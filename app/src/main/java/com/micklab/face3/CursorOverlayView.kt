package com.micklab.face3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.cursor_anchor)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val anchorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.cursor_anchor)
        style = Paint.Style.FILL
        alpha = 170
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.cursor_fill)
        style = Paint.Style.FILL
        alpha = 220
    }

    private val cursorOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.cursor_outline)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private var normalizedX = 0f
    private var normalizedY = 0f

    fun setCursorOffsetNormalized(x: Float, y: Float) {
        val clampedX = x.coerceIn(-1f, 1f)
        val clampedY = y.coerceIn(-1f, 1f)
        if (abs(clampedX - normalizedX) < 0.001f && abs(clampedY - normalizedY) < 0.001f) {
            return
        }

        normalizedX = clampedX
        normalizedY = clampedY
        postInvalidateOnAnimation()
    }

    fun centerCursor() {
        setCursorOffsetNormalized(0f, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) {
            return
        }

        val centerX = width / 2f
        val centerY = height / 2f
        val anchorRadius = 10f * resources.displayMetrics.density
        val cursorRadius = 14f * resources.displayMetrics.density
        val maxTravelX = width * 0.32f
        val maxTravelY = height * 0.24f
        val cursorX = centerX + (normalizedX * maxTravelX)
        val cursorY = centerY + (normalizedY * maxTravelY)

        canvas.drawCircle(centerX, centerY, anchorRadius, anchorPaint)
        canvas.drawCircle(centerX, centerY, 3f * resources.displayMetrics.density, anchorDotPaint)
        canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorPaint)
        canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorOutlinePaint)
    }
}
