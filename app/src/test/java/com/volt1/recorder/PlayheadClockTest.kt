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
        clock.onReport(12_500, 5_000 * ms)                  // small error: smoothed
        assertEquals(12_125L + 4_800, clock.estimate(5_100 * ms))
    }

    @Test
    fun smallErrorsAreCorrectedGraduallyLargeOnesSnap() {
        val clock = PlayheadClock(48_000)
        clock.start(0, 10_000_000, 0)
        // Prediction at 100 ms is 4 800; report says 5 200 (+400): 25 % now.
        clock.onReport(5_200, 100 * ms)
        assertEquals(4_900L, clock.estimate(100 * ms))
        // Large error (> 100 ms of audio) is taken as is.
        clock.onReport(20_000, 120 * ms)
        assertEquals(20_000L, clock.estimate(120 * ms))
    }

    @Test
    fun frequentReportsWithJitterGiveEvenSteps() {
        // Reports every ~21 ms with +-1 ms timestamp jitter; drawn at 60 fps.
        val random = Random(91)
        val clock = PlayheadClock(48_000)
        clock.start(0, 100_000_000, 0)
        var nextReport = 21 * ms
        var last = 0L
        var frameTime = 0L
        repeat(600) {
            frameTime += 16_666_667L
            while (nextReport <= frameTime) {
                val truth = nextReport * 48 / 1_000_000
                clock.onReport(truth + random.nextLong(-48, 49), nextReport)
                nextReport += 21 * ms
            }
            val value = clock.estimate(frameTime)
            val step = value - last
            // Ideal step 800 frames per display frame; no stalls, no jumps.
            assertTrue("step $step", step in 650..950)
            last = value
        }
    }

    @Test
    fun neverMovesBackwardsOnALateReport() {
        val clock = PlayheadClock(48_000)
        clock.start(0, 10_000_000, 0)
        val ahead = clock.estimate(200 * ms)                // 9 600
        clock.onReport(2_000, 200 * ms)                     // far behind (> 100 ms): snaps
        assertEquals(ahead, clock.estimate(200 * ms))       // holds instead of jumping back
        assertEquals(ahead, clock.estimate(300 * ms))       // 2 000 + 4 800 < 9 600
        assertEquals(2_000L + 48 * 200, clock.estimate(400 * ms))   // catches up, then continues
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
