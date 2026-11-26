package com.volt1.recorder

import kotlin.math.max

/** Android-free level metering: shared accumulator + display ballistics. */
object LevelMeter {

    const val FLOOR_DB = -60f

    /** -0.1 dBFS: a sample at or above this counts as an over/clip. */
    const val CLIP_LEVEL = 8_292_583 // round(8_388_607 * 10^(-0.1/20))

    /** Scale labels drawn under the bar. */
    val SCALE_DB = intArrayOf(-48, -36, -24, -18, -12, -6, 0)

    /** Linear dB position 0..1 on a FLOOR_DB..0 scale. */
    fun dbToFraction(db: Float): Float =
        ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

    /**
     * Written by the capture thread per block, drained by the UI per frame.
     * Draining returns the loudest block since the previous drain, so no
     * peak is lost however the two rates relate.
     */
    class Accumulator {
        private var pendingPeak = 0f
        private var sessionPeak = 0f
        private var clipEvents = 0
        private var lastBlockClipped = false

        @Synchronized
        fun reset() {
            pendingPeak = 0f
            sessionPeak = 0f
            clipEvents = 0
            lastBlockClipped = false
        }

        /** Clears the take maximum and the clip latch, keeps metering. */
        @Synchronized
        fun clearHold() {
            sessionPeak = 0f
            clipEvents = 0
        }

        /**
         * A run of consecutive clipped blocks counts as one event, so one
         * long over is "1", not ten per second.
         */
        @Synchronized
        fun onBlock(peakLinear: Float, clippedSamples: Int) {
            val peak = peakLinear.coerceIn(0f, 1f)
            pendingPeak = max(pendingPeak, peak)
            sessionPeak = max(sessionPeak, peak)
            val clipped = clippedSamples > 0
            if (clipped && !lastBlockClipped) clipEvents++
            lastBlockClipped = clipped
        }

        /** Loudest peak (linear) since the last call; then resets it. */
        @Synchronized
        fun drainPeak(): Float {
            val value = pendingPeak
            pendingPeak = 0f
            return value
        }

        @Synchronized
        fun sessionPeak(): Float = sessionPeak

        @Synchronized
        fun clipEvents(): Int = clipEvents
    }

    /**
     * Peak-programme style display: instant attack, linear release of the
     * bar, and a peak-hold marker that holds then falls.
     */
    class Ballistics(
        private val releaseDbPerSecond: Float = 24f,
        private val holdMs: Long = 1_500L,
        private val holdFallDbPerSecond: Float = 12f
    ) {
        var barDb: Float = Pcm24.SILENCE_DB
            private set
        var holdDb: Float = Pcm24.SILENCE_DB
            private set

        private var lastMs = Long.MIN_VALUE
        private var holdUntilMs = Long.MIN_VALUE

        fun reset() {
            barDb = Pcm24.SILENCE_DB
            holdDb = Pcm24.SILENCE_DB
            lastMs = Long.MIN_VALUE
            holdUntilMs = Long.MIN_VALUE
        }

        fun update(nowMs: Long, inputDb: Float) {
            val input = inputDb.coerceIn(Pcm24.SILENCE_DB, 0f)
            val dt = if (lastMs == Long.MIN_VALUE) 0f else ((nowMs - lastMs).coerceAtLeast(0L) / 1000f)
            lastMs = nowMs

            barDb = if (input >= barDb) input else max(input, barDb - releaseDbPerSecond * dt)

            if (input >= holdDb) {
                holdDb = input
                holdUntilMs = nowMs + holdMs
            } else if (nowMs > holdUntilMs) {
                holdDb = max(input, holdDb - holdFallDbPerSecond * dt)
            }
        }
    }
}
