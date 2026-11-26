package com.volt1.recorder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Streams PCM24 into a pending MediaStore WAV. The header is rewritten with
 * the final sizes on close; until [closeAndPublish] succeeds the entry stays
 * IS_PENDING and [abort] deletes it.
 */
class Wav24Writer(
    context: Context,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
    displayNameOverride: String? = null
) : PcmSink {

    private val resolver = context.contentResolver

    val displayName: String =
        displayNameOverride ?: (
            "Volt1_" +
                SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) +
                "_24bit_48k.wav"
            )

    val uri: Uri
    private val descriptor: ParcelFileDescriptor
    private val output: FileOutputStream
    private val channel: FileChannel

    private val frameSizeBytes = channels * Pcm24.BYTES_PER_SAMPLE

    var dataBytes = 0L
        private set

    private var closed = false
    private var published = false

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

        uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать запись MediaStore")

        var openedDescriptor: ParcelFileDescriptor? = null
        try {
            openedDescriptor = resolver.openFileDescriptor(uri, "rw")
                ?: error("Не удалось открыть WAV")
            descriptor = openedDescriptor
            output = FileOutputStream(openedDescriptor.fileDescriptor)
            channel = output.channel
            writeFully(header(0L))
        } catch (t: Throwable) {
            runCatching { openedDescriptor?.close() }
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    override fun write(source: ByteBuffer, bytes: Int) {
        check(!closed) { "WAV уже закрыт" }
        require(bytes >= 0 && bytes % frameSizeBytes == 0) {
            "PCM24 block должен быть кратен frame size"
        }

        if (dataBytes + bytes > WavHeader.MAX_PCM_BYTES) {
            error("Достигнут предел стандартного RIFF/WAV (~4 GiB)")
        }

        source.limit(bytes)
        source.position(0)
        writeFully(source)
        dataBytes += bytes
    }

    fun closeAndPublish() {
        if (published) return
        if (!closed) finalizeAndClose()

        val updated = resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
            null,
            null
        )

        if (updated <= 0) error("MediaStore не опубликовал WAV")
        published = true
    }

    fun closeKeepingPending() {
        if (closed) return
        finalizeAndClose()
    }

    private fun finalizeAndClose() {
        WavHeader.finalize(channel, sampleRate, channels, 24, dataBytes)
        channel.force(true)

        channel.close()
        output.close()
        descriptor.close()

        closed = true
    }

    /**
     * Deletes an incomplete file. A file whose header was already finalized
     * is complete audio and is never deleted here.
     */
    fun abort() {
        if (closed) return

        runCatching { channel.close() }
        runCatching { output.close() }
        runCatching { descriptor.close() }
        closed = true
        resolver.delete(uri, null, null)
    }

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun header(pcmBytes: Long): ByteBuffer =
        WavHeader.create(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = 24,
            pcmBytes = pcmBytes
        )
}
