package com.volt1.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.random.Random

class LevelMeterTest {

    @Test
    fun clipLevelIsMinusPointOneDbfs() {
        val db = 20 * log10(LevelMeter.CLIP_LEVEL / 8_388_607.0)
        assertEquals(-0.1, db, 1e-5)
        assertEquals((8_388_607 * Math.pow(10.0, -0.1 / 20)).roundToInt(), LevelMeter.CLIP_LEVEL)
    }

    private fun block(vararg q23: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(q23.size * 4).order(ByteOrder.nativeOrder())
        q23.forEach { b.putInt(it shl 8) }
        b.clear()
        return b
    }

    @Test
    fun conversionCountsClippedSamplesOnBothPolarities() {
        val out = ByteArray(30)
        val r = Pcm24.convertChannel0Pcm32ToPcm24(
            block(0, LevelMeter.CLIP_LEVEL - 1, LevelMeter.CLIP_LEVEL, -LevelMeter.CLIP_LEVEL,
                Pcm24.MAX_VALUE, Pcm24.MIN_VALUE, 5, -LevelMeter.CLIP_LEVEL + 1),
            32, 4, out
        )
        assertEquals(4, r.clippedSamples)

        val quiet = Pcm24.convertChannel0Pcm32ToPcm24(block(1, -1, 1000), 12, 4, out, r)
        assertEquals("result object is reset", 0, quiet.clippedSamples)
    }

    @Test
    fun dbToFraction() {
        assertEquals(0f, LevelMeter.dbToFraction(-120f), 0f)
        assertEquals(0f, LevelMeter.dbToFraction(-60f), 0f)
        assertEquals(0.5f, LevelMeter.dbToFraction(-30f), 1e-6f)
        assertEquals(0.9f, LevelMeter.dbToFraction(-6f), 1e-6f)
        assertEquals(1f, LevelMeter.dbToFraction(0f), 0f)
        assertEquals(1f, LevelMeter.dbToFraction(3f), 0f)
        LevelMeter.SCALE_DB.forEach { assertTrue(it in -60..0) }
    }

    @Test
    fun accumulatorNeverLosesAPeakBetweenDrains() {
        val acc = LevelMeter.Accumulator()
        acc.onBlock(0.2f, 0)
        acc.onBlock(0.9f, 0)
        acc.onBlock(0.1f, 0)
        assertEquals(0.9f, acc.drainPeak(), 0f)
        assertEquals(0f, acc.drainPeak(), 0f)
        acc.onBlock(0.3f, 0)
        assertEquals(0.3f, acc.drainPeak(), 0f)
        assertEquals(0.9f, acc.sessionPeak(), 0f)
        acc.onBlock(5f, 0)
        assertEquals(1f, acc.drainPeak(), 0f)
    }

    @Test
    fun consecutiveClippedBlocksAreOneEvent() {
        val acc = LevelMeter.Accumulator()
        listOf(0, 3, 7, 1, 0, 0, 2, 0, 9).forEach { acc.onBlock(0.5f, it) }
        // runs: [3,7,1] [2] [9]
        assertEquals(3, acc.clipEvents())
        acc.clearHold()
        assertEquals(0, acc.clipEvents())
        assertEquals(0f, acc.sessionPeak(), 0f)
        acc.onBlock(0.5f, 4) // still the same run as the last clipped block
        assertEquals(0, acc.clipEvents())
        acc.onBlock(0.5f, 0); acc.onBlock(0.5f, 1)
        assertEquals(1, acc.clipEvents())
        acc.reset()
        assertEquals(0, acc.clipEvents())
        acc.onBlock(0.5f, 1)
        assertEquals(1, acc.clipEvents())
    }

    @Test
    fun accumulatorIsThreadSafe() {
        val acc = LevelMeter.Accumulator()
        val writer = Thread { repeat(200_000) { acc.onBlock(if (it == 123_456) 0.99f else 0.5f, it % 3) } }
        writer.start()
        var max = 0f
        while (writer.isAlive) max = maxOf(max, acc.drainPeak())
        writer.join()
        max = maxOf(max, acc.drainPeak())
        assertEquals(0.99f, max, 0f)
        assertEquals(0.99f, acc.sessionPeak(), 0f)
    }

    @Test
    fun ballisticsAttackInstantReleaseLinear() {
        val b = LevelMeter.Ballistics(releaseDbPerSecond = 20f, holdMs = 1_000, holdFallDbPerSecond = 10f)
        b.update(0, -6f)
        assertEquals(-6f, b.barDb, 0f)
        assertEquals(-6f, b.holdDb, 0f)
        b.update(500, -60f)
        assertEquals(-16f, b.barDb, 1e-4f)   // 20 dB/s * 0.5 s
        assertEquals(-6f, b.holdDb, 0f)      // still holding
        b.update(1_000, -60f)
        assertEquals(-26f, b.barDb, 1e-4f)
        assertEquals(-6f, b.holdDb, 0f)      // hold ends after 1000 ms (strictly later)
        b.update(1_500, -60f)
        assertEquals(-11f, b.holdDb, 1e-4f)  // 10 dB/s * 0.5 s
        b.update(1_600, -3f)                 // new peak: instant
        assertEquals(-3f, b.barDb, 0f)
        assertEquals(-3f, b.holdDb, 0f)
    }

    @Test
    fun ballisticsNeverFallBelowInputAndClampRange() {
        val random = Random(60)
        val b = LevelMeter.Ballistics()
        var t = 0L
        repeat(10_000) {
            t += random.nextLong(0, 200)
            val input = random.nextFloat() * 130f - 125f
            b.update(t, input)
            val clamped = input.coerceIn(-120f, 0f)
            assertTrue(b.barDb >= clamped - 1e-4f)
            assertTrue(b.holdDb >= b.barDb - 1e-4f || b.holdDb >= clamped - 1e-4f)
            assertTrue(b.barDb in -120f..0f && b.holdDb in -120f..0f)
        }
        b.reset()
        assertEquals(-120f, b.barDb, 0f)
        assertEquals(-120f, b.holdDb, 0f)
    }

    @Test
    fun ballisticsFirstUpdateAndClockGoingBackwards() {
        val b = LevelMeter.Ballistics()
        b.update(1_000_000, -20f)
        assertEquals(-20f, b.barDb, 0f)
        b.update(999_000, -60f) // dt clamps to 0 -> no release
        assertEquals(-20f, b.barDb, 0f)
    }

    @Test
    fun meterTexts() {
        assertEquals("-12.3 · max  -3.1", TimeFormat.meter(-12.34f, -3.1f, Locale.US))
        assertEquals("-99.9 · max -99.9", TimeFormat.meter(-120f, -120f, Locale.US))
        assertEquals("max  -0.1 dBFS", TimeFormat.maxPeak(-0.1f, Locale.US))
        assertEquals("-12,3 · max  -3,1", TimeFormat.meter(-12.34f, -3.1f, Locale.forLanguageTag("ru")))
    }
}
