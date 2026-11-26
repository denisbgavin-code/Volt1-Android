package com.volt1.recorder

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One finished file in Music/Volt1 Recorder. */
data class RecordingItem(
    val id: Long,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSeconds: Long
)

/** Android-free rules for the recordings list. */
object RecordingsCatalog {

    const val RELATIVE_PATH_PREFIX = "Music/Volt1 Recorder"
    private const val MAX_BASE_LENGTH = 120
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")
    private val SPACES = Regex("""\s+""")

    /**
     * Cleans a user-entered name for MediaStore DISPLAY_NAME: strips path
     * and reserved characters, trims, keeps it short and always ends in
     * ".wav". Returns null when nothing usable is left or the name is
     * unchanged.
     */
    fun sanitizeRename(input: String, current: String): String? {
        var base = input.trim()
        if (base.endsWith(".wav", ignoreCase = true)) base = base.dropLast(4)
        base = base.replace(FORBIDDEN, "_").replace(SPACES, " ").trim().trim('.', ' ')
        if (base.isEmpty() || base.all { it == '_' }) return null
        if (base.length > MAX_BASE_LENGTH) base = base.take(MAX_BASE_LENGTH).trimEnd('.', ' ')
        val name = "$base.wav"
        return if (name == current) null else name
    }

    /** Text shown pre-filled in the rename dialog. */
    fun editableBase(displayName: String): String =
        if (displayName.endsWith(".wav", ignoreCase = true)) displayName.dropLast(4) else displayName

    /**
     * MediaStore DURATION can be 0/missing for freshly published files;
     * for our canonical 44-byte header mono PCM24/48 it follows from size.
     */
    fun effectiveDurationMs(durationMs: Long, sizeBytes: Long): Long {
        if (durationMs > 0L) return durationMs
        val pcm = sizeBytes - WavHeader.HEADER_BYTES
        if (pcm <= 0L) return 0L
        return (pcm / Pcm24.BYTES_PER_SAMPLE) * 1000L / 48_000L
    }

    /** "0:07", "3:07", "1:02:03". */
    fun formatDuration(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "812 KB", "12.3 MB", "1.20 GB" (binary units). */
    fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val b = bytes.coerceAtLeast(0L).toDouble()
        return when {
            b < 1024.0 * 1024.0 -> String.format(locale, "%.0f KB", b / 1024.0)
            b < 1024.0 * 1024.0 * 1024.0 -> String.format(locale, "%.1f MB", b / (1024.0 * 1024.0))
            else -> String.format(locale, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatDate(
        epochSeconds: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", locale)
            .apply { this.timeZone = timeZone }
            .format(Date(epochSeconds * 1000L))

    /** Newest first; ties by name for a stable order. */
    fun sorted(items: List<RecordingItem>): List<RecordingItem> =
        items.sortedWith(
            compareByDescending<RecordingItem> { it.dateAddedSeconds }.thenBy { it.displayName }
        )

    /** Working edit files live in the same folder but are never listed. */
    fun isListed(displayName: String?): Boolean =
        displayName != null &&
            displayName.endsWith(".wav", ignoreCase = true) &&
            !RecordingGuards.isWorkingName(displayName)
}
