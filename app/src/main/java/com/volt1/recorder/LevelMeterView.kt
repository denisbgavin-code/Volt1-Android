package com.volt1.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Segment bar (-60..0 dBFS) with peak-hold marker, a latched CLIP box on the
 * right (shows the number of overs, tap the meter to clear) and a dB scale.
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val densityScale = resources.displayMetrics.density

    private val offPaint = Paint().apply { color = Color.rgb(40, 45, 53) }
    private val onPaint = Paint()
    private val holdPaint = Paint().apply { color = Color.rgb(244, 247, 250) }
    private val clipOffPaint = Paint().apply { color = Color.rgb(60, 36, 40) }
    private val clipOnPaint = Paint().apply { color = COLOR_CLIP }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(113, 124, 140)
        textSize = 9f * densityScale
    }
    private val clipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 8f * densityScale
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val scaleLabels = LevelMeter.SCALE_DB.map { if (it == 0) "0" else it.toString() }

    private var barDb = Pcm24.SILENCE_DB
    private var holdDb = Pcm24.SILENCE_DB
    private var clipEvents = 0

    fun setLevels(bar: Float, hold: Float, clips: Int) {
        val nextBar = bar.coerceIn(Pcm24.SILENCE_DB, 0f)
        val nextHold = hold.coerceIn(Pcm24.SILENCE_DB, 0f)
        if (
            abs(nextBar - barDb) >= 0.15f ||
            abs(nextHold - holdDb) >= 0.15f ||
            clips != clipEvents
        ) {
            barDb = nextBar
            holdDb = nextHold
            clipEvents = clips
            invalidate()
        }
    }

    /** Kept for callers that only have a single level. */
    fun setLevelDb(value: Float) = setLevels(value, value, clipEvents)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val barHeight = density(BAR_HEIGHT_DP).coerceAtMost(height.toFloat())
        val clipWidth = density(CLIP_WIDTH_DP)
        val clipGap = density(4f)
        val barWidth = width - clipWidth - clipGap
        val gap = density(2.5f)
        val radius = density(1.5f)
        val segmentWidth = (barWidth - gap * (SEGMENTS - 1)) / SEGMENTS

        val active = (LevelMeter.dbToFraction(barDb) * SEGMENTS).roundToInt()
        val holdSegment =
            if (holdDb > LevelMeter.FLOOR_DB) {
                (LevelMeter.dbToFraction(holdDb) * SEGMENTS).roundToInt().coerceIn(1, SEGMENTS) - 1
            } else {
                -1
            }

        for (i in 0 until SEGMENTS) {
            val left = i * (segmentWidth + gap)
            val paint = when {
                i == holdSegment && i >= active -> holdPaint
                i < active -> onPaint.apply { color = segmentColor(i) }
                else -> offPaint
            }
            canvas.drawRoundRect(left, 0f, left + segmentWidth, barHeight, radius, radius, paint)
        }

        val clipLeft = width - clipWidth
        canvas.drawRoundRect(
            clipLeft, 0f, width.toFloat(), barHeight, radius, radius,
            if (clipEvents > 0) clipOnPaint else clipOffPaint
        )
        if (clipEvents > 0) {
            val label = if (clipEvents > 99) "99+" else clipEvents.toString()
            val baseline = barHeight / 2f - (clipTextPaint.ascent() + clipTextPaint.descent()) / 2f
            canvas.drawText(label, clipLeft + clipWidth / 2f, baseline, clipTextPaint)
        }

        if (height >= barHeight + density(9f)) {
            val baseline = barHeight + density(10f)
            LevelMeter.SCALE_DB.forEachIndexed { index, db ->
                val label = scaleLabels[index]
                val x = LevelMeter.dbToFraction(db.toFloat()) * barWidth
                val textWidth = scalePaint.measureText(label)
                val drawX = (x - textWidth / 2f).coerceIn(0f, barWidth - textWidth)
                canvas.drawText(label, drawX, baseline, scalePaint)
            }
            val clipLabel = "CLIP"
            canvas.drawText(
                clipLabel,
                clipLeft + (clipWidth - scalePaint.measureText(clipLabel)) / 2f,
                baseline,
                scalePaint
            )
        }
    }

    private fun segmentColor(index: Int): Int {
        // Segment i covers -60 + 2i .. -60 + 2(i+1) dB.
        val topDb = LevelMeter.FLOOR_DB + (index + 1) * (-LevelMeter.FLOOR_DB / SEGMENTS)
        return when {
            topDb > -3f -> COLOR_CLIP
            topDb > -12f -> COLOR_HOT
            else -> COLOR_OK
        }
    }

    private fun density(dp: Float): Float = dp * densityScale

    private companion object {
        const val SEGMENTS = 30
        const val BAR_HEIGHT_DP = 10f
        const val CLIP_WIDTH_DP = 26f
        val COLOR_CLIP = Color.rgb(255, 83, 83)
        val COLOR_HOT = Color.rgb(238, 190, 72)
        val COLOR_OK = Color.rgb(92, 220, 165)
    }
}
