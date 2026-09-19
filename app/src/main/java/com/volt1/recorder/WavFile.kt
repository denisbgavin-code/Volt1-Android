package com.volt1.recorder

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class WavInfo(
    val uri: Uri,
    val displayName: String,
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
        get() = if (sampleRate > 0) {
            totalFrames * 1000L / sampleRate
        } else {
            0L
        }
}

object WavFile {

    private const val PCM_FORMAT = 1
    private const val REQUIRED_SAMPLE_RATE = 48_000
    private const val REQUIRED_BITS = 24
    private const val REQUIRED_CHANNELS = 1
    private const val PCM24_MAX = 8_388_607f

    fun readInfo(
        context: Context,
        uri: Uri,
        displayName: String
    ): WavInfo {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: error("Не удалось открыть WAV")

        pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val channel = input.channel

                val riffHeader = ByteBuffer
                    .allocate(12)
                    .order(ByteOrder.LITTLE_ENDIAN)

                readFully(channel, riffHeader)
                riffHeader.flip()

                val riff = readAscii(riffHeader, 4)
                riffHeader.int
                val wave = readAscii(riffHeader, 4)

                if (riff != "RIFF" || wave != "WAVE") {
                    error("Файл не является RIFF/WAVE")
                }

                var formatCode = -1
                var channels = -1
                var sampleRate = -1
                var bitsPerSample = -1
                var dataOffset = -1L
                var dataSize = -1L

                var position = 12L
                val fileSize = channel.size()

                while (position + 8L <= fileSize) {
                    channel.position(position)

                    val chunkHeader = ByteBuffer
                        .allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN)

                    readFully(channel, chunkHeader)
                    chunkHeader.flip()

                    val chunkId = readAscii(chunkHeader, 4)
                    val chunkSize =
                        chunkHeader.int.toLong() and 0xffffffffL
                    val payloadOffset = position + 8L

                    if (payloadOffset + chunkSize > fileSize) {
                        error("Повреждённый WAV chunk: $chunkId")
                    }

                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize < 16L) {
                                error("Некорректный fmt chunk")
                            }

                            val fmt = ByteBuffer
                                .allocate(16)
                                .order(ByteOrder.LITTLE_ENDIAN)

                            channel.position(payloadOffset)
                            readFully(channel, fmt)
                            fmt.flip()

