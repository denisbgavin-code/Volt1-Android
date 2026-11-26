package com.volt1.recorder

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Android-free mapping from UI timeline fractions to sample frames.
 *
 * Computed in Double: Long * Float is evaluated in Float, whose 24-bit
 * mantissa cannot address every frame once a file exceeds 2^24 frames
 * (~5.8 min at 48 kHz) and would shift edit points by whole frames.
 */
object SelectionMath {

    fun cursorFrame(totalFrames: Long, position: Float): Long {
        if (totalFrames <= 0L) return 0L
        return floor(totalFrames.toDouble() * position.toDouble())
            .toLong()
            .coerceIn(0L, totalFrames - 1L)
    }

    /** Non-empty [start, end) covering the selection fractions. */
    fun selectedFrames(
        totalFrames: Long,
        selectionStart: Float,
        selectionEnd: Float
    ): Pair<Long, Long> {
        require(totalFrames > 0L) { "Пустой WAV" }

        val start = floor(totalFrames.toDouble() * selectionStart.toDouble())
            .toLong()
            .coerceIn(0L, totalFrames - 1L)

        val end = ceil(totalFrames.toDouble() * selectionEnd.toDouble())
            .toLong()
            .coerceIn(start + 1L, totalFrames)

        return start to end
    }
}

object TimeFormat {

    /** HH:MM:SS.t */
    fun duration(elapsedMs: Long, locale: Locale = Locale.getDefault()): String {
        val safe = elapsedMs.coerceAtLeast(0L)
        val totalSeconds = safe / 1000L
        return String.format(
            locale,
            "%02d:%02d:%02d.%01d",
            totalSeconds / 3600L,
            (totalSeconds % 3600L) / 60L,
            totalSeconds % 60L,
            (safe % 1000L) / 100L
        )
    }

    /** Held peak and take maximum while recording, e.g. "-12.3 · max -3.1". */
    fun meter(heldDb: Float, maxDb: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%5.1f · max %5.1f", heldDb.coerceAtLeast(-99.9f), maxDb.coerceAtLeast(-99.9f))

    /** Take maximum after recording, e.g. "max -3.1 dBFS". */
    fun maxPeak(maxDb: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "max %5.1f dBFS", maxDb.coerceAtLeast(-99.9f))

    /** Uses the device locale, as before (e.g. "-12,3 dBFS" in ru). */
    fun peakDb(db: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%6.1f dBFS", db)
}
