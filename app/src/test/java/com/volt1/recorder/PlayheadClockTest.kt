package com.volt1.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlayheadClockTest {

    private val ms = 1_000_000L

    @Test
    fun advancesSmoothlyBetweenSparseReports() {
        val clock = PlayheadClock(48_000)
        clock.start(1_000, 1_000_000, 0)
        // 60 fps frames with no reports: moves 800 frames per 16.67 ms.
        var previous = 1_000L
        for (frame in 1..10) {
            val now = frame * 16_666_667L
            val value = clock.estimate(now)
            assertEquals(1_000L + now * 48_000L / 1_000_000_000L, value)
            assertTrue(value > previous)
            previous = value
        }
    }

    @Test
    fun extrapolationIsCappedSoAStallDoesNotRunAway() {
        val clock = PlayheadClock(48_000, maxExtrapolationNanos = 250 * ms)
        clock.start(0, 10_000_000, 0)
        assertEquals(12_000L, clock.estimate(250 * ms))
        assertEquals(12_000L, clock.estimate(5_000 * ms))   // audio stalled
        clock.onReport(12_500, 5_000 * ms)
        assertEquals(12_500L + 4_800, clock.estimate(5_100 * ms))
    }

    @Test
    fun neverMovesBackwardsOnALateReport() {
        val clock = PlayheadClock(48_000)
        clock.start(0, 10_000_000, 0)
        val ahead = clock.estimate(200 * ms)                // 9 600
        clock.onReport(5_000, 200 * ms)                     // report behind the estimate
        assertEquals(ahead, clock.estimate(200 * ms))       // holds instead of jumping back
        assertEquals(ahead, clock.estimate(250 * ms))       // 5 000 + 2 400 < 9 600
        assertEquals(5_000L + 48 * 120, clock.estimate(320 * ms))   // catches up, then continues
    }

    @Test
    fun clampsToRangeEndAndStops() {
        val clock = PlayheadClock(48_000)
        clock.start(0, 1_000, 0)
        assertEquals(1_000L, clock.estimate(1_000 * ms))
        clock.onReport(5_000, 1_000 * ms)
        assertEquals(1_000L, clock.estimate(1_100 * ms))
        clock.stop()
        clock.onReport(0, 1_200 * ms)                        // ignored after stop
        assertEquals(1_000L, clock.estimate(2_000 * ms))
    }

    @Test
    fun randomizedReportsKeepOutputMonotonicAndBounded() {
        val random = Random(90)
        repeat(200) {
            val start = random.nextLong(0, 1_000_000)
            val end = start + random.nextLong(1, 2_000_000)
            val clock = PlayheadClock(48_000)
            clock.start(start, end, 0)
            var now = 0L
            var last = start
            repeat(500) {
                now += random.nextLong(0, 40 * ms)
                if (random.nextInt(4) == 0) {
                    clock.onReport(random.nextLong(start, end + 10_000), now)
                }
                val value = clock.estimate(now)
                assertTrue(value >= last)
                assertTrue(value in start..end)
                last = value
            }
        }
    }

    @Test
    fun presentedFrameFromTimestamp() {
        assertEquals(1_000L, PlaybackMath.presentedFrame(1_000, 0, 48_000))
        assertEquals(1_480L, PlaybackMath.presentedFrame(1_000, 10 * ms, 48_000))
        assertEquals(1_000L, PlaybackMath.presentedFrame(1_000, -5 * ms, 48_000))
    }
}
