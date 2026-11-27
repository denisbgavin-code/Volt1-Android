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
    private val maxExtrapolationNanos: Long = 250_000_000L
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

    /** A measured presented frame at [nowNanos]. */
    fun onReport(frame: Long, nowNanos: Long) {
        if (!running) return
        anchorFrame = frame.coerceAtMost(endFrame)
        anchorNanos = nowNanos
    }

    /** Smoothed frame for drawing at [nowNanos]. */
    fun estimate(nowNanos: Long): Long {
        if (!running) return lastOutput
        val elapsed = (nowNanos - anchorNanos).coerceIn(0L, maxExtrapolationNanos)
        val extrapolated = anchorFrame + elapsed * sampleRate / 1_000_000_000L
        val value = maxOf(lastOutput, extrapolated).coerceAtMost(endFrame)
        lastOutput = value
        return value
    }
}