                            formatCode = fmt.short.toInt() and 0xffff
                            channels = fmt.short.toInt() and 0xffff
                            sampleRate = fmt.int
                            fmt.int
                            fmt.short
                            bitsPerSample =
                                fmt.short.toInt() and 0xffff
                        }

                        "data" -> {
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

                    position =
                        payloadOffset + chunkSize + (chunkSize and 1L)
                }

                if (
                    formatCode < 0 ||
                    channels <= 0 ||
                    sampleRate <= 0 ||
                    bitsPerSample <= 0 ||
                    dataOffset < 0L ||
                    dataSize < 0L
                ) {
                    error(
                        "WAV не содержит обязательные fmt/data chunks"
                    )
                }

                val info = WavInfo(
                    uri = uri,
                    displayName = displayName,
                    audioFormat = formatCode,
                    channels = channels,
                    sampleRate = sampleRate,
                    bitsPerSample = bitsPerSample,
                    dataOffset = dataOffset,
                    dataSize = dataSize
                )

                validateEditable(info)
                return info
            }
        }
    }

    fun validateEditable(info: WavInfo) {
        if (info.audioFormat != PCM_FORMAT) {
            error("Редактор поддерживает только PCM WAV")
        }
        if (info.sampleRate != REQUIRED_SAMPLE_RATE) {
            error(
                "Редактор ожидает 48 кГц, найдено " +
                    "${info.sampleRate} Гц"
            )
        }
        if (info.bitsPerSample != REQUIRED_BITS) {
            error(
                "Редактор ожидает 24 bit, найдено " +
                    "${info.bitsPerSample} bit"
            )
        }
        if (info.channels != REQUIRED_CHANNELS) {
            error(
                "Редактор ожидает mono WAV, найдено " +
                    "${info.channels} канал(а)"
            )
        }
        if (info.frameSizeBytes != 3) {
            error("Некорректный PCM24 frame size")
        }
        if (info.totalFrames <= 0L) {
            error("WAV не содержит аудиоданных")
        }
    }

    fun loadWaveform(
        context: Context,
        info: WavInfo,
        pointCount: Int = 900
    ): FloatArray {
        validateEditable(info)

        val points = pointCount.coerceIn(64, 1600)
        val peaks = FloatArray(points)
        val totalFrames = info.totalFrames

        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(info.uri, "r")
            ?: error("Не удалось открыть WAV для waveform")

        pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val channel = input.channel
                val maxFramesPerBin = 512

                for (bin in 0 until points) {
                    val binStart =
                        totalFrames * bin / points
                    val binEnd =
                        totalFrames * (bin + 1L) / points
                    val binFrames =
                        max(1L, binEnd - binStart)

                    val framesToRead = min(
                        binFrames,
                        maxFramesPerBin.toLong()
                    ).toInt()

                    val sampleStart =
                        binStart +
                            max(
                                0L,
                                (binFrames - framesToRead) / 2L
                            )

                    channel.position(
                        info.dataOffset +
                            sampleStart * info.frameSizeBytes
                    )

                    val bytesToRead =
                        framesToRead * info.frameSizeBytes
                    val buffer =
                        ByteBuffer.allocate(bytesToRead)

                    readFully(channel, buffer)
                    buffer.flip()

                    var peak = 0

                    while (buffer.remaining() >= 3) {
                        val b0 =
                            buffer.get().toInt() and 0xff
                        val b1 =
                            buffer.get().toInt() and 0xff
                        val b2 =
                            buffer.get().toInt() and 0xff

                        var sample =
                            b0 or (b1 shl 8) or (b2 shl 16)

                        if ((sample and 0x800000) != 0) {
                            sample = sample or -0x1000000
                        }

                        peak = max(peak, abs(sample))
                    }

                    peaks[bin] =
                        (peak / PCM24_MAX).coerceIn(0f, 1f)
                }
            }
        }

        return peaks
    }

    fun saveSelection(
        context: Context,
        info: WavInfo,
        startFrame: Long,
        endFrameExclusive: Long
    ): String {
        validateEditable(info)

        val start =
            startFrame.coerceIn(
                0L,
                info.totalFrames - 1L
            )

        val end =
            endFrameExclusive.coerceIn(
                start + 1L,
                info.totalFrames
            )

        val baseName = info.displayName
            .removeSuffix(".wav")
            .removeSuffix(".WAV")

        val writer = Wav24Writer(
            context = context,
            sampleRate = info.sampleRate,
            channels = info.channels,
            displayNameOverride =
                "${baseName}_edit_${System.currentTimeMillis()}.wav"
        )

        var completed = false

        try {
            val resolver = context.contentResolver
            val pfd =
                resolver.openFileDescriptor(info.uri, "r")
                    ?: error(
                        "Не удалось открыть исходный WAV"
                    )

            pfd.use { descriptor ->
                FileInputStream(
                    descriptor.fileDescriptor
                ).use { input ->
                    val channel = input.channel

                    val startByte =
                        info.dataOffset +
                            start * info.frameSizeBytes

                    var remaining =
                        (end - start) *
                            info.frameSizeBytes

                    channel.position(startByte)

                    val blockSize =
                        alignDown(
                            256 * 1024,
                            info.frameSizeBytes
                        )

                    val buffer =
                        ByteBuffer.allocateDirect(blockSize)

                    while (remaining > 0L) {
                        buffer.clear()

                        val requested = min(
                            buffer.capacity().toLong(),
                            remaining
                        ).toInt()

                        buffer.limit(requested)

                        var readTotal = 0
                        while (readTotal < requested) {
                            val read = channel.read(buffer)
                            if (read < 0) {
                                error(
                                    "Неожиданный конец " +
                                        "исходного WAV"
                                )
                            }
                            readTotal += read
                        }

                        writer.write(buffer, readTotal)
                        remaining -= readTotal
                    }
                }
            }

            writer.closeAndPublish()
            completed = true
            return writer.displayName

        } finally {
            if (!completed) {
                runCatching { writer.abort() }
            }
        }
    }

    private fun alignDown(
        value: Int,
        alignment: Int
    ): Int = value - (value % alignment)

    private fun readFully(
        channel: java.nio.channels.FileChannel,
        buffer: ByteBuffer
    ) {
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) {
                error("Неожиданный конец WAV")
            }
        }
    }

    private fun readAscii(
        buffer: ByteBuffer,
        count: Int
    ): String {
        val bytes = ByteArray(count)
        buffer.get(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }
}
