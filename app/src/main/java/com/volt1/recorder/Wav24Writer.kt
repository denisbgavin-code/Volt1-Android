package com.volt1.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Wav24Writer(
    context: Context,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1
) {
    private val resolver = context.contentResolver

    val displayName: String =
        "Volt1_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) +
            "_24bit_48k.wav"

    private val uri: Uri
    private val descriptor: android.os.ParcelFileDescriptor
    private val output: FileOutputStream
    private val channel: FileChannel

    private var dataBytes = 0L
    private var closed = false

    init {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/Volt1 Recorder"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        uri = resolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Не удалось создать запись MediaStore")

        try {
            descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: error("Не удалось открыть WAV")
            output = FileOutputStream(descriptor.fileDescriptor)
            channel = output.channel
            writeFully(createHeader(0L))
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun write(source: ByteBuffer, bytes: Int) {
        check(!closed) { "WAV уже закрыт" }
        require(bytes >= 0 && bytes % FRAME_SIZE_BYTES == 0) {
            "PCM24 block должен быть кратен 3 байтам"
        }

        if (dataBytes + bytes > MAX_PCM_BYTES) {
            error("Достигнут предел стандартного RIFF/WAV (~4 GiB)")
        }

        source.position(0)
        source.limit(bytes)
        writeFully(source)
        dataBytes += bytes
    }

    fun closeAndPublish() {
        if (closed) return

        channel.position(0L)
        writeFully(createHeader(dataBytes))
        channel.force(true)

        channel.close()
        output.close()
        descriptor.close()

        resolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            },
            null,
            null
        )

        closed = true
    }

    fun abort() {
        if (closed) return

        runCatching { channel.close() }
        runCatching { output.close() }
        runCatching { descriptor.close() }
        resolver.delete(uri, null, null)
        closed = true
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun createHeader(pcmBytes: Long): ByteBuffer {
        val bitsPerSample = 24
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign

        return ByteBuffer
            .allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putAscii("RIFF")
                putInt((36L + pcmBytes).toInt())
                putAscii("WAVE")

                putAscii("fmt ")
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())

                putAscii("data")
                putInt(pcmBytes.toInt())
                flip()
            }
    }

    private fun ByteBuffer.putAscii(value: String) {
        put(value.toByteArray(Charsets.US_ASCII))
    }

    companion object {
        private const val WAV_HEADER_BYTES = 44
        private const val FRAME_SIZE_BYTES = 3
        private const val MAX_PCM_BYTES = 0xfffffff0L
    }
}
