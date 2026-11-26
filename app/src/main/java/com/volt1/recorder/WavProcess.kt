package com.volt1.recorder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Android-free sample processing for the editor (48 kHz / 24-bit / mono).
 * Every operation streams source -> sink in blocks and keeps the length.
 */
object WavProcess {

    const val NORMALIZE_TARGET_DB = -1.0

    /** Search radius for zero-crossing snapping: 2.5 ms. */
    const val ZERO_CROSSING_RADIUS = 120

    private const val BLOCK_FRAMES = 64 * 1024

    /**
     * Linear fade over frames [start, end): gain 0 -> 1 (fade-in) or
     * 1 -> 0 (fade-out), endpoints inclusive. All other frames are copied
     * bit-exact.
     */
    fun fade(
        channel: FileChannel,
        layout: WavLayout,
        sink: PcmSink,
        startFrame: Long,
        endFrameExclusive: Long,
        fadeIn: Boolean
    ) {
        WavEdit.validateEditable(layout)
        val (start, end) = WavEdit.clampRange(layout.totalFrames, startFrame, endFrameExclusive)
        val count = end - start
        val denominator = (count - 1).coerceAtLeast(1L).toDouble()

        WavEdit.copyFrames(channel, layout, sink, 0L, start)
        transform(channel, layout, sink, start, end) { index, sample ->
            if (count <= 1L) {
                if (fadeIn) 0 else sample
            } else {
                val ratio = (index - start) / denominator
                val gain = if (fadeIn) ratio else 1.0 - ratio
                (sample * gain).toInt()
            }
        }
        WavEdit.copyFrames(channel, layout, sink, end, layout.totalFrames)
    }

    /** Exact absolute peak of frames [start, end). */
    fun peak(channel: FileChannel, layout: WavLayout, startFrame: Long, endFrameExclusive: Long): Int {
        WavEdit.validateEditable(layout)
        var peak = 0
        forEachBlock(channel, layout, startFrame, endFrameExclusive) { bytes, frames, _ ->
            peak = max(peak, Pcm24.peakAbs(bytes, 0, frames))
        }
        return peak
    }

    /** Gain that brings [peakAbs] to [targetDb] dBFS. */
    fun normalizeGain(peakAbs: Int, targetDb: Double = NORMALIZE_TARGET_DB): Double {
        require(peakAbs > 0) { "Тишина: нормализовать нечего" }
        return Pcm24.MAX_VALUE * 10.0.pow(targetDb / 20.0) / peakAbs
    }

    /**
     * Scales the whole file so its peak sits at [targetDb] dBFS (boost or
     * cut). Rounds to nearest; returns the applied gain.
     */
    fun normalize(
        channel: FileChannel,
        layout: WavLayout,
        sink: PcmSink,
        targetDb: Double = NORMALIZE_TARGET_DB
    ): Double {
        val peak = peak(channel, layout, 0L, layout.totalFrames)
        if (peak == 0) error("Тишина: нормализовать нечего")
        val gain = normalizeGain(peak, targetDb)
        transform(channel, layout, sink, 0L, layout.totalFrames) { _, sample ->
            (sample * gain).roundToLong().coerceIn(Pcm24.MIN_VALUE.toLong(), Pcm24.MAX_VALUE.toLong()).toInt()
        }
        return gain
    }

    /**
     * Nearest frame boundary within [radius] of [frame] where the signal
     * crosses zero; returns [frame] itself when there is none. A boundary b
     * lies between samples b-1 and b; it qualifies if either is 0 or they
     * have opposite signs. 0 and totalFrames always qualify (file edges).
     */
    fun nearestZeroCrossing(
        channel: FileChannel,
        layout: WavLayout,
        frame: Long,
        radius: Int = ZERO_CROSSING_RADIUS
    ): Long {
        WavEdit.validateEditable(layout)
        val total = layout.totalFrames
        val target = frame.coerceIn(0L, total)
        val from = max(0L, target - radius - 1)
        val to = min(total, target + radius + 1)
        val count = (to - from).toInt()
        if (count <= 0) return target

        val bytes = ByteArray(count * Pcm24.BYTES_PER_SAMPLE)
        readFullyAt(channel, ByteBuffer.wrap(bytes), layout.frameOffset(from))
        val samples = IntArray(count) { Pcm24.decode(bytes, it * Pcm24.BYTES_PER_SAMPLE) }

        return nearestZeroCrossing(samples, from, total, target, radius)
    }

