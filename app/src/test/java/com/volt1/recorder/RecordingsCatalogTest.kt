package com.volt1.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

class RecordingsCatalogTest {

    private val current = "Volt1_2026-11-26_22-10-05_24bit_48k.wav"

    @Test
    fun renameSanitizes() {
        assertEquals("Vocal take 3.wav", RecordingsCatalog.sanitizeRename("  Vocal   take 3 ", current))
        assertEquals("Vocal.wav", RecordingsCatalog.sanitizeRename("Vocal.WAV", current))
        assertEquals("a_b_c_d.wav", RecordingsCatalog.sanitizeRename("a/b\\c:d", current))
        assertEquals("x_y.wav", RecordingsCatalog.sanitizeRename("x\u0000y", current))
        assertEquals("Песня.wav", RecordingsCatalog.sanitizeRename("Песня", current))
        assertEquals("name.wav", RecordingsCatalog.sanitizeRename("..name..", current))
        assertEquals("take.mp3.wav", RecordingsCatalog.sanitizeRename("take.mp3", current))
    }

    @Test
    fun renameRejectsEmptyOrUnchanged() {
        assertNull(RecordingsCatalog.sanitizeRename("", current))
        assertNull(RecordingsCatalog.sanitizeRename("   ", current))
        assertNull(RecordingsCatalog.sanitizeRename(".wav", current))
        assertNull(RecordingsCatalog.sanitizeRename("///", current))
        assertNull(RecordingsCatalog.sanitizeRename("...", current))
        assertNull(RecordingsCatalog.sanitizeRename(current.removeSuffix(".wav"), current))
        assertNull(RecordingsCatalog.sanitizeRename(current, current))
    }

    @Test
    fun renameLimitsLength() {
        val name = RecordingsCatalog.sanitizeRename("a".repeat(500), current)!!
        assertEquals(124, name.length)
        assertTrue(name.endsWith(".wav"))
    }

    @Test
    fun renameFuzzNeverProducesReservedCharacters() {
        val random = Random(70)
        val alphabet = "abcXYZ019 _-./\\:*?\"<>|\u0001\u007f\tёЖ."
        repeat(20_000) {
            val input = String(CharArray(random.nextInt(0, 40)) { alphabet[random.nextInt(alphabet.length)] })
            val out = RecordingsCatalog.sanitizeRename(input, current) ?: return@repeat
            assertTrue(out, out.endsWith(".wav"))
            assertTrue(out, out.none { it in "/\\:*?\"<>|" || it.code < 0x20 || it.code == 0x7f })
            assertFalse(out, out.startsWith(".") || out.startsWith(" "))
            assertTrue(out.length <= 124)
        }
    }

    @Test
    fun editableBase() {
        assertEquals("take", RecordingsCatalog.editableBase("take.wav"))
        assertEquals("take", RecordingsCatalog.editableBase("take.WAV"))
        assertEquals("take", RecordingsCatalog.editableBase("take"))
    }

    @Test
    fun durationFallsBackToFileSize() {
        assertEquals(1234L, RecordingsCatalog.effectiveDurationMs(1234L, 0L))
        assertEquals(1_000L, RecordingsCatalog.effectiveDurationMs(0L, 44L + 144_000L))
        assertEquals(60_000L, RecordingsCatalog.effectiveDurationMs(-1L, 44L + 144_000L * 60 + 1))
        assertEquals(0L, RecordingsCatalog.effectiveDurationMs(0L, 44L))
        assertEquals(0L, RecordingsCatalog.effectiveDurationMs(0L, 0L))
    }

    @Test
    fun formats() {
        assertEquals("0:00", RecordingsCatalog.formatDuration(0))
        assertEquals("0:01", RecordingsCatalog.formatDuration(500))
        assertEquals("3:07", RecordingsCatalog.formatDuration(187_000))
        assertEquals("1:02:03", RecordingsCatalog.formatDuration(3_723_000))
        assertEquals("0:00", RecordingsCatalog.formatDuration(-5))

        assertEquals("0 KB", RecordingsCatalog.formatSize(0, Locale.US))
        assertEquals("812 KB", RecordingsCatalog.formatSize(812 * 1024L, Locale.US))
        assertEquals("12.3 MB", RecordingsCatalog.formatSize((12.3 * 1024 * 1024).toLong(), Locale.US))
        assertEquals("1.20 GB", RecordingsCatalog.formatSize((1.2 * 1024 * 1024 * 1024).toLong(), Locale.US))
        assertEquals("12,3 MB", RecordingsCatalog.formatSize((12.3 * 1024 * 1024).toLong(), Locale.forLanguageTag("ru")))

        assertEquals(
            "26.11.2026 22:10",
            RecordingsCatalog.formatDate(1_795_731_000L, Locale.US, TimeZone.getTimeZone("UTC"))
        )
    }

    @Test
    fun sortsNewestFirstStable() {
        val items = listOf(
            RecordingItem(1, "b.wav", 0, 0, 100),
            RecordingItem(2, "a.wav", 0, 0, 100),
            RecordingItem(3, "c.wav", 0, 0, 300),
            RecordingItem(4, "d.wav", 0, 0, 50)
        )
        assertEquals(listOf(3L, 2L, 1L, 4L), RecordingsCatalog.sorted(items).map { it.id })
    }

    @Test
    fun listingExcludesWorkingFilesAndNonWav() {
        assertTrue(RecordingsCatalog.isListed(current))
        assertTrue(RecordingsCatalog.isListed("My take.WAV"))
        assertFalse(RecordingsCatalog.isListed("Volt1_Working_Cut_1.wav"))
        assertFalse(RecordingsCatalog.isListed("notes.txt"))
        assertFalse(RecordingsCatalog.isListed(null))
    }
}
