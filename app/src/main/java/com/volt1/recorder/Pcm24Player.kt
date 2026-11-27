package com.volt1.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Bit-exact PCM24 playback of a frame range. Each play() runs on its own
 * worker thread that owns its own AudioTrack; stop() only signals and
 * interrupts the current one, so a finishing worker can never release the
 * track of the playback that replaced it.
 */
class Pcm24Player {

    private var stopFlag = AtomicBoolean(true)

    // Guarded by this: the track of the current (latest) playback only.
    private var activeTrack: AudioTrack? = null

    @Synchronized
    fun play(
        context: Context,
        info: WavInfo,
        startFrame: Long,
        endFrameExclusive: Long,
        onProgress: (Long) -> Unit,
        onFinished: (String?) -> Unit
    ) {
        stop()

        val localStop = AtomicBoolean(false)
        stopFlag = localStop

        Thread(
            {
                var track: AudioTrack? = null
                var errorMessage: String? = null

                try {
                    track = createTrack(info)
                    if (!publishTrack(localStop, track)) return@Thread

                    playRange(
                        context = context,
                        info = info,
                        track = track,
                        startFrame = startFrame,
                        endFrameExclusive = endFrameExclusive,
                        stop = localStop,
                        onProgress = onProgress
                    )
                } catch (t: Throwable) {
                    errorMessage = t.message ?: t.javaClass.simpleName
                } finally {
                    if (track != null) {
                        clearTrack(track)
                        runCatching {
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                track.stop()
                            }
                        }
                        runCatching { track.flush() }
                        runCatching { track.release() }
                    }

                    if (!localStop.get()) {
                        onFinished(errorMessage)
                    }
                }
            },
            "Volt1-Selection-Playback"
        ).start()
    }

    @Synchronized
    fun stop() {
        stopFlag.set(true)

        val track = activeTrack
        activeTrack = null

        // pause() interrupts a blocking write(); flush() drops queued audio.
        runCatching { track?.pause() }
        runCatching { track?.flush() }
    }

    @Synchronized
    private fun publishTrack(localStop: AtomicBoolean, track: AudioTrack): Boolean {
        if (localStop.get()) return false
        activeTrack = track
        return true
    }

    @Synchronized
    private fun clearTrack(track: AudioTrack) {
        if (activeTrack === track) activeTrack = null
    }

    private fun createTrack(info: WavInfo): AudioTrack {
        WavFile.validateEditable(info)

        val minBuffer = AudioTrack.getMinBufferSize(
            info.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_24BIT_PACKED
        )

        if (minBuffer <= 0) {
            error("Android не поддерживает PCM24 playback")
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                    .setSampleRate(info.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                PlaybackMath.alignUp(max(minBuffer * 2, 48 * 1024), info.frameSizeBytes)
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            error("AudioTrack не инициализирован")
        }

        return track
    }

    private fun playRange(
        context: Context,
        info: WavInfo,
        track: AudioTrack,
        startFrame: Long,
        endFrameExclusive: Long,
        stop: AtomicBoolean,
        onProgress: (Long) -> Unit
    ) {
        val (safeStart, safeEnd) =
            WavEdit.clampRange(info.totalFrames, startFrame, endFrameExclusive)
        val selectionFrames = safeEnd - safeStart
        val totalFrames = info.totalFrames
        val frameSize = info.frameSizeBytes

        val trackBufferBytes = track.bufferSizeInFrames * frameSize
        val buffer = ByteBuffer.allocateDirect(
            PlaybackMath.alignUp(max(trackBufferBytes, 48 * 1024), frameSize)
        )

        val pfd = context.contentResolver.openFileDescriptor(info.uri, "r")
            ?: error("Не удалось открыть WAV")

        // Absolute frame of the file that is audible right now.
        fun report(playedFrames: Long) {
            onProgress(safeStart + playedFrames.coerceIn(0L, selectionFrames))
        }

        pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val channel = input.channel
                var filePosition = info.dataOffset + safeStart * frameSize
                var remaining = selectionFrames * frameSize

                report(0L)
                track.play()

                while (remaining > 0L && !stop.get()) {
                    val requested = min(buffer.capacity().toLong(), remaining).toInt()
                    buffer.clear()
                    buffer.limit(requested)

                    while (buffer.hasRemaining() && !stop.get()) {
                        val read = channel.read(buffer, filePosition)
                        if (read < 0) error("Неожиданный конец WAV при playback")
                        filePosition += read
                    }

                    if (stop.get()) return

                    buffer.flip()

                    // Small writes: a blocking write returns as soon as ~21 ms
                    // of space frees up, so the position is reported ~50x/s
                    // instead of once per ~340 ms buffer.
                    val chunkBytes = WRITE_CHUNK_FRAMES * frameSize
                    while (buffer.hasRemaining() && !stop.get()) {
                        val written = track.write(
                            buffer,
                            min(buffer.remaining(), chunkBytes),
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written < 0) error("AudioTrack.write(): $written")
                        report(presentedFrames(track, info.sampleRate))
                    }

                    remaining -= requested
                }

                awaitDrain(track, info.sampleRate, selectionFrames, stop, ::report)
            }
        }
    }

    /**
     * Waits until every written frame has been played.
     *
     * A streaming AudioTrack that never filled its buffer (short selections)
     * does not start, and the final partial period may never be mixed, so
     * the head can stall short of the end forever. If the head stops
     * advancing, stop() forces the remaining frames out; the head reads 0
     * after stop(), so progress is then estimated from the clock.
     */
    private fun awaitDrain(
        track: AudioTrack,
        sampleRate: Int,
        selectionFrames: Long,
        stop: AtomicBoolean,
        report: (Long) -> Unit
    ) {
        var lastHead = -1L
        var lastChange = SystemClock.elapsedRealtime()

        while (!stop.get()) {
            val head = headFrames(track).coerceAtMost(selectionFrames)
            report(presentedFrames(track, sampleRate).coerceAtMost(selectionFrames))

            if (head >= selectionFrames) return

            val now = SystemClock.elapsedRealtime()
            if (head != lastHead) {
                lastHead = head
                lastChange = now
            } else if (now - lastChange >= STALL_TIMEOUT_MS) {
                runCatching { track.stop() }

                val drainStart = SystemClock.elapsedRealtime()
                val drainMs =
                    PlaybackMath.framesToMs(selectionFrames - head, sampleRate) + DRAIN_MARGIN_MS

                while (!stop.get()) {
                    val elapsed = SystemClock.elapsedRealtime() - drainStart
                    if (elapsed >= drainMs) break
                    val estimated = head + elapsed * sampleRate / 1000L
                    report(estimated.coerceAtMost(selectionFrames))
                    SystemClock.sleep(PROGRESS_POLL_MS)
                }

                if (!stop.get()) report(selectionFrames)
                return
            }

            SystemClock.sleep(PROGRESS_POLL_MS)
        }
    }

    private fun headFrames(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and 0xffffffffL

    /**
     * Frame being presented at the speaker now, from AudioTrack.getTimestamp
     * (latency-compensated, fine-grained); falls back to the head position.
     * Only used for display; drain/stall logic keeps the raw head.
     */
    private fun presentedFrames(track: AudioTrack, sampleRate: Int): Long {
        // Fresh object per call: workers of consecutive playbacks may overlap.
        val timestamp = android.media.AudioTimestamp()
        val ok = runCatching { track.getTimestamp(timestamp) }.getOrDefault(false)
        if (!ok || timestamp.nanoTime <= 0L) return headFrames(track)
        val sinceNanos = (System.nanoTime() - timestamp.nanoTime).coerceAtLeast(0L)
        return PlaybackMath.presentedFrame(timestamp.framePosition, sinceNanos, sampleRate)
    }

    companion object {
        private const val PROGRESS_POLL_MS = 20L
        private const val WRITE_CHUNK_FRAMES = 1_024
        private const val STALL_TIMEOUT_MS = 300L
        private const val DRAIN_MARGIN_MS = 50L
    }
}

/** Android-free arithmetic used by [Pcm24Player]. */
object PlaybackMath {

    fun alignUp(value: Int, alignment: Int): Int {
        require(alignment > 0)
        val remainder = value % alignment
        return if (remainder == 0) value else value + alignment - remainder
    }

    /** Frame position extrapolated [sinceNanos] past a timestamp. */
    fun presentedFrame(framePosition: Long, sinceNanos: Long, sampleRate: Int): Long =
        (framePosition + sinceNanos.coerceAtLeast(0L) * sampleRate / 1_000_000_000L).coerceAtLeast(0L)

    fun framesToMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate <= 0 || frames <= 0L) 0L else (frames * 1000L + sampleRate - 1) / sampleRate

    /** Absolute timeline position (0..1) of [playedFrames] into a selection. */
    fun progress(
        selectionStart: Long,
        playedFrames: Long,
        selectionFrames: Long,
        totalFrames: Long
    ): Float {
        if (totalFrames <= 0L) return 0f
        val absolute = selectionStart + playedFrames.coerceIn(0L, selectionFrames)
        return (absolute.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
