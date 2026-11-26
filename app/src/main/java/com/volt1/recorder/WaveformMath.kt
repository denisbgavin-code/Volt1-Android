package com.volt1.recorder

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Android-free reduction of a peak envelope to one value per pixel column.
 *
 * Drawing one antialiased line per peak (8000 in edit mode) heavily overdraws
 * a ~1000 px view; the max per column is exactly the visible envelope and
 * needs at most one line per column per colour.
 */
object WaveformMath {

    const val EMPTY = -1f

    /**
     * Edit mode: peak i sits at timeline fraction i / lastIndex.
     * Fills [active] (inside the selection) and [inactive] per column with
     * the largest peak, or [EMPTY]. Returns the column count used.
     */
    fun editColumns(
        peaks: FloatArray,
        visibleStart: Float,
        visibleEnd: Float,
        selectionStart: Float,
        selectionEnd: Float,
        width: Float,
        active: FloatArray,
        inactive: FloatArray,
        domainStart: Float = 0f,
        domainEnd: Float = 1f
    ): Int {
        val columns = columnCount(width, active, inactive)
        if (columns == 0) return 0

        val lastIndex = peaks.lastIndex
        val span = visibleEnd - visibleStart
        val domain = domainEnd - domainStart
        if (lastIndex <= 0 || span <= 0f || domain <= 0f) return columns

        // Peaks cover timeline [domainStart, domainEnd] (whole file = 0..1,
        // or a zoomed-in detail window).
        val localStart = (visibleStart - domainStart) / domain
        val localEnd = (visibleEnd - domainStart) / domain
        val first = floor(localStart * lastIndex).toInt().coerceIn(0, lastIndex)
        val last = (localEnd * lastIndex).toInt().coerceIn(first, lastIndex)

        for (index in first..last) {
            val fraction =
                if (domainStart == 0f && domainEnd == 1f) index / lastIndex.toFloat()
                else domainStart + index / lastIndex.toFloat() * domain
            val x = (fraction - visibleStart) / span * width
            val column = columnOf(x, width, columns)
            if (column < 0) continue

            val value = peaks[index].coerceIn(0f, 1f)
            val target =
                if (fraction >= selectionStart && fraction <= selectionEnd) active else inactive
            if (value > target[column]) target[column] = value
        }

        return columns
    }

    /** Live mode: n peaks spread evenly over [0, width]; single colour. */
    fun liveColumns(peaks: FloatArray, width: Float, out: FloatArray): Int {
        val columns = columnCount(width, out, null)
        if (columns == 0 || peaks.isEmpty()) return columns

        val step = if (peaks.size <= 1) width else width / max(1, peaks.size - 1)

        for (index in peaks.indices) {
            val x = if (peaks.size == 1) width else index * step
            val column = columnOf(x, width, columns)
            if (column < 0) continue
            val value = peaks[index].coerceIn(0f, 1f)
            if (value > out[column]) out[column] = value
        }

        return columns
    }

    /** Column for x, or -1 when outside [0, width]. x == width maps to the last column. */
    fun columnOf(x: Float, width: Float, columns: Int): Int {
        if (x.isNaN() || x < 0f || x > width) return -1
        return floor(x).toInt().coerceAtMost(columns - 1)
    }

    private fun columnCount(width: Float, a: FloatArray, b: FloatArray?): Int {
        if (width <= 0f) return 0
        val columns = ceil(width).toInt()
        require(a.size >= columns && (b == null || b.size >= columns)) {
            "Column buffer too small"
        }
        a.fill(EMPTY, 0, columns)
        b?.fill(EMPTY, 0, columns)
        return columns
    }
}
