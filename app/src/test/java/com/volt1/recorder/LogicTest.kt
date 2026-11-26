package com.volt1.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

class RecordingWaveformBufferTest {

    @Before
    fun reset() = RecordingWaveformBuffer.reset()

    @Test
    fun appendsAndClamps() {
        RecordingWaveformBuffer.append(0.5f)
        RecordingWaveformBuffer.append(2f)
        RecordingWaveformBuffer.append(-1f)
        assertArrayEquals(floatArrayOf(0.5f, 1f, 0f), RecordingWaveformBuffer.snapshot(), 0f)
    }

    @Test
    fun compressesByPairwiseMaxWhenFull() {
        val reference = ArrayList<Float>()
        val random = Random(30)
        repeat(10_000) {
            val v = random.nextFloat()
            if (reference.size >= 2048) {
                val compressed = ArrayList<Float>()
                var i = 0
                while (i + 1 < reference.size) { compressed += max(reference[i], reference[i + 1]); i += 2 }
                if (i < reference.size) compressed += reference[i]
                reference.clear(); reference.addAll(compressed)
            }
            reference += v
            RecordingWaveformBuffer.append(v)
        }
        val snapshot = RecordingWaveformBuffer.snapshot()
        assertTrue(snapshot.size <= 2048)
        assertArrayEquals(reference.toFloatArray(), snapshot, 0f)
    }

    @Test
    fun compressionNeverLosesTheLoudestPeak() {
        repeat(100_000) { RecordingWaveformBuffer.append(if (it == 31_337) 0.99f else 0.1f) }
        assertEquals(0.99f, RecordingWaveformBuffer.snapshot().maxOrNull()!!, 0f)
    }

    @Test
    fun snapshotIsACopyAndResetClears() {
        RecordingWaveformBuffer.append(0.3f)
        val snap = RecordingWaveformBuffer.snapshot()
        snap[0] = 0.9f
        assertEquals(0.3f, RecordingWaveformBuffer.snapshot()[0], 0f)
        RecordingWaveformBuffer.reset()
        assertEquals(0, RecordingWaveformBuffer.snapshot().size)
    }

    @Test
    fun concurrentAppendAndSnapshotAreSafe() {
        val writer = Thread { repeat(200_000) { RecordingWaveformBuffer.append((it % 100) / 100f) } }
        writer.start()
        while (writer.isAlive) {
            val s = RecordingWaveformBuffer.snapshot()
            assertTrue(s.size <= 2048)
            assertTrue(s.all { it in 0f..1f })
        }
        writer.join()
    }
}

class WaveformMathTest {

    /** The original per-peak drawing: which x each line was drawn at, with its paint. */
    private data class Line(val x: Float, val value: Float, val active: Boolean)

    private fun originalEditLines(
        peaks: FloatArray, vs: Float, ve: Float, ss: Float, se: Float, width: Float
    ): List<Line> {
        val lastIndex = peaks.lastIndex
        if (lastIndex <= 0) return emptyList()
        val first = floor(vs * lastIndex).toInt().coerceIn(0, lastIndex)
        val last = (ve * lastIndex).toInt().coerceIn(first, lastIndex)
        return (first..last).map { index ->
            val f = index / lastIndex.toFloat()
            val x = (f - vs) / (ve - vs) * width
            Line(x, peaks[index].coerceIn(0f, 1f), f >= ss && f <= se)
        }
    }

    @Test
    fun editColumnsAreTheMaxOfOriginalLinesPerColumn() {
        val random = Random(31)
        repeat(500) {
            val peaks = FloatArray(random.nextInt(2, 9_000)) { random.nextFloat() * 1.2f - 0.1f }
            val width = random.nextInt(1, 2_000) + if (random.nextBoolean()) 0.5f else 0f
            val span = random.nextFloat().coerceAtLeast(1f / 64f)
            val vs = random.nextFloat() * (1f - span)
            val ve = vs + span
            val ss = random.nextFloat()
            val se = (ss + random.nextFloat()).coerceAtMost(1f)

            val columns = ceil(width).toInt()
            val active = FloatArray(columns + 3) { 42f }
            val inactive = FloatArray(columns + 3) { 42f }
            assertEquals(columns, WaveformMath.editColumns(peaks, vs, ve, ss, se, width, active, inactive))

            val expectedActive = FloatArray(columns) { -1f }
            val expectedInactive = FloatArray(columns) { -1f }
            for (line in originalEditLines(peaks, vs, ve, ss, se, width)) {
                if (line.x < 0f || line.x > width) continue
                val c = floor(line.x).toInt().coerceAtMost(columns - 1)
                val target = if (line.active) expectedActive else expectedInactive
                target[c] = max(target[c], line.value)
            }
            assertArrayEquals(expectedActive, active.copyOf(columns), 0f)
            assertArrayEquals(expectedInactive, inactive.copyOf(columns), 0f)
        }
    }

