package com.volt1.recorder

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/** Destination for raw PCM bytes (implemented by [Wav24Writer]). */
fun interface PcmSink {
    /** Writes bytes [0, bytes) of [source]; position/limit may be changed. */
    fun write(source: ByteBuffer, bytes: Int)
}

/** Android-free description of the PCM layout of a RIFF/WAVE file. */
data class WavLayout(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long
) {
    val frameSizeBytes: Int
        get() = channels * bitsPerSample / 8

    val totalFrames: Long
        get() = if (frameSizeBytes > 0) dataSize / frameSizeBytes else 0L

    val durationMs: Long
        get() = if (sampleRate > 0) totalFrames * 1000L / sampleRate else 0L

    fun frameOffset(frame: Long): Long = dataOffset + frame * frameSizeBytes
}

object WavHeader {

    const val HEADER_BYTES = 44
    const val PCM_FORMAT = 1

    /** Largest PCM payload whose RIFF size (incl. pad byte) fits in 32 bits. */
    const val MAX_PCM_BYTES = 0xffffffffL - 37L

    /**
     * Canonical 44-byte PCM header. An odd [pcmBytes] count is followed by a
     * RIFF pad byte, which is included in the RIFF chunk size.
     */
    fun create(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        pcmBytes: Long
    ): ByteBuffer {
        require(pcmBytes in 0L..MAX_PCM_BYTES) { "PCM size out of RIFF range" }

        val blockAlign = channels * bitsPerSample / 8
        val pad = pcmBytes and 1L

        return ByteBuffer.allocate(HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(RIFF)
                putInt((36L + pcmBytes + pad).toInt())
                put(WAVE)
                put(FMT)
                putInt(16)
                putShort(PCM_FORMAT.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * blockAlign)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put(DATA)
                putInt(pcmBytes.toInt())
                flip()
            }
    }

    /**
     * Completes a file whose PCM was streamed after a placeholder header:
     * appends the RIFF pad byte for odd payloads at the current end and
     * rewrites the header with the final sizes.
     */
    fun finalize(
        channel: FileChannel,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        pcmBytes: Long
    ) {
        if ((pcmBytes and 1L) != 0L) {
            writeFullyAt(channel, ByteBuffer.allocate(1), HEADER_BYTES + pcmBytes)
        }
        writeFullyAt(channel, create(sampleRate, channels, bitsPerSample, pcmBytes), 0L)
    }

    /**
     * Repairs a file streamed by [Wav24Writer] that was never finalized
     * (process killed, phone rebooted). Such a file always has our canonical
     * 44-byte header with the expected format and PCM from byte 44 on.
     *
     * Only audio up to the last checkpoint is kept: the checkpoint is
     * written right after a successful capture-path validation and fsync'd,
     * so it never covers unvalidated audio or blocks that a power loss may
     * have left unwritten. The file is truncated to it and finalized.
     * Returns the recovered PCM byte count (0 = nothing to recover).
     * Fails closed when the file is not in the expected layout.
     */
    fun recoverStreamed(
        channel: FileChannel,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        writeChannel: FileChannel = channel
    ): Long {
        val size = channel.size()
        if (size < HEADER_BYTES) error("Слишком короткий WAV для восстановления")

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        readFullyAt(channel, header, 0L)
        header.flip()

        val frameSize = channels * bitsPerSample / 8
        val expected =
            matches(header, 0, RIFF) &&
                matches(header, 8, WAVE) &&
                matches(header, 12, FMT) &&
                header.getInt(16) == 16 &&
                (header.getShort(20).toInt() and 0xffff) == PCM_FORMAT &&
                (header.getShort(22).toInt() and 0xffff) == channels &&
                header.getInt(24) == sampleRate &&
                (header.getShort(32).toInt() and 0xffff) == frameSize &&
                (header.getShort(34).toInt() and 0xffff) == bitsPerSample &&
                matches(header, 36, DATA)

        if (!expected || frameSize <= 0) {
            error("WAV не в формате Volt 1 Recorder; восстановление отменено")
        }

        val checkpointed = header.getInt(40).toLong() and 0xffffffffL
        val available = size - HEADER_BYTES
        val pcmBytes = minOf(checkpointed, available).let { it - it % frameSize }

        writeChannel.truncate(HEADER_BYTES + pcmBytes)
        finalize(writeChannel, sampleRate, channels, bitsPerSample, pcmBytes)
        writeChannel.force(true)
        return pcmBytes
    }

    /** Parses the RIFF chunk list; fails closed on any structural damage. */
    fun parse(channel: FileChannel): WavLayout {
        val fileSize = channel.size()
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)

        header.limit(12)
        readFullyAt(channel, header, 0L)
        header.flip()

