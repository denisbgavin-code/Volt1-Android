package com.volt1.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val waveformPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 45, 45)
            strokeWidth = density(1.2f)
        }

    private val selectionPaint = Paint().apply {
        color = Color.argb(32, 176, 0, 32)
        style = Paint.Style.FILL
    }

    private val handlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(176, 0, 32)
            strokeWidth = density(3f)
        }

    private val centerPaint = Paint().apply {
        color = Color.rgb(215, 215, 215)
        strokeWidth = density(1f)
    }

    private var peaks = FloatArray(0)
    private var startFraction = 0f
    private var endFraction = 1f
    private var activeHandle = Handle.NONE

    var onSelectionChanged: ((Float, Float) -> Unit)? = null

    fun setWaveform(values: FloatArray) {
        peaks = values.copyOf()
        invalidate()
    }

    fun setSelection(start: Float, end: Float) {
        startFraction = start.coerceIn(0f, 1f)
        endFraction = end.coerceIn(startFraction, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            backgroundPaint
        )

        val centerY = height / 2f

        canvas.drawLine(
            0f,
            centerY,
            width.toFloat(),
            centerY,
            centerPaint
        )

        if (
            peaks.isNotEmpty() &&
            width > 0 &&
            height > 0
        ) {
            val halfHeight = height * 0.42f
            val step =
                width.toFloat() / peaks.size

            peaks.forEachIndexed { index, peak ->
                val x = (index + 0.5f) * step
                val amplitude =
                    peak.coerceIn(0f, 1f) *
                        halfHeight

                canvas.drawLine(
                    x,
                    centerY - amplitude,
                    x,
                    centerY + amplitude,
                    waveformPaint
                )
            }
        }

        val startX = startFraction * width
        val endX = endFraction * width

        canvas.drawRect(
            startX,
            0f,
            endX,
            height.toFloat(),
            selectionPaint
        )

        canvas.drawLine(
            startX,
            0f,
            startX,
            height.toFloat(),
            handlePaint
        )

        canvas.drawLine(
            endX,
            0f,
            endX,
            height.toFloat(),
            handlePaint
        )
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {
        if (!isEnabled || width <= 0) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(
                    true
                )

                val x =
                    event.x.coerceIn(
                        0f,
                        width.toFloat()
                    )

                val startX =
                    startFraction * width
                val endX =
                    endFraction * width

                activeHandle = if (
                    abs(x - startX) <=
                    abs(x - endX)
                ) {
                    Handle.START
                } else {
                    Handle.END
                }

                updateHandle(x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateHandle(
                    event.x.coerceIn(
                        0f,
                        width.toFloat()
                    )
                )
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (
                    event.actionMasked ==
                    MotionEvent.ACTION_UP
                ) {
                    updateHandle(
                        event.x.coerceIn(
                            0f,
                            width.toFloat()
                        )
                    )
                    performClick()
                }

                activeHandle = Handle.NONE

                parent?.requestDisallowInterceptTouchEvent(
                    false
                )

                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateHandle(x: Float) {
        val fraction =
            (x / max(1, width))
                .coerceIn(0f, 1f)

        val minGap = 0.001f

        when (activeHandle) {
            Handle.START -> {
                startFraction = min(
                    fraction,
                    max(
                        0f,
                        endFraction - minGap
                    )
                )
            }

            Handle.END -> {
                endFraction = max(
                    fraction,
                    min(
                        1f,
                        startFraction + minGap
                    )
                )
            }

            Handle.NONE -> return
        }

        invalidate()

        onSelectionChanged?.invoke(
            startFraction,
            endFraction
        )
    }

    private fun density(dp: Float): Float =
        dp * resources.displayMetrics.density

    private enum class Handle {
        NONE,
        START,
        END
    }
}
