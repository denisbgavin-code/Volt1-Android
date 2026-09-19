package com.volt1.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class LiveWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gridPaint = Paint().apply {
        color = Color.rgb(40, 45, 53)
        strokeWidth = density(1f)
    }

    private val centerPaint = Paint().apply {
        color = Color.rgb(69, 77, 89)
        strokeWidth = density(1f)
    }

    private val waveformPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(92, 220, 165)
            strokeWidth = density(1.45f)
            strokeCap = Paint.Cap.ROUND
        }

    private val playheadPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 83, 83)
            strokeWidth = density(2f)
        }

    private val idlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(101, 110, 124)
            strokeWidth = density(1f)
        }

    private var peaks = FloatArray(0)
    private var recording = false

    fun setWaveform(
        values: FloatArray,
        isRecording: Boolean
    ) {
        peaks = values
        recording = isRecording
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) {
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        for (i in 1 until 8) {
            val x = w * i / 8f
            canvas.drawLine(
                x,
                0f,
                x,
                h,
                gridPaint
            )
        }

        for (i in 1 until 4) {
            val y = h * i / 4f
            canvas.drawLine(
                0f,
                y,
                w,
                y,
                gridPaint
            )
        }

        canvas.drawLine(
            0f,
            centerY,
            w,
            centerY,
            centerPaint
        )

        if (peaks.isEmpty()) {
            val segment = density(12f)
            val gap = density(8f)
            var x = 0f

            while (x < w) {
                canvas.drawLine(
                    x,
                    centerY,
                    (x + segment).coerceAtMost(w),
                    centerY,
                    idlePaint
                )
                x += segment + gap
            }
        } else {
            val halfHeight = h * 0.41f
            val step =
                if (peaks.size <= 1) {
                    w
                } else {
                    w / max(1, peaks.size - 1)
                }

            peaks.forEachIndexed { index, value ->
                val x =
                    if (peaks.size == 1) {
                        w
                    } else {
                        index * step
                    }

                val shaped =
                    value.coerceIn(0f, 1f)

                val amplitude =
                    max(
                        density(1f),
                        shaped * halfHeight
                    )

                canvas.drawLine(
                    x,
                    centerY - amplitude,
                    x,
                    centerY + amplitude,
                    waveformPaint
                )
            }
        }

        if (recording) {
            canvas.drawLine(
                w - density(1f),
                0f,
                w - density(1f),
                h,
                playheadPaint
            )
        }
    }

    private fun density(dp: Float): Float =
        dp * resources.displayMetrics.density
}
