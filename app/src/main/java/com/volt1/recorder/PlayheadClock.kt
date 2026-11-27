package com.volt1.recorder

/**
 * Android-free smooth playhead.
 *
 * The player reports the presented frame only every few tens to hundreds
 * of milliseconds (after each blocking write / poll). Drawing those
 * reports directly makes the playhead jump. Between reports the position
 * is extrapolated at the sample rate on every display frame; the output
 * never moves backwards, never passes the end of the range and never runs
 * more than [maxExtrapolationNanos] ahead of the last report (so it stops
 * near the real position if the audio stalls).
 */
class PlayheadClock(
    private val sampleRate: Int,
    private val maxExtrapolationNanos: Long = 500_000_000L,
    /** Share of a report's error corrected at once; the rest by drift. */
    private val correctionGain: Double = 0.25,
    /** Errors above this (frames) are not smoothed but taken as-is. */
    private val snapFrames: Long = sampleRate / 10L
) {
    private var anchorFrame = 0L
    private var anchorNanos = 0L
    private var endFrame = 0L
    private var lastOutput = 0L

    var running = false
        private set

    fun start(startFrame: Long, endFrameExclusive: Long, nowNanos: Long) {
        anchorFrame = startFrame
        anchorNanos = nowNanos
        endFrame = endFrameExclusive
        lastOutput = startFrame
        running = true
    }

    fun stop() {
        running = false
    }

    /**
     * A measured presented frame at [nowNanos]. Small errors against the
     * running prediction are corrected gradually (no visible jump); large
     * ones (start, seek, stall) are taken directly.
     */
    fun onReport(frame: Long, nowNanos: Long) {
        if (!running) return
        val measured = frame.coerceAtMost(endFrame)
        val predicted = predict(nowNanos)
        val error = measured - predicted
        anchorFrame =
            if (kotlin.math.abs(error) > snapFrames) measured
            else predicted + (error * correctionGain).toLong()
        anchorNanos = nowNanos
    }

    private fun predict(nowNanos: Long): Long {
        val elapsed = (nowNanos - anchorNanos).coerceIn(0L, maxExtrapolationNanos)
        return anchorFrame + elapsed * sampleRate / 1_000_000_000L
    }

    /** Smoothed frame for drawing at [nowNanos]. */
    fun estimate(nowNanos: Long): Long {
        if (!running) return lastOutput
        val value = maxOf(lastOutput, predict(nowNanos)).coerceAtMost(endFrame)
        lastOutput = value
        return value
    }
}
