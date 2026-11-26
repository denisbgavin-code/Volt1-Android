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
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

class WavProcessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun wav(samples: IntArray): File = TestWav.write(tmp.newFile(), samples)

    private fun refFade(samples: IntArray, s: Int, e: Int, fadeIn: Boolean): IntArray {
        val out = samples.copyOf()
        val n = e - s
        for (i in s until e) {
            out[i] = if (n <= 1) (if (fadeIn) 0 else samples[i]) else {
                val ratio = (i - s).toDouble() / (n - 1).toDouble()
                val gain = if (fadeIn) ratio else 1.0 - ratio
                (samples[i] * gain).toInt().coerceIn(Pcm24.MIN_VALUE, Pcm24.MAX_VALUE)
            }
        }
        return out
    }

    @Test
    fun fadeMatchesReferenceAndKeepsEverythingElse() {
        val random = Random(80)
        val samples = TestWav.randomSamples(random, 200_000) // > 1 block (65 536)
        val source = wav(samples)
        val cases = listOf(0 to 100, 1_000 to 150_000, 199_000 to 200_000, 5 to 6, 65_530 to 131_080)
        for ((s, e) in cases) for (fadeIn in booleanArrayOf(true, false)) {
            val out = TestWav.edit(source, tmp.newFile()) { ch, l, sink ->
                WavProcess.fade(ch, l, sink, s.toLong(), e.toLong(), fadeIn)
            }
            assertArrayEquals("fade $s..$e in=$fadeIn", refFade(samples, s, e, fadeIn), out)
        }
    }

    @Test
    fun fadeEndpoints() {
        val samples = IntArray(1_000) { 1_000_000 }
        val fin = TestWav.edit(wav(samples), tmp.newFile()) { ch, l, sink -> WavProcess.fade(ch, l, sink, 100, 200, true) }
        assertEquals(0, fin[100]); assertEquals(1_000_000, fin[199]); assertEquals(1_000_000, fin[99])
        val fout = TestWav.edit(wav(samples), tmp.newFile()) { ch, l, sink -> WavProcess.fade(ch, l, sink, 100, 200, false) }
        assertEquals(1_000_000, fout[100]); assertEquals(0, fout[199]); assertEquals(1_000_000, fout[200])
        // monotonic
        for (i in 101 until 200) assertTrue(fin[i] >= fin[i - 1] && fout[i] <= fout[i - 1])
    }

    @Test
    fun normalizeHitsTargetAndPreservesShape() {
        val random = Random(81)
        val samples = TestWav.randomSamples(random, 100_000, amplitude = 1_000_000)
        val source = wav(samples)
        var gain = 0.0
        val out = TestWav.edit(source, tmp.newFile()) { ch, l, sink -> gain = WavProcess.normalize(ch, l, sink) }
        val peakIn = samples.maxOf { abs(it) }
        val target = 8_388_607 * 10.0.pow(-1.0 / 20.0)
        assertEquals(target / peakIn, gain, 1e-12)
        assertEquals(target, out.maxOf { abs(it) }.toDouble(), 1.0)
        for (i in samples.indices) {
            assertEquals((samples[i] * gain).roundToLong().toInt(), out[i])
        }
    }

    @Test
    fun normalizeAlsoAttenuatesAndRejectsSilence() {
        val loud = IntArray(1_000) { if (it % 2 == 0) Pcm24.MAX_VALUE else Pcm24.MIN_VALUE }
        val out = TestWav.edit(wav(loud), tmp.newFile()) { ch, l, sink -> WavProcess.normalize(ch, l, sink) }
        assertTrue(out.maxOf { abs(it) } < Pcm24.MAX_VALUE)
        assertEquals(8_388_607 * 10.0.pow(-1.0 / 20.0), out.maxOf { abs(it) }.toDouble(), 1.0)

        TestWav.open(wav(IntArray(500))) { ch ->
            val l = WavHeader.parse(ch)
            assertThrows(IllegalStateException::class.java) { WavProcess.normalize(ch, l, PcmSink { _, _ -> }) }
        }
        assertThrows(IllegalArgumentException::class.java) { WavProcess.normalizeGain(0) }
    }

    @Test
    fun peakIsExact() {
        val random = Random(82)
        val samples = TestWav.randomSamples(random, 150_000, amplitude = 100_000)
        samples[123_456] = -7_000_000
        TestWav.open(wav(samples)) { ch ->
            val l = WavHeader.parse(ch)
            assertEquals(7_000_000, WavProcess.peak(ch, l, 0, l.totalFrames))
            assertEquals(samples.copyOfRange(0, 1000).maxOf { abs(it) }, WavProcess.peak(ch, l, 0, 1000))
            assertEquals(0, WavProcess.peak(ch, l, 10, 10))
        }
    }

    // ---- zero crossings -----------------------------------------------------

    private fun refZero(samples: IntArray, target: Long, radius: Int): Long {
        val total = samples.size.toLong()
        fun ok(b: Long) = b <= 0 || b >= total || samples[(b - 1).toInt()].let { x ->
            val y = samples[b.toInt()]; x == 0 || y == 0 || (x < 0) != (y < 0)
        }
        val t = target.coerceIn(0, total)
        for (d in 0..radius) {
            val l = t - d; val r = t + d
            val lo = l >= 0 && ok(l); val ro = d > 0 && r <= total && ok(r)
            fun mag(b: Long) = abs(samples[b.coerceIn(0, total - 1).toInt()])
            if (lo && ro) return if (mag(l) <= mag(r)) l else r
            if (lo) return l
            if (ro) return r
        }
        return t
    }

    @Test
    fun zeroCrossingFindsNearestBoundary() {
        val s = intArrayOf(5, 4, 3, -1, -2, -3, 2, 6, 7, 8)
        val samples = s
        val file = wav(samples)
        TestWav.open(file) { ch ->
            val l = WavHeader.parse(ch)
            assertEquals(3L, WavProcess.nearestZeroCrossing(ch, l, 3, 10))  // between 3 and -1
            assertEquals(3L, WavProcess.nearestZeroCrossing(ch, l, 2, 10))
            assertEquals(6L, WavProcess.nearestZeroCrossing(ch, l, 6, 10))  // between -3 and 2
            assertEquals(6L, WavProcess.nearestZeroCrossing(ch, l, 8, 10))
            assertEquals(0L, WavProcess.nearestZeroCrossing(ch, l, 1, 10))  // file edge closer
            assertEquals(10L, WavProcess.nearestZeroCrossing(ch, l, 10, 10))
            assertEquals(8L, WavProcess.nearestZeroCrossing(ch, l, 8, 1))   // none in radius 1
        }
    }

    @Test
    fun zeroCrossingRandomizedAgainstReference() {
        val random = Random(83)
        repeat(300) {
            val n = random.nextInt(1, 3_000)
            val samples = IntArray(n) { random.nextInt(-50, 51) * if (random.nextInt(4) == 0) 0 else 1000 }
            val file = wav(samples)
            TestWav.open(file) { ch ->
                val l = WavHeader.parse(ch)
                repeat(20) {
                    val target = random.nextLong(0, n + 1L)
                    val radius = random.nextInt(0, 200)
                    assertEquals(refZero(samples, target, radius), WavProcess.nearestZeroCrossing(ch, l, target, radius))
                }
            }
            file.delete()
        }
    }

    // ---- detail peaks ------------------------------------------------------------

    private fun refRangePeaks(samples: IntArray, s: Int, e: Int, points: Int): FloatArray {
        val frames = (e - s).toLong()
        val n = points.coerceIn(2, 20_000)
        val peaks = IntArray(n)
        for (f in 0 until frames) {
            val bin = ((f * n) / frames).toInt()
            peaks[bin] = max(peaks[bin], abs(samples[(s + f).toInt()]))
        }
        if (frames < n) for (bin in 0 until n) if (peaks[bin] == 0) {
            val f = (bin.toLong() * frames / n).coerceAtMost(frames - 1)
            peaks[bin] = peaks[((f * n) / frames).toInt()]
        }
        return FloatArray(n) { Pcm24.peakToLinear(peaks[it]) }
    }

    @Test
    fun rangePeaksAreExactIncludingDeepZoom() {
        val random = Random(84)
        val samples = TestWav.randomSamples(random, 300_000)
        val file = wav(samples)
        TestWav.open(file) { ch ->
            val l = WavHeader.parse(ch)
            for ((s, e, p) in listOf(Triple(0, 300_000, 1000), Triple(1000, 1240, 3000), Triple(5, 9, 100), Triple(100_000, 270_000, 6000))) {
                assertArrayEquals("$s..$e/$p", refRangePeaks(samples, s, e, p), WavProcess.peaksRange(ch, l, s.toLong(), e.toLong(), p), 0f)
            }
        }
    }

    @Test
    fun deepZoomPeaksHaveNoHoles() {
        val samples = IntArray(1_000) { 4_000_000 }
        TestWav.open(wav(samples)) { ch ->
            val p = WavProcess.peaksRange(ch, WavHeader.parse(ch), 10, 20, 2000)
            assertTrue(p.all { it > 0.47f })
        }
    }

    @Test
    fun editColumnsWithDetailDomain() {
        // Detail peaks for timeline [0.5, 0.6]; view shows [0.52, 0.54].
        val detail = FloatArray(1001) { it / 1000f }
        val active = FloatArray(200)
        val inactive = FloatArray(200)
        WaveformMath.editColumns(detail, 0.52f, 0.54f, 0f, 1f, 200f, active, inactive, 0.5f, 0.6f)
        // Visible detail indices 200..400 -> values 0.2..0.4 spread over 200 px.
        val values = active.filter { it >= 0f }
        assertTrue(values.isNotEmpty())
        assertEquals(0.2f, values.first(), 0.01f)
        assertEquals(0.4f, values.last(), 0.01f)
        assertTrue(inactive.all { it == -1f })
    }
}