        if (!matches(header, 0, RIFF) || !matches(header, 8, WAVE)) {
            error("Файл не является RIFF/WAVE")
        }

        var formatCode = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1L
        var dataSize = -1L

        var position = 12L

        while (position + 8L <= fileSize) {
            header.clear()
            header.limit(8)
            readFullyAt(channel, header, position)
            header.flip()

            val chunkSize = header.getInt(4).toLong() and 0xffffffffL
            val payloadOffset = position + 8L

            if (payloadOffset + chunkSize > fileSize) {
                error("Повреждённый WAV chunk: ${chunkName(header)}")
            }

            when {
                matches(header, 0, FMT) -> {
                    if (chunkSize < 16L) error("Некорректный fmt chunk")

                    header.clear()
                    readFullyAt(channel, header, payloadOffset)
                    header.flip()

                    formatCode = header.getShort(0).toInt() and 0xffff
                    channels = header.getShort(2).toInt() and 0xffff
                    sampleRate = header.getInt(4)
                    bitsPerSample = header.getShort(14).toInt() and 0xffff
                }

                matches(header, 0, DATA) -> {
                    dataOffset = payloadOffset
                    dataSize = chunkSize
                }
            }

            if (
                formatCode >= 0 &&
                channels > 0 &&
                sampleRate > 0 &&
                bitsPerSample > 0 &&
                dataOffset >= 0L
            ) {
                break
            }

            position = payloadOffset + chunkSize + (chunkSize and 1L)
        }

        if (
            formatCode < 0 ||
            channels <= 0 ||
            sampleRate <= 0 ||
            bitsPerSample <= 0 ||
            dataOffset < 0L
        ) {
            error("WAV не содержит обязательные fmt/data chunks")
        }

        return WavLayout(
            audioFormat = formatCode,
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize
        )
    }

    private fun matches(buffer: ByteBuffer, offset: Int, tag: ByteArray): Boolean {
        for (i in tag.indices) {
            if (buffer.get(offset + i) != tag[i]) return false
        }
        return true
    }

    private fun chunkName(buffer: ByteBuffer): String =
        String(ByteArray(4) { buffer.get(it) }, Charsets.US_ASCII)

    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    private val FMT = "fmt ".toByteArray(Charsets.US_ASCII)
    private val DATA = "data".toByteArray(Charsets.US_ASCII)
}

/** Validation and frame-level editing of 48 kHz / 24-bit / mono PCM WAV. */
object WavEdit {

    const val REQUIRED_SAMPLE_RATE = 48_000
    const val REQUIRED_BITS = 24
    const val REQUIRED_CHANNELS = 1
    const val DECLICK_FADE_MS = 5L

    /** Upper bound of frames read per waveform bin (sampled beyond it). */
    const val MAX_FRAMES_PER_BIN = 4_096

    private const val COPY_BLOCK_BYTES = 256 * 1024

    fun validateEditable(layout: WavLayout) {
        if (layout.audioFormat != WavHeader.PCM_FORMAT) {
            error("Редактор поддерживает только PCM WAV")
        }
        if (layout.sampleRate != REQUIRED_SAMPLE_RATE) {
            error("Редактор ожидает 48 кГц, найдено ${layout.sampleRate} Гц")
        }
        if (layout.bitsPerSample != REQUIRED_BITS) {
            error("Редактор ожидает 24 bit, найдено ${layout.bitsPerSample} bit")
        }
        if (layout.channels != REQUIRED_CHANNELS) {
            error("Редактор ожидает mono WAV, найдено ${layout.channels} канал(а)")
        }
        if (layout.frameSizeBytes != Pcm24.BYTES_PER_SAMPLE) {
            error("Некорректный PCM24 frame size")
        }
        if (layout.totalFrames <= 0L) {
            error("WAV не содержит аудиоданных")
        }
    }

    /** Clamps a requested range to a non-empty [start, end) inside the file. */
    fun clampRange(totalFrames: Long, start: Long, endExclusive: Long): Pair<Long, Long> {
        require(totalFrames > 0L) { "Пустой WAV" }
        val safeStart = start.coerceIn(0L, totalFrames - 1L)
        val safeEnd = endExclusive.coerceIn(safeStart + 1L, totalFrames)
        return safeStart to safeEnd
    }

    /** Writes only frames [start, end) to [sink]. */
    fun crop(
        channel: FileChannel,
        layout: WavLayout,
        sink: PcmSink,
        startFrame: Long,
        endFrameExclusive: Long
    ) {
        validateEditable(layout)
        val (start, end) = clampRange(layout.totalFrames, startFrame, endFrameExclusive)
        copyFrames(channel, layout, sink, start, end)
    }