    /** Array form of [nearestZeroCrossing]; samples[0] is frame [firstFrame]. */
    fun nearestZeroCrossing(
        samples: IntArray,
        firstFrame: Long,
        totalFrames: Long,
        target: Long,
        radius: Int
    ): Long {
        fun qualifies(boundary: Long): Boolean {
            if (boundary <= 0L || boundary >= totalFrames) return true
            val a = (boundary - 1 - firstFrame).toInt()
            val b = (boundary - firstFrame).toInt()
            if (a < 0 || b >= samples.size) return false
            val x = samples[a]
            val y = samples[b]
            return x == 0 || y == 0 || (x < 0) != (y < 0)
        }

        for (distance in 0..radius) {
            val left = target - distance
            val right = target + distance
            // Prefer the side with the quieter sample at equal distance.
            val leftOk = left >= 0L && qualifies(left)
            val rightOk = distance > 0 && right <= totalFrames && qualifies(right)
            if (leftOk && rightOk) {
                return if (magnitudeAt(samples, firstFrame, left) <= magnitudeAt(samples, firstFrame, right)) left else right
            }
            if (leftOk) return left
            if (rightOk) return right
        }
        return target
    }

    private fun magnitudeAt(samples: IntArray, firstFrame: Long, boundary: Long): Int {
        val i = (boundary - firstFrame).toInt().coerceIn(0, samples.lastIndex)
        return abs(samples[i])
    }

    /**
     * Peak envelope of frames [start, end) with [points] bins (exact: every
     * frame is read). Used for the zoomed-in detail view.
     */
    fun peaksRange(
        channel: FileChannel,
        layout: WavLayout,
        startFrame: Long,
        endFrameExclusive: Long,
        points: Int
    ): FloatArray {
        WavEdit.validateEditable(layout)
        val (start, end) = WavEdit.clampRange(layout.totalFrames, startFrame, endFrameExclusive)
        val frames = end - start
        val n = points.coerceIn(2, 20_000)
        val peaks = IntArray(n)

        forEachBlock(channel, layout, start, end) { bytes, count, blockStart ->
            for (i in 0 until count) {
                val frame = blockStart + i - start
                val bin = ((frame * n) / frames).toInt().coerceAtMost(n - 1)
                val s = Pcm24.decode(bytes, i * Pcm24.BYTES_PER_SAMPLE)
                val m = if (s < 0) -s else s
                if (m > peaks[bin]) peaks[bin] = m
            }
        }

        // Bins narrower than one frame (deep zoom) get the frame they sit on.
        if (frames < n) {
            for (bin in 0 until n) {
                if (peaks[bin] == 0) {
                    val frame = (bin.toLong() * frames / n).coerceAtMost(frames - 1)
                    val srcBin = ((frame * n) / frames).toInt().coerceAtMost(n - 1)
                    peaks[bin] = peaks[srcBin]
                }
            }
        }
        return FloatArray(n) { Pcm24.peakToLinear(peaks[it]) }
    }

    private inline fun transform(
        channel: FileChannel,
        layout: WavLayout,
        sink: PcmSink,
        startFrame: Long,
        endFrameExclusive: Long,
        crossinline map: (frame: Long, sample: Int) -> Int
    ) {
        forEachBlock(channel, layout, startFrame, endFrameExclusive) { bytes, count, blockStart ->
            for (i in 0 until count) {
                val offset = i * Pcm24.BYTES_PER_SAMPLE
                val mapped = map(blockStart + i, Pcm24.decode(bytes, offset))
                Pcm24.encode(mapped.coerceIn(Pcm24.MIN_VALUE, Pcm24.MAX_VALUE), bytes, offset)
            }
            sink.write(ByteBuffer.wrap(bytes), count * Pcm24.BYTES_PER_SAMPLE)
        }
    }

    private inline fun forEachBlock(
        channel: FileChannel,
        layout: WavLayout,
        startFrame: Long,
        endFrameExclusive: Long,
        block: (bytes: ByteArray, frames: Int, firstFrame: Long) -> Unit
    ) {
        if (endFrameExclusive <= startFrame) return
        val bytes = ByteArray(BLOCK_FRAMES * Pcm24.BYTES_PER_SAMPLE)
        var frame = startFrame
        while (frame < endFrameExclusive) {
            val count = min(BLOCK_FRAMES.toLong(), endFrameExclusive - frame).toInt()
            val buffer = ByteBuffer.wrap(bytes, 0, count * Pcm24.BYTES_PER_SAMPLE)
            readFullyAt(channel, buffer, layout.frameOffset(frame))
            block(bytes, count, frame)
            frame += count
        }
    }
}