    @Test
    fun fullViewDrawsAtMostTwoLinesPerColumnInsteadOfOnePerPeak() {
        val peaks = FloatArray(8_000) { 0.5f }
        val active = FloatArray(1080)
        val inactive = FloatArray(1080)
        WaveformMath.editColumns(peaks, 0f, 1f, 0.25f, 0.75f, 1080f, active, inactive)
        val lines = active.count { it >= 0f } + inactive.count { it >= 0f }
        assertTrue("lines=$lines", lines <= 1080 + 2)
        // Every column is covered: the envelope has no holes at full view.
        for (c in 0 until 1080) assertTrue(active[c] >= 0f || inactive[c] >= 0f)
    }

    @Test
    fun liveColumnsMatchOriginalSpacing() {
        val random = Random(32)
        repeat(300) {
            val peaks = FloatArray(random.nextInt(0, 2_049)) { random.nextFloat() }
            val width = random.nextInt(1, 1_500).toFloat()
            val columns = ceil(width).toInt()
            val out = FloatArray(columns)
            WaveformMath.liveColumns(peaks, width, out)

            val expected = FloatArray(columns) { -1f }
            val step = if (peaks.size <= 1) width else width / max(1, peaks.size - 1)
            peaks.forEachIndexed { index, v ->
                val x = if (peaks.size == 1) width else index * step
                if (x in 0f..width) {
                    val c = floor(x).toInt().coerceAtMost(columns - 1)
                    expected[c] = max(expected[c], v)
                }
            }
            assertArrayEquals(expected, out, 0f)
        }
    }

    @Test
    fun peaksExactlyOnSelectionEdgesAreActive() {
        // 5 peaks at fractions 0, .25, .5, .75, 1; selection [.25, .75] inclusive.
        val peaks = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val active = FloatArray(100)
        val inactive = FloatArray(100)
        WaveformMath.editColumns(peaks, 0f, 1f, 0.25f, 0.75f, 100f, active, inactive)
        assertEquals(0.2f, active[25], 0f)
        assertEquals(0.3f, active[50], 0f)
        assertEquals(0.4f, active[75], 0f)
        assertEquals(-1f, inactive[25], 0f)
        assertEquals(-1f, inactive[75], 0f)
        assertEquals(0.1f, inactive[0], 0f)
        assertEquals(0.5f, inactive[99], 0f)
    }

    @Test
    fun columnEdgesAndDegenerateInput() {
        assertEquals(-1, WaveformMath.columnOf(-0.01f, 100f, 100))
        assertEquals(0, WaveformMath.columnOf(0f, 100f, 100))
        assertEquals(99, WaveformMath.columnOf(99.99f, 100f, 100))
        assertEquals(99, WaveformMath.columnOf(100f, 100f, 100))
        assertEquals(-1, WaveformMath.columnOf(100.01f, 100f, 100))
        assertEquals(-1, WaveformMath.columnOf(Float.NaN, 100f, 100))

        assertEquals(0, WaveformMath.editColumns(FloatArray(10), 0f, 1f, 0f, 1f, 0f, FloatArray(0), FloatArray(0)))
        val a = FloatArray(10); val b = FloatArray(10)
        assertEquals(10, WaveformMath.editColumns(FloatArray(1), 0f, 1f, 0f, 1f, 10f, a, b))
        assertTrue(a.all { it == -1f } && b.all { it == -1f })
        assertThrows(IllegalArgumentException::class.java) {
            WaveformMath.liveColumns(FloatArray(3), 10f, FloatArray(9))
        }
    }
}

class SelectionMathTest {

    @Test
    fun doublePrecisionAddressesEveryFrameOfLongFiles() {
        // 1 hour at 48 kHz: 172.8 M frames, far beyond Float's 2^24.
        val total = 48_000L * 3_600
        val random = Random(33)
        repeat(10_000) {
            val position = random.nextFloat()
            val exact = floor(total.toDouble() * position.toDouble()).toLong().coerceIn(0, total - 1)
            assertEquals(exact, SelectionMath.cursorFrame(total, position))
        }
        // The previous Long * Float arithmetic was off by many frames here.
        val position = 0.7777777f
        val floatResult = floor(total * position).toLong()
        val doubleResult = SelectionMath.cursorFrame(total, position)
        assertTrue(floatResult != doubleResult)
    }

