package com.volt1.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val offPaint = Paint().apply {
        color = Color.rgb(40, 45, 53)
    }

    private val onPaint = Paint()

    private var db = -120f

    fun setLevelDb(value: Float) {
        val next = value.coerceIn(-120f, 0f)

        if (kotlin.math.abs(next - db) >= 0.15f) {
            db = next
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) {
            return
        }

        val segmentCount = 30
        val gap = density(2.5f)

        val segmentWidth =
            (
                width -
                    gap * (segmentCount - 1)
                ) / segmentCount

        val normalized =
            ((db + 60f) / 60f)
                .coerceIn(0f, 1f)

        val active =
            (normalized * segmentCount)
                .roundToInt()

        for (i in 0 until segmentCount) {
            val left =
                i * (segmentWidth + gap)

            val right =
                left + segmentWidth

            if (i < active) {
                val position =
                    i / (segmentCount - 1f)

                onPaint.color = when {
                    position >= 0.92f ->
                        Color.rgb(255, 83, 83)

                    position >= 0.78f ->
                        Color.rgb(238, 190, 72)

                    else ->
                        Color.rgb(92, 220, 165)
                }

                canvas.drawRoundRect(
                    left,
                    0f,
                    right,
                    height.toFloat(),
                    density(1.5f),
                    density(1.5f),
                    onPaint
                )
            } else {
                canvas.drawRoundRect(
                    left,
                    0f,
                    right,
                    height.toFloat(),
                    density(1.5f),
                    density(1.5f),
                    offPaint
                )
            }
        }
    }

    private fun density(dp: Float): Float =
        dp * resources.displayMetrics.density
}
