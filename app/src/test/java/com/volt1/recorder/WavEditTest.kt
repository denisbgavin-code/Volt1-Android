package com.volt1.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class WavEditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- independent reference implementations (sample arrays) ----------

    private fun refClamp(total: Int, start: Long, end: Long): Pair<Int, Int> {
        val s = start.coerceIn(0L, total - 1L).toInt()
        val e = end.coerceIn(s + 1L, total.toLong()).toInt()
        return s to e
    }

    private fun refFade(samples: IntArray, fadeIn: Boolean): IntArray {
        val n = samples.size
        return IntArray(n) { i ->
            val ratio = i.toDouble() / (n - 1).toDouble()
            val gain = if (fadeIn) ratio else 1.0 - ratio
            (samples[i] * gain).toInt().coerceIn(-8_388_608, 8_388_607)
        }
    }

    private fun refDelete(samples: IntArray, start: Long, end: Long): IntArray {
        val (s, e) = refClamp(samples.size, start, end)
        val fade = min(48_000 * 5 / 1000, min(s, samples.size - e))
        if (fade <= 1) return samples.copyOfRange(0, s) + samples.copyOfRange(e, samples.size)
        return samples.copyOfRange(0, s - fade) +
            refFade(samples.copyOfRange(s - fade, s), fadeIn = false) +
            refFade(samples.copyOfRange(e, e + fade), fadeIn = true) +
            samples.copyOfRange(e + fade, samples.size)
    }

    private fun refPeaks(samples: IntArray, points: Int, cap: Int = WavEdit.MAX_FRAMES_PER_BIN): FloatArray {
        val total = samples.size.toLong()
        val n = points.coerceIn(64, 12_000)
        return FloatArray(n) { bin ->
            val bs = total * bin / n
            val be = total * (bin + 1L) / n
            val bf = max(1L, be - bs)
            val read = min(bf, cap.toLong())
            val from = bs + (bf - read) / 2
            var peak = 0L
            for (i in from until from + read) peak = max(peak, abs(samples[i.toInt()].toLong()))
            (peak / 8_388_607.0).toFloat().coerceIn(0f, 1f)
        }
    }

    private fun wav(samples: IntArray): File = TestWav.write(tmp.newFile(), samples)

    // ---- validation --------------------------------------------------------

    @Test
    fun validateEditableAcceptsOnlyPcm24Mono48k() {
        val ok = WavLayout(1, 1, 48_000, 24, 44, 3)
        WavEdit.validateEditable(ok)

        val bad = listOf(
            ok.copy(audioFormat = 3) to "PCM",
            ok.copy(sampleRate = 44_100) to "48",
            ok.copy(bitsPerSample = 16, dataSize = 2) to "24",
            ok.copy(channels = 2, dataSize = 6) to "mono",
            ok.copy(dataSize = 0) to "аудиоданных",
            ok.copy(dataSize = 2) to "аудиоданных"
        )
        for ((layout, message) in bad) {
            val e = assertThrows(IllegalStateException::class.java) { WavEdit.validateEditable(layout) }
            assertTrue("${e.message} should mention $message", e.message!!.contains(message))
        }
    }

    @Test
    fun clampRangeAlwaysReturnsNonEmptyRangeInsideFile() {
        val random = Random(20)
        repeat(50_000) {
            val total = random.nextLong(1, 1_000_000)
            val s = random.nextLong(-2 * total, 2 * total)
            val e = random.nextLong(-2 * total, 2 * total)
            val (a, b) = WavEdit.clampRange(total, s, e)
            assertTrue(a in 0 until total)
            assertTrue(b in (a + 1)..total)
        }
        assertEquals(0L to 1L, WavEdit.clampRange(1, 0, 0))
        assertEquals(9L to 10L, WavEdit.clampRange(10, 50, 60))
        assertThrows(IllegalArgumentException::class.java) { WavEdit.clampRange(0, 0, 1) }
    }

    // ---- crop ----------------------------------------------------------------

    @Test
    fun cropIsBitExact() {
        val random = Random(21)
        val samples = TestWav.randomSamples(random, 30_000)
        val source = wav(samples)

        val cases = listOf(0L to 30_000L, 0L to 1L, 29_999L to 30_000L, 100L to 200L, 5L to 29_000L, -10L to 10L, 25_000L to 99_999L)
        for ((s, e) in cases) {
            val out = TestWav.edit(source, tmp.newFile()) { ch, layout, sink -> WavEdit.crop(ch, layout, sink, s, e) }
            val (rs, re) = refClamp(samples.size, s, e)
            assertArrayEquals("crop $s..$e", samples.copyOfRange(rs, re), out)
        }
    }

    @Test
    fun cropAcrossManyCopyBlocks() {
        // > 256 KiB of PCM forces several copy blocks, including a partial one.
        val random = Random(22)
        val samples = TestWav.randomSamples(random, 300_001)
        val source = wav(samples)
        val target = tmp.newFile()
        val out = TestWav.edit(source, target) { ch, layout, sink ->
            WavEdit.crop(ch, layout, sink, 7, 299_995)
        }
        assertArrayEquals(samples.copyOfRange(7, 299_995), out)
        assertEquals(299_988L, TestWav.layout(target).totalFrames)
    }

    // ---- delete ---------------------------------------------------------------

    @Test
    fun deleteMatchesReferenceInFixedCases() {
        val random = Random(23)
        val samples = TestWav.randomSamples(random, 20_000)
        val source = wav(samples)

        val cases = listOf(
            0L to 100L,          // at start: no audio before -> no fade
            19_900L to 20_000L,  // at end: no audio after -> no fade
            1L to 2L,            // 1 frame before -> fade <= 1 -> plain cut
            2L to 3L,            // 2 frames before -> 2-frame fade
            10_000L to 10_001L,  // single frame deleted mid-file, full 240-frame fades
            300L to 19_850L,     // big middle cut, before=300 after=150 -> fade=150
            239L to 500L,        // just under full fade before
            240L to 500L,        // exactly full fade before
            5_000L to 19_760L,   // exactly 240 after
            -5L to 50L,          // clamped start
            19_000L to 50_000L   // clamped end
        )
        for ((s, e) in cases) {
            val out = TestWav.edit(source, tmp.newFile()) { ch, layout, sink -> WavEdit.deleteRange(ch, layout, sink, s, e) }
            val expected = refDelete(samples, s, e)
            val (rs, re) = refClamp(samples.size, s, e)
            assertEquals("length $s..$e", samples.size - (re - rs), out.size)
            assertArrayEquals("delete $s..$e", expected, out)
        }
    }

    @Test
    fun deleteRandomizedAgainstReference() {
        val random = Random(24)
        repeat(150) { iteration ->
            val total = random.nextInt(2, 6_000)
            val samples = TestWav.randomSamples(random, total, amplitude = if (iteration % 3 == 0) 1000 else Pcm24.MAX_VALUE)
            val source = wav(samples)
            val s = random.nextLong(0, total.toLong())
            val e = random.nextLong(s + 1, total + 1L)
            if (s == 0L && e == total.toLong()) return@repeat

            val out = TestWav.edit(source, tmp.newFile()) { ch, layout, sink -> WavEdit.deleteRange(ch, layout, sink, s, e) }
            assertArrayEquals("iteration $iteration total=$total $s..$e", refDelete(samples, s, e), out)
            source.delete()
        }
    }

    @Test
    fun deleteKeepsUntouchedAudioBitExact() {
        val random = Random(25)
        val samples = TestWav.randomSamples(random, 48_000)
        val out = TestWav.edit(wav(samples), tmp.newFile()) { ch, layout, sink ->
            WavEdit.deleteRange(ch, layout, sink, 10_000, 20_000)
        }
        // Everything outside the 2 x 240 fade windows is untouched.
        assertArrayEquals(samples.copyOfRange(0, 9_760), out.copyOfRange(0, 9_760))
        assertArrayEquals(samples.copyOfRange(20_240, 48_000), out.copyOfRange(10_240, 38_000))
        // Join is silent at the splice point (fade-out ends at 0, fade-in starts at 0).
        assertEquals(0, out[9_999])
        assertEquals(0, out[10_000])
    }

    @Test
    fun deletingWholeTrackIsRefusedAndWritesNothing() {
        val samples = IntArray(1_000) { it }
        val source = wav(samples)
        var written = 0
        val sink = PcmSink { _, bytes -> written += bytes }
        TestWav.open(source) { ch ->
            val layout = WavHeader.parse(ch)
            val e = assertThrows(IllegalStateException::class.java) {
                WavEdit.deleteRange(ch, layout, sink, 0, 1_000)
            }
            assertTrue(e.message!!.contains("весь трек"))
            // Out-of-range requests that clamp to the whole track too.
            assertThrows(IllegalStateException::class.java) {
                WavEdit.deleteRange(ch, layout, sink, -100, 5_000)
            }
        }
        assertEquals(0, written)
    }

    @Test
    fun editsRejectNonEditableFormats() {
        val layout = WavLayout(1, 2, 48_000, 24, 44, 600)
        val sink = PcmSink { _, _ -> error("must not write") }
        TestWav.open(wav(IntArray(200))) { ch ->
            assertThrows(IllegalStateException::class.java) { WavEdit.crop(ch, layout, sink, 0, 10) }
            assertThrows(IllegalStateException::class.java) { WavEdit.deleteRange(ch, layout, sink, 0, 10) }
            assertThrows(IllegalStateException::class.java) { WavEdit.computePeaks(ch, layout, 100) }
        }
    }

    @Test
    fun truncatedSourceFailsInsteadOfWritingSilence() {
        val samples = IntArray(1_000) { it }
        val source = wav(samples)
        // Header claims more data than present (e.g. file truncated after parse).
        val lying = WavLayout(1, 1, 48_000, 24, 44, 3_000L * 3)
        TestWav.open(source) { ch ->
            assertThrows(IllegalStateException::class.java) {
                WavEdit.crop(ch, lying, PcmSink { _, _ -> }, 0, 3_000)
            }
        }
    }

    @Test
    fun repeatedEditsComposeLikeReference() {
        val random = Random(26)
        var expected = TestWav.randomSamples(random, 40_000)
        var file = wav(expected)
        repeat(12) { step ->
            val total = expected.size
            val s = random.nextLong(0, total - 1L)
            val e = random.nextLong(s + 1, min(total.toLong(), s + 5_000) + 1)
            val crop = step % 4 == 3
            if (!crop && s == 0L && e == total.toLong()) return@repeat
            val next = tmp.newFile()
            val out = TestWav.edit(file, next) { ch, layout, sink ->
                if (crop) WavEdit.crop(ch, layout, sink, s, min(total.toLong(), e + 10_000))
                else WavEdit.deleteRange(ch, layout, sink, s, e)
            }
            expected = if (crop) {
                val (rs, re) = refClamp(total, s, min(total.toLong(), e + 10_000))
                expected.copyOfRange(rs, re)
            } else refDelete(expected, s, e)
            assertArrayEquals("step $step", expected, out)
            file = next
        }
    }

    // ---- waveform peaks ----------------------------------------------------------

    @Test
    fun peaksMatchReferenceForShortAndLongFiles() {
        val random = Random(27)
        for (total in intArrayOf(1, 10, 63, 64, 65, 1_000, 48_000, 300_000, 1_200_000)) {
            val samples = TestWav.randomSamples(random, total, amplitude = 2_000_000)
            // A few loud transients at random places.
            repeat(5) { samples[random.nextInt(total)] = if (random.nextBoolean()) Pcm24.MAX_VALUE else Pcm24.MIN_VALUE }
            val source = wav(samples)
            for (points in intArrayOf(10, 64, 500, 8_000, 20_000)) {
                val peaks = TestWav.open(source) { ch ->
                    WavEdit.computePeaks(ch, WavHeader.parse(ch), points)
                }
                assertArrayEquals("total=$total points=$points", refPeaks(samples, points), peaks, 0f)
            }
            source.delete()
        }
    }

    @Test
    fun peaksAreExactWhenBinsFitTheReadCap() {
        // 8000 points x 4096 frames = 32.7 M frames (~11 min) are scanned fully.
        // Smaller equivalent: every frame of every bin is inspected, so a single
        // transient anywhere is always visible.
        val total = 8_000 * 64
        val random = Random(28)
        repeat(20) {
            val samples = IntArray(total)
            val position = random.nextInt(total)
            samples[position] = 5_000_000
            val peaks = TestWav.open(wav(samples)) { ch -> WavEdit.computePeaks(ch, WavHeader.parse(ch), 8_000) }
            val bin = (position.toLong() * 8_000 / total).toInt()
            assertEquals(5_000_000 / 8_388_607f, peaks[bin], 1e-6f)
            assertEquals(1, peaks.count { it > 0f })
        }
    }

    @Test
    fun peaksOfSilenceAndFullScale() {
        val silent = TestWav.open(wav(IntArray(10_000))) { ch -> WavEdit.computePeaks(ch, WavHeader.parse(ch), 100) }
        assertTrue(silent.all { it == 0f })
        val full = TestWav.open(wav(IntArray(10_000) { if (it % 2 == 0) Pcm24.MIN_VALUE else Pcm24.MAX_VALUE })) { ch ->
            WavEdit.computePeaks(ch, WavHeader.parse(ch), 100)
        }
        assertTrue(full.all { it == 1f })
    }
}