    @Test
    fun selectedFramesIsNonEmptyAndInside() {
        val random = Random(34)
        repeat(20_000) {
            val total = random.nextLong(1, 500_000_000)
            val a = random.nextFloat() * 1.2f - 0.1f
            val b = random.nextFloat() * 1.2f - 0.1f
            val (s, e) = SelectionMath.selectedFrames(total, a, b)
            assertTrue(s in 0 until total)
            assertTrue(e in (s + 1)..total)
        }
        assertEquals(0L to 48_000L, SelectionMath.selectedFrames(48_000, 0f, 1f))
        assertEquals(12_000L to 36_000L, SelectionMath.selectedFrames(48_000, 0.25f, 0.75f))
        assertEquals(0L, SelectionMath.cursorFrame(0, 0.5f))
        assertEquals(99L, SelectionMath.cursorFrame(100, 1f))
        assertThrows(IllegalArgumentException::class.java) { SelectionMath.selectedFrames(0, 0f, 1f) }
    }

    @Test
    fun timeFormatting() {
        assertEquals("00:00:00.0", TimeFormat.duration(0, Locale.US))
        assertEquals("00:00:00.0", TimeFormat.duration(-50, Locale.US))
        assertEquals("00:00:01.2", TimeFormat.duration(1_299, Locale.US))
        assertEquals("01:01:01.5", TimeFormat.duration(3_661_500, Locale.US))
        assertEquals("100:00:00.0", TimeFormat.duration(360_000_000, Locale.US))
        assertEquals(" -12.3 dBFS", TimeFormat.peakDb(-12.34f, Locale.US))
        assertEquals("-120.0 dBFS", TimeFormat.peakDb(-120f, Locale.US))
        assertEquals(" -12,3 dBFS", TimeFormat.peakDb(-12.34f, Locale.forLanguageTag("ru")))
    }
}

class PlaybackMathTest {

    @Test
    fun alignUp() {
        assertEquals(0, PlaybackMath.alignUp(0, 3))
        assertEquals(3, PlaybackMath.alignUp(1, 3))
        assertEquals(49_152, PlaybackMath.alignUp(49_152, 3))
        assertEquals(49_155, PlaybackMath.alignUp(49_153, 3))
        assertThrows(IllegalArgumentException::class.java) { PlaybackMath.alignUp(5, 0) }
    }

    @Test
    fun framesToMsRoundsUp() {
        assertEquals(0L, PlaybackMath.framesToMs(0, 48_000))
        assertEquals(1L, PlaybackMath.framesToMs(1, 48_000))
        assertEquals(1_000L, PlaybackMath.framesToMs(48_000, 48_000))
        assertEquals(1_001L, PlaybackMath.framesToMs(48_001, 48_000))
        assertEquals(0L, PlaybackMath.framesToMs(100, 0))
    }

    @Test
    fun progressIsClampedAndAbsolute() {
        assertEquals(0.5f, PlaybackMath.progress(500, 0, 100, 1_000), 0f)
        assertEquals(0.55f, PlaybackMath.progress(500, 50, 100, 1_000), 1e-6f)
        assertEquals(0.6f, PlaybackMath.progress(500, 5_000, 100, 1_000), 1e-6f)
        assertEquals(0.5f, PlaybackMath.progress(500, -5, 100, 1_000), 0f)
        assertEquals(0f, PlaybackMath.progress(0, 0, 0, 0), 0f)
        assertEquals(1f, PlaybackMath.progress(999, 1, 1, 1_000), 0f)
    }
}

class Volt1DeviceTest {

    @Test
    fun recognisesVolt1Names() {
        for (name in listOf("Volt 1", "UA Volt 1", "Volt1", "VOLT 1", "Universal Audio Volt 1 USB", "volt  1")) {
            assertTrue(name, Volt1Device.isVolt1Name(name))
        }
        for (name in listOf("Volt 2", "Volt 176", "Volt 276", "Revolt 1", "Volt", "", null, "USB Audio")) {
            assertFalse("$name", Volt1Device.isVolt1Name(name))
        }
    }

    @Test
    fun sampleRateSupport() {
        assertTrue(Volt1Device.supports48k(intArrayOf()))
        assertTrue(Volt1Device.supports48k(intArrayOf(44_100, 48_000)))
        assertFalse(Volt1Device.supports48k(intArrayOf(44_100, 96_000)))
    }
}
