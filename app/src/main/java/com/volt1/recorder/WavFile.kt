package com.volt1.recorder

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.channels.FileChannel

data class WavInfo(
    val uri: Uri,
    val displayName: String,
    val layout: WavLayout
) {
    val audioFormat: Int get() = layout.audioFormat
    val channels: Int get() = layout.channels
    val sampleRate: Int get() = layout.sampleRate
    val bitsPerSample: Int get() = layout.bitsPerSample
    val dataOffset: Long get() = layout.dataOffset
    val dataSize: Long get() = layout.dataSize
    val frameSizeBytes: Int get() = layout.frameSizeBytes
    val totalFrames: Long get() = layout.totalFrames
    val durationMs: Long get() = layout.durationMs
}

/** ContentResolver glue around the Android-free [WavHeader] / [WavEdit]. */
object WavFile {

    fun readInfo(context: Context, uri: Uri, displayName: String): WavInfo {
        val layout = withChannel(context, uri, "Не удалось открыть WAV") {
            WavHeader.parse(it)
        }
        WavEdit.validateEditable(layout)
        return WavInfo(uri, displayName, layout)
    }

    fun validateEditable(info: WavInfo) = WavEdit.validateEditable(info.layout)

    fun loadWaveform(context: Context, info: WavInfo, pointCount: Int = 6_000): FloatArray {
        validateEditable(info)
        return withChannel(context, info.uri, "Не удалось открыть WAV для waveform") {
            WavEdit.computePeaks(it, info.layout, pointCount)
        }
    }

    /** New pending working file containing only [start, end). */
    fun cropSelectionWorking(
        context: Context,
        info: WavInfo,
        startFrame: Long,
        endFrameExclusive: Long
    ): Uri = writeWorking(context, info, "Volt1_Working_Crop_") { channel, sink ->
        WavEdit.crop(channel, info.layout, sink, startFrame, endFrameExclusive)
    }

    /** New pending working file with [start, end) removed and the join faded. */
    fun deleteSelectionWorking(
        context: Context,
        info: WavInfo,
        startFrame: Long,
        endFrameExclusive: Long
    ): Uri = writeWorking(context, info, "Volt1_Working_Cut_") { channel, sink ->
        WavEdit.deleteRange(channel, info.layout, sink, startFrame, endFrameExclusive)
    }

    fun exportToUri(context: Context, info: WavInfo, destinationUri: Uri) {
        validateEditable(info)

        val resolver = context.contentResolver

        resolver.openInputStream(info.uri)
            ?.use { source ->
                // "wt" truncates: plain "w" may leave stale bytes behind
                // when overwriting a larger existing document.
                val output = resolver.openOutputStream(destinationUri, "wt")
                    ?: error("Не удалось открыть выбранное место сохранения")

                output.use { target ->
                    source.copyTo(target, 256 * 1024)
                    target.flush()
                }
            }
            ?: error("Не удалось открыть текущую редакцию")
    }

    fun discardPending(context: Context, uri: Uri?) {
        if (uri == null) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private inline fun <T> withChannel(
        context: Context,
        uri: Uri,
        openError: String,
        block: (FileChannel) -> T
    ): T {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error(openError)

        return pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                block(input.channel)
            }
        }
    }

    private inline fun writeWorking(
        context: Context,
        info: WavInfo,
        namePrefix: String,
        body: (FileChannel, PcmSink) -> Unit
    ): Uri {
        validateEditable(info)

        val writer = Wav24Writer(
            context = context,
            sampleRate = info.sampleRate,
            channels = info.channels,
            displayNameOverride = "$namePrefix${System.currentTimeMillis()}.wav"
        )

        var completed = false

        try {
            withChannel(context, info.uri, "Не удалось открыть текущую редакцию") {
                body(it, writer)
            }
            writer.closeKeepingPending()
            completed = true
            return writer.uri
        } finally {
            if (!completed) runCatching { writer.abort() }
        }
    }
}