    /**
     * Writes the file without frames [start, end). When there is audio on
     * both sides of the cut, the last [DECLICK_FADE_MS] before the cut are
     * faded out and the first ones after it faded in, so the join is click
     * free. Output length is always totalFrames - (end - start).
     */
    fun deleteRange(
        channel: FileChannel,
        layout: WavLayout,
        sink: PcmSink,
        startFrame: Long,
        endFrameExclusive: Long
    ) {
        validateEditable(layout)
        val total = layout.totalFrames
        val (start, end) = clampRange(total, startFrame, endFrameExclusive)

        if (start == 0L && end == total) {
            error("Нельзя удалить весь трек")
        }

        val fadeFrames = min(
            layout.sampleRate * DECLICK_FADE_MS / 1000L,
            min(start, total - end)
        ).toInt()

        if (fadeFrames > 1) {
            val fadeStart = start - fadeFrames
            copyFrames(channel, layout, sink, 0L, fadeStart)
            writeFadedFrames(channel, layout, sink, fadeStart, fadeFrames, fadeIn = false)
            writeFadedFrames(channel, layout, sink, end, fadeFrames, fadeIn = true)
            copyFrames(channel, layout, sink, end + fadeFrames, total)
        } else {
            copyFrames(channel, layout, sink, 0L, start)
            copyFrames(channel, layout, sink, end, total)
        }
    }

    fun copyFrames(
        channel: FileChannel,
        layout: WavLayout,
        sink: PcmSink,
        startFrame: Long,
        endFrameExclusive: Long
    ) {
        if (endFrameExclusive <= startFrame) return

        val frameSize = layout.frameSizeBytes
        var position = layout.frameOffset(startFrame)
        var remaining = (endFrameExclusive - startFrame) * frameSize

        val buffer = ByteBuffer.allocateDirect(
            COPY_BLOCK_BYTES - COPY_BLOCK_BYTES % frameSize
        )

        while (remaining > 0L) {
            val requested = min(buffer.capacity().toLong(), remaining).toInt()
            buffer.clear()
            buffer.limit(requested)
            readFullyAt(channel, buffer, position)
            sink.write(buffer, requested)
            position += requested
            remaining -= requested
        }
    }

    private fun writeFadedFrames(
        channel: FileChannel,
        layout: WavLayout,
        sink: PcmSink,
        startFrame: Long,
        frameCount: Int,
        fadeIn: Boolean
    ) {
        if (frameCount <= 0) return

        val bytes = ByteArray(frameCount * layout.frameSizeBytes)
        val buffer = ByteBuffer.wrap(bytes)
        readFullyAt(channel, buffer, layout.frameOffset(startFrame))
        Pcm24.applyLinearFade(bytes, frameCount, fadeIn)
        sink.write(buffer, bytes.size)
    }

    /**
     * Peak envelope with [pointCount] bins over the whole file. Each bin is
     * read in full up to [MAX_FRAMES_PER_BIN] frames (exact peaks for files
     * up to ~11 minutes at 8000 points); longer bins read a centred window.
     */
    fun computePeaks(
        channel: FileChannel,
        layout: WavLayout,
        pointCount: Int
    ): FloatArray {
        validateEditable(layout)

        val points = pointCount.coerceIn(64, 12_000)
        val peaks = FloatArray(points)
        val totalFrames = layout.totalFrames
        val frameSize = layout.frameSizeBytes

        val bytes = ByteArray(MAX_FRAMES_PER_BIN * frameSize)
        val buffer = ByteBuffer.wrap(bytes)

        for (bin in 0 until points) {
            val binStart = totalFrames * bin / points
            val binEnd = totalFrames * (bin + 1L) / points
            val binFrames = max(1L, binEnd - binStart)
            val framesToRead = min(binFrames, MAX_FRAMES_PER_BIN.toLong()).toInt()
            val sampleStart = binStart + (binFrames - framesToRead) / 2L

            buffer.clear()
            buffer.limit(framesToRead * frameSize)
            readFullyAt(channel, buffer, layout.frameOffset(sampleStart))

            peaks[bin] = Pcm24.peakToLinear(Pcm24.peakAbs(bytes, 0, framesToRead))
        }

        return peaks
    }
}

/** Positional read of exactly buffer.remaining() bytes starting at [position]. */
internal fun readFullyAt(channel: FileChannel, buffer: ByteBuffer, position: Long) {
    var offset = position
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, offset)
        if (read < 0) error("Неожиданный конец WAV")
        offset += read
    }
}

internal fun writeFullyAt(channel: FileChannel, buffer: ByteBuffer, position: Long) {
    var offset = position
    while (buffer.hasRemaining()) {
        offset += channel.write(buffer, offset)
    }
}
