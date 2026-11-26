package com.volt1.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class UnifiedWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Mode {
        EMPTY,
        LIVE,
        EDIT
    }

    private enum class Gesture {
        NONE,
        POSSIBLE_TAP,
        START_HANDLE,
        END_HANDLE,
        PAN,
        RANGE_CREATE
    }

    // Declared first: the Paint initializers below use density().
    private val densityScale =
        resources.displayMetrics.density

    private var activeColumns = FloatArray(0)
    private var inactiveColumns = FloatArray(0)
    private var lineBuffer = FloatArray(0)

    private val backgroundPaint =
        Paint().apply {
            color =
                Color.rgb(
                    16,
                    19,
                    24
                )
            style =
                Paint.Style.FILL
        }

    private val gridPaint =
        Paint().apply {
            color =
                Color.rgb(
                    40,
                    45,
                    53
                )
            strokeWidth =
                density(1f)
        }

    private val centerPaint =
        Paint().apply {
            color =
                Color.rgb(
                    67,
                    76,
                    88
                )
            strokeWidth =
                density(1f)
        }

    private val waveformPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    92,
                    220,
                    165
                )
            strokeWidth =
                density(1.35f)
            strokeCap =
                Paint.Cap.ROUND
        }

    private val inactiveWaveformPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    131,
                    143,
                    160
                )
            strokeWidth =
                density(1.25f)
            strokeCap =
                Paint.Cap.ROUND
        }

    private val selectionPaint =
        Paint().apply {
            color =
                Color.argb(
                    42,
                    92,
                    220,
                    165
                )
            style =
                Paint.Style.FILL
        }

    private val handlePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    92,
                    220,
                    165
                )
            strokeWidth =
                density(2.2f)
        }

    private val handleFillPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    92,
                    220,
                    165
                )
            style =
                Paint.Style.FILL
        }

    private val liveHeadPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    255,
                    83,
                    83
                )
            strokeWidth =
                density(2f)
        }

    private val playbackHeadPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    238,
                    190,
                    72
                )
            strokeWidth =
                density(2.5f)
        }

    private val playbackHeadFillPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    238,
                    190,
                    72
                )
            style =
                Paint.Style.FILL
        }

    private val rulerPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    113,
                    124,
                    140
                )
            textSize =
                density(9f)
        }

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private val touchSlop =
        ViewConfiguration
            .get(context)
            .scaledTouchSlop
            .toFloat()

    private val longPressTimeout =
        ViewConfiguration
            .getLongPressTimeout()
            .toLong()

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object :
                ScaleGestureDetector
                    .SimpleOnScaleGestureListener() {

                override fun onScaleBegin(
                    detector:
                        ScaleGestureDetector
                ): Boolean {
                    if (
                        mode !=
                        Mode.EDIT
                    ) {
                        return false
                    }

                    cancelLongPressGesture()
                    gesture =
                        Gesture.NONE

                    parent
                        ?.requestDisallowInterceptTouchEvent(
                            true
                        )

                    return true
                }

                override fun onScale(
                    detector:
                        ScaleGestureDetector
                ): Boolean {
                    if (
                        mode !=
                        Mode.EDIT
                    ) {
                        return false
                    }

                    val oldSpan =
                        visibleEnd -
                            visibleStart

                    if (
                        oldSpan <= 0f
                    ) {
                        return false
                    }

                    val focusFraction =
                        xToTimeline(
                            detector.focusX
                        )

                    val focusRatio =
                        (
                            focusFraction -
                                visibleStart
                            ) /
                            oldSpan

                    val newSpan =
                        (
                            oldSpan /
                                detector
                                    .scaleFactor
                            )
                            .coerceIn(
                                minVisibleSpan(),
                                1f
                            )

                    var newStart =
                        focusFraction -
                            focusRatio *
                                newSpan

                    newStart =
                        newStart
                            .coerceIn(
                                0f,
                                1f -
                                    newSpan
                            )

                    visibleStart =
                        newStart

                    visibleEnd =
                        newStart +
                            newSpan

                    viewportChanged()
                    return true
                }

                override fun onScaleEnd(
                    detector:
                        ScaleGestureDetector
                ) {
                    parent
                        ?.requestDisallowInterceptTouchEvent(
                            false
                        )
                }
            }
        )

    private var mode =
        Mode.EMPTY

    private var peaks =
        FloatArray(0)

    private var durationMs =
        0L

    private var selectionStart =
        0f

    private var selectionEnd =
        1f

    private var visibleStart =
        0f

    private var visibleEnd =
        1f

    private var playbackPosition =
        0f

    private var gesture =
        Gesture.NONE

    private var downX =
        0f

    private var lastTouchX =
        0f

    private var rangeAnchor =
        0f

    private var pointerMoved =
        false

    private var longPressArmed =
        false

    var onSelectionChanged:
        ((Float, Float) -> Unit)? =
        null

    /** Called when zoom/pan changed the visible window (timeline fractions). */
    var onViewportChanged:
        ((Float, Float) -> Unit)? =
        null

    /** Frames in the open file; limits zoom to [MIN_VISIBLE_FRAMES]. */
    var totalFrames: Long = 0L

    // High-resolution peaks for a zoomed-in window, loaded on demand.
    private var detailPeaks: FloatArray? = null
    private var detailStart = 0f
    private var detailEnd = 0f

    /** Installs exact peaks for timeline window [start, end]. */
    fun setDetail(start: Float, end: Float, values: FloatArray) {
        detailPeaks = values
        detailStart = start
        detailEnd = end
        invalidate()
    }

    fun clearDetail() {
        detailPeaks = null
    }

    fun visibleWindow(): Pair<Float, Float> = visibleStart to visibleEnd

    private fun minVisibleSpan(): Float =
        if (totalFrames > 0L) {
            (MIN_VISIBLE_FRAMES.toFloat() / totalFrames).coerceIn(1e-7f, 1f)
        } else {
            MIN_VISIBLE_SPAN
        }

    var onPlayheadChanged:
        ((Float) -> Unit)? =
        null

    private val longPressRunnable =
        Runnable {
            if (
                !longPressArmed ||
                gesture !=
                Gesture.POSSIBLE_TAP ||
                mode !=
                Mode.EDIT
            ) {
                return@Runnable
            }

            gesture =
                Gesture.RANGE_CREATE

            rangeAnchor =
                xToTimeline(
                    downX
                )

            selectionStart =
                rangeAnchor

            selectionEnd =
                rangeAnchor

            playbackPosition =
                rangeAnchor

            onPlayheadChanged
                ?.invoke(
                    playbackPosition
                )

            selectionChanged()
        }

    fun showIdle() {
        cancelLongPressGesture()

        mode =
            Mode.EMPTY

        peaks =
            FloatArray(0)

        detailPeaks =
            null

        durationMs =
            0L

        selectionStart =
            0f

        selectionEnd =
            1f

        visibleStart =
            0f

        visibleEnd =
            1f

        playbackPosition =
            0f

        invalidate()
    }

    fun showLive(
        values: FloatArray
    ) {
        cancelLongPressGesture()

        mode =
            Mode.LIVE

        peaks =
            values

        detailPeaks =
            null

        durationMs =
            0L

        visibleStart =
            0f

        visibleEnd =
            1f

        invalidate()
    }

    fun showEditable(
        values: FloatArray,
        trackDurationMs: Long,
        resetViewport: Boolean =
            true
    ) {
        cancelLongPressGesture()

        mode =
            Mode.EDIT

        peaks =
            values

        detailPeaks =
            null

        durationMs =
            trackDurationMs
                .coerceAtLeast(
                    0L
                )

        if (
            resetViewport
        ) {
            selectionStart =
                0f

            selectionEnd =
                1f

            visibleStart =
                0f

            visibleEnd =
                1f

            playbackPosition =
                0f

            onSelectionChanged
                ?.invoke(
                    selectionStart,
                    selectionEnd
                )

            onPlayheadChanged
                ?.invoke(
                    playbackPosition
                )
        }

        invalidate()
    }

    fun resetZoom() {
        visibleStart =
            0f

        visibleEnd =
            1f

        viewportChanged()
    }

    private fun viewportChanged() {
        invalidate()
        onViewportChanged?.invoke(visibleStart, visibleEnd)
    }

    fun setSelection(
        start: Float,
        end: Float
    ) {
        selectionStart =
            start.coerceIn(
                0f,
                1f
            )

        selectionEnd =
            end.coerceIn(
                selectionStart,
                1f
            )

        invalidate()
    }

    fun setPlaybackPosition(
        fraction: Float
    ) {
        playbackPosition =
            fraction.coerceIn(
                0f,
                1f
            )

        if (
            mode ==
            Mode.EDIT
        ) {
            keepPlayheadVisible()
        }

        invalidate()
    }

    fun selectionFractions():
        Pair<Float, Float> =
        selectionStart to
            selectionEnd

    fun playbackFraction():
        Float =
        playbackPosition

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(
            canvas
        )

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        canvas.drawRect(
            0f,
            0f,
            w,
            h,
            backgroundPaint
        )

        val rulerHeight =
            if (
                mode ==
                Mode.EDIT &&
                durationMs >
                0L
            ) {
                density(
                    15f
                )
            } else {
                0f
            }

        val contentTop =
            rulerHeight

        val contentHeight =
            h -
                rulerHeight

        val centerY =
            contentTop +
                contentHeight /
                2f

        drawGrid(
            canvas,
            w,
            h,
            contentTop,
            centerY
        )

        drawWaveform(
            canvas,
            w,
            contentHeight,
            centerY
        )

        if (
            mode ==
            Mode.EDIT
        ) {
            drawSelection(
                canvas,
                w,
                h,
                contentTop
            )

            drawTimeRuler(
                canvas,
                w
            )

            drawPlaybackHead(
                canvas,
                h,
                contentTop
            )
        }

        if (
            mode ==
            Mode.LIVE
        ) {
            canvas.drawLine(
                w -
                    density(1f),
                contentTop,
                w -
                    density(1f),
                h,
                liveHeadPaint
            )
        }
    }

    private fun drawGrid(
        canvas: Canvas,
        width: Float,
        height: Float,
        contentTop: Float,
        centerY: Float
    ) {
        for (
            i in
            1 until 8
        ) {
            val x =
                width *
                    i /
                    8f

            canvas.drawLine(
                x,
                contentTop,
                x,
                height,
                gridPaint
            )
        }

        for (
            i in
            1 until 4
        ) {
            val y =
                contentTop +
                    (
                        height -
                            contentTop
                        ) *
                    i /
                    4f

            canvas.drawLine(
                0f,
                y,
                width,
                y,
                gridPaint
            )
        }

        canvas.drawLine(
            0f,
            centerY,
            width,
            centerY,
            centerPaint
        )
    }

    private fun drawWaveform(
        canvas: Canvas,
        width: Float,
        contentHeight: Float,
        centerY: Float
    ) {
        if (peaks.isEmpty()) return

        val halfHeight = contentHeight * 0.40f
        val minAmplitude = density(0.7f)
        val needed = kotlin.math.ceil(width).toInt()

        if (activeColumns.size < needed) {
            activeColumns = FloatArray(needed)
            inactiveColumns = FloatArray(needed)
            lineBuffer = FloatArray(needed * 4)
        }

        if (mode != Mode.EDIT) {
            val columns = WaveformMath.liveColumns(peaks, width, activeColumns)
            drawColumns(canvas, activeColumns, columns, centerY, halfHeight, minAmplitude, waveformPaint)
            return
        }

        val detail = detailPeaks
        val useDetail =
            detail != null &&
                detail.size > 1 &&
                detailStart <= visibleStart &&
                detailEnd >= visibleEnd

        val columns = WaveformMath.editColumns(
            peaks = if (useDetail) detail!! else peaks,
            visibleStart = visibleStart,
            visibleEnd = visibleEnd,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd,
            width = width,
            active = activeColumns,
            inactive = inactiveColumns,
            domainStart = if (useDetail) detailStart else 0f,
            domainEnd = if (useDetail) detailEnd else 1f
        )

        drawColumns(canvas, inactiveColumns, columns, centerY, halfHeight, minAmplitude, inactiveWaveformPaint)
        drawColumns(canvas, activeColumns, columns, centerY, halfHeight, minAmplitude, waveformPaint)
    }

    /** One batched drawLines() call for all non-empty columns. */
    private fun drawColumns(
        canvas: Canvas,
        values: FloatArray,
        columns: Int,
        centerY: Float,
        halfHeight: Float,
        minAmplitude: Float,
        paint: Paint
    ) {
        var count = 0
        for (column in 0 until columns) {
            val value = values[column]
            if (value < 0f) continue

            val amplitude = max(minAmplitude, value * halfHeight)
            val x = column + 0.5f
            lineBuffer[count] = x
            lineBuffer[count + 1] = centerY - amplitude
            lineBuffer[count + 2] = x
            lineBuffer[count + 3] = centerY + amplitude
            count += 4
        }
        if (count > 0) canvas.drawLines(lineBuffer, 0, count, paint)
    }

    private fun drawSelection(
        canvas: Canvas,
        width: Float,
        height: Float,
        contentTop: Float
    ) {
        val startX =
            timelineToX(
                selectionStart
            )

        val endX =
            timelineToX(
                selectionEnd
            )

        val left =
            max(
                0f,
                min(
                    startX,
                    endX
                )
            )

        val right =
            min(
                width,
                max(
                    startX,
                    endX
                )
            )

        if (
            right >=
            0f &&
            left <=
            width &&
            abs(
                selectionEnd -
                    selectionStart
            ) >
            MIN_SELECTION_SPAN
        ) {
            canvas.drawRect(
                left,
                contentTop,
                right,
                height,
                selectionPaint
            )
        }

        drawHandle(
            canvas,
            startX,
            height,
            contentTop
        )

        drawHandle(
            canvas,
            endX,
            height,
            contentTop
        )
    }

    private fun drawHandle(
        canvas: Canvas,
        x: Float,
        height: Float,
        contentTop: Float
    ) {
        if (
            x <
            -density(8f) ||
            x >
            width +
                density(8f)
        ) {
            return
        }

        canvas.drawLine(
            x,
            contentTop,
            x,
            height,
            handlePaint
        )

        canvas.drawCircle(
            x,
            contentTop +
                density(8f),
            density(5.5f),
            handleFillPaint
        )
    }

    private fun drawPlaybackHead(
        canvas: Canvas,
        height: Float,
        contentTop: Float
    ) {
        if (
            playbackPosition <
            visibleStart ||
            playbackPosition >
            visibleEnd
        ) {
            return
        }

        val x =
            timelineToX(
                playbackPosition
            )

        canvas.drawLine(
            x,
            contentTop,
            x,
            height,
            playbackHeadPaint
        )

        canvas.drawCircle(
            x,
            contentTop +
                density(6f),
            density(4.5f),
            playbackHeadFillPaint
        )
    }

    private fun drawTimeRuler(
        canvas: Canvas,
        width: Float
    ) {
        if (
            durationMs <=
            0L
        ) {
            return
        }

        val divisions =
            4

        val visibleDurationMs =
            (durationMs.toDouble() * (visibleEnd - visibleStart)).toLong()

        for (
            i in
            0..divisions
        ) {
            val ratio =
                i /
                    divisions
                        .toFloat()

            val timelineFraction =
                visibleStart +
                    (
                        visibleEnd -
                            visibleStart
                        ) *
                    ratio

            val timeMs =
                (
                    durationMs.toDouble() *
                        timelineFraction
                    )
                    .toLong()

            val label =
                if (visibleDurationMs < 10_000L) {
                    formatRulerTimeFine(
                        timeMs
                    )
                } else {
                    formatRulerTime(
                        timeMs
                    )
                }

            val x =
                width *
                    ratio

            val textWidth =
                rulerPaint
                    .measureText(
                        label
                    )

            val drawX =
                when (
                    i
                ) {
                    0 ->
                        0f

                    divisions ->
                        width -
                            textWidth

                    else ->
                        x -
                            textWidth /
                            2f
                }

            canvas.drawText(
                label,
                drawX,
                density(10f),
                rulerPaint
            )
        }
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {
        if (
            mode !=
            Mode.EDIT ||
            !isEnabled
        ) {
            return false
        }

        scaleDetector
            .onTouchEvent(
                event
            )

        when (
            event.actionMasked
        ) {
            MotionEvent
                .ACTION_DOWN -> {
                parent
                    ?.requestDisallowInterceptTouchEvent(
                        true
                    )

                downX =
                    event.x

                lastTouchX =
                    event.x

                pointerMoved =
                    false

                val startX =
                    timelineToX(
                        selectionStart
                    )

                val endX =
                    timelineToX(
                        selectionEnd
                    )

                val hit =
                    density(24f)

                gesture =
                    when {
                        abs(
                            event.x -
                                startX
                        ) <=
                        hit ->
                            Gesture
                                .START_HANDLE

                        abs(
                            event.x -
                                endX
                        ) <=
                        hit ->
                            Gesture
                                .END_HANDLE

                        else ->
                            Gesture
                                .POSSIBLE_TAP
                    }

                if (
                    gesture ==
                    Gesture.POSSIBLE_TAP
                ) {
                    armLongPress()
                }

                return true
            }

            MotionEvent
                .ACTION_POINTER_DOWN -> {
                cancelLongPressGesture()

                gesture =
                    Gesture.NONE

                return true
            }

            MotionEvent
                .ACTION_MOVE -> {
                if (
                    event.pointerCount >
                    1 ||
                    scaleDetector
                        .isInProgress
                ) {
                    cancelLongPressGesture()
                    gesture =
                        Gesture.NONE

                    return true
                }

                val x =
                    event.x

                val totalDx =
                    x -
                        downX

                if (
                    abs(
                        totalDx
                    ) >
                    touchSlop
                ) {
                    pointerMoved =
                        true
                }

                when (
                    gesture
                ) {
                    Gesture
                        .START_HANDLE ->
                        moveStartHandle(
                            x
                        )

                    Gesture
                        .END_HANDLE ->
                        moveEndHandle(
                            x
                        )

                    Gesture
                        .RANGE_CREATE ->
                        updateCreatedRange(
                            x
                        )

                    Gesture
                        .POSSIBLE_TAP -> {
                        if (
                            pointerMoved
                        ) {
                            cancelLongPressGesture()

                            gesture =
                                Gesture.PAN

                            panBy(
                                x -
                                    lastTouchX
                            )
                        }
                    }

                    Gesture
                        .PAN ->
                        panBy(
                            x -
                                lastTouchX
                        )

                    Gesture
                        .NONE ->
                        Unit
                }

                lastTouchX =
                    x

                return true
            }

            MotionEvent
                .ACTION_UP -> {
                val x =
                    event.x

                when (
                    gesture
                ) {
                    Gesture
                        .POSSIBLE_TAP -> {
                        cancelLongPressGesture()

                        if (
                            !pointerMoved
                        ) {
                            movePlayheadTo(
                                x
                            )

                            performClick()
                        }
                    }

                    Gesture
                        .RANGE_CREATE -> {
                        updateCreatedRange(
                            x
                        )
                    }

                    else ->
                        cancelLongPressGesture()
                }

                gesture =
                    Gesture.NONE

                parent
                    ?.requestDisallowInterceptTouchEvent(
                        false
                    )

                return true
            }

            MotionEvent
                .ACTION_CANCEL -> {
                cancelLongPressGesture()

                gesture =
                    Gesture.NONE

                parent
                    ?.requestDisallowInterceptTouchEvent(
                        false
                    )

                return true
            }
        }

        return true
    }

    override fun performClick():
        Boolean {

        super.performClick()
        return true
    }

    private fun armLongPress() {
        longPressArmed =
            true

        mainHandler
            .postDelayed(
                longPressRunnable,
                longPressTimeout
            )
    }

    private fun cancelLongPressGesture() {
        longPressArmed =
            false

        mainHandler
            .removeCallbacks(
                longPressRunnable
            )
    }

    private fun movePlayheadTo(
        x: Float
    ) {
        playbackPosition =
            xToTimeline(
                x
            )

        invalidate()

        onPlayheadChanged
            ?.invoke(
                playbackPosition
            )
    }

    private fun updateCreatedRange(
        x: Float
    ) {
        val current =
            xToTimeline(
                x
            )

        selectionStart =
            min(
                rangeAnchor,
                current
            )

        selectionEnd =
            max(
                rangeAnchor,
                current
            )

        playbackPosition =
            selectionStart

        onPlayheadChanged
            ?.invoke(
                playbackPosition
            )

        selectionChanged()
    }

    private fun moveStartHandle(
        x: Float
    ) {
        val fraction =
            xToTimeline(
                x
            )

        selectionStart =
            min(
                fraction,
                selectionEnd -
                    MIN_SELECTION_SPAN
            )
                .coerceIn(
                    0f,
                    1f
                )

        selectionChanged()
    }

    private fun moveEndHandle(
        x: Float
    ) {
        val fraction =
            xToTimeline(
                x
            )

        selectionEnd =
            max(
                fraction,
                selectionStart +
                    MIN_SELECTION_SPAN
            )
                .coerceIn(
                    0f,
                    1f
                )

        selectionChanged()
    }

    private fun selectionChanged() {
        invalidate()

        onSelectionChanged
            ?.invoke(
                selectionStart,
                selectionEnd
            )
    }

    private fun panBy(
        dx: Float
    ) {
        val span =
            visibleEnd -
                visibleStart

        if (
            span >=
            0.9999f ||
            width <=
            0
        ) {
            return
        }

        val delta =
            -dx /
                width *
                span

        val start =
            (
                visibleStart +
                    delta
                )
                .coerceIn(
                    0f,
                    1f -
                        span
                )

        visibleStart =
            start

        visibleEnd =
            start +
                span

        viewportChanged()
    }

    private fun keepPlayheadVisible() {
        val span =
            visibleEnd -
                visibleStart

        if (
            span >=
            0.9999f
        ) {
            return
        }

        if (
            playbackPosition <
            visibleStart ||
            playbackPosition >
            visibleEnd
        ) {
            val newStart =
                (
                    playbackPosition -
                        span /
                        2f
                    )
                    .coerceIn(
                        0f,
                        1f -
                            span
                    )

            visibleStart =
                newStart

            visibleEnd =
                newStart +
                    span

            onViewportChanged?.invoke(visibleStart, visibleEnd)
        }
    }

    private fun timelineToX(
        fraction: Float
    ): Float {
        val span =
            visibleEnd -
                visibleStart

        if (
            span <=
            0f ||
            width <=
            0
        ) {
            return 0f
        }

        return (
            (
                fraction -
                    visibleStart
                ) /
                span
            ) *
            width
    }

    private fun xToTimeline(
        x: Float
    ): Float {
        if (
            width <=
            0
        ) {
            return visibleStart
        }

        val normalized =
            (
                x /
                    width
                )
                .coerceIn(
                    0f,
                    1f
                )

        return (
            visibleStart +
                normalized *
                (
                    visibleEnd -
                        visibleStart
                    )
            )
            .coerceIn(
                0f,
                1f
            )
    }

    /** m:ss.mmm for zoomed-in views. */
    private fun formatRulerTimeFine(
        ms: Long
    ): String =
        String.format(
            java.util.Locale.US,
            "%d:%02d.%03d",
            ms / 60_000L,
            (ms / 1000L) % 60L,
            ms % 1000L
        )

    private fun formatRulerTime(
        ms: Long
    ): String {
        val totalSeconds =
            ms /
                1000L

        val minutes =
            totalSeconds /
                60L

        val seconds =
            totalSeconds %
                60L

        return "%02d:%02d"
            .format(
                minutes,
                seconds
            )
    }

    private fun density(
        dp: Float
    ): Float =
        dp * densityScale

    companion object {
        /** Deepest zoom: 240 frames (5 ms) across the view. */
        const val MIN_VISIBLE_FRAMES = 240

        private const val MIN_VISIBLE_SPAN =
            0.015625f

        private const val MIN_SELECTION_SPAN =
            0.0005f
    }
}
