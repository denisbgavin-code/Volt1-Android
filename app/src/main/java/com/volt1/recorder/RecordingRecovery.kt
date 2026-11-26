package com.volt1.recorder

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Finds this app's MediaStore entries that are still IS_PENDING after the
 * process died (crash, kill, reboot, battery) and either repairs and
 * publishes interrupted recordings or deletes leftover working edit files.
 */
object RecordingRecovery {

    data class Result(
        val recovered: List<String>,
        val failed: List<String>,
        val discardedWorkingFiles: Int
    )

    fun run(context: Context): Result {
        val recovered = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var discarded = 0

        // The running capture owns its pending file; never touch it.
        if (RecorderService.active) return Result(recovered, failed, 0)

        val resolver = context.contentResolver

        for ((uri, name) in pendingEntries(resolver)) {
            if (RecorderService.active || name == RecorderService.currentFile) break

            when {
                RecordingGuards.isWorkingName(name) -> {
                    if (runCatching { resolver.delete(uri, null, null) }.isSuccess) discarded++
                }

                RecordingGuards.isRecordingName(name) -> {
                    val result = runCatching { recover(resolver, uri) }
                    when (result.getOrNull()) {
                        true -> recovered += name
                        false -> runCatching { resolver.delete(uri, null, null) }
                        null -> failed += name
                    }
                }
            }
        }

        return Result(recovered, failed, discarded)
    }

    /** true = published, false = nothing recoverable. Throws on damage. */
    private fun recover(resolver: ContentResolver, uri: Uri): Boolean {
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: error("Не удалось открыть WAV")

        val pcmBytes = pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    WavHeader.recoverStreamed(
                        channel = input.channel,
                        sampleRate = WavEdit.REQUIRED_SAMPLE_RATE,
                        channels = WavEdit.REQUIRED_CHANNELS,
                        bitsPerSample = WavEdit.REQUIRED_BITS,
                        writeChannel = output.channel
                    )
                }
            }
        }

        if (pcmBytes <= 0L) return false

        val updated = resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
            null,
            null
        )
        if (updated <= 0) error("MediaStore не опубликовал WAV")
        return true
    }

    private fun pendingEntries(resolver: ContentResolver): List<Pair<Uri, String>> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("Music/Volt1 Recorder%")
            )
        }

        val entries = mutableListOf<Pair<Uri, String>>()
        resolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
            args,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                entries += ContentUris.withAppendedId(collection, cursor.getLong(idColumn)) to name
            }
        }
        return entries
    }
}
