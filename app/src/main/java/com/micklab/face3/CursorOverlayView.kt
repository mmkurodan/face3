package com.micklab.face3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

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
    private var cursorMovementThresholdRatioX = 0.015f
    private var cursorMovementThresholdRatioY = 0.015f
    private var cursorMovementSpeed = 3f
    private var isCursorActive = false
    private var targetNormalizedX = 0f
    private var targetNormalizedY = 0f

    fun setCursorMovementThresholdX(ratio: Float) {
        cursorMovementThresholdRatioX = ratio.coerceIn(0.005f, 0.05f)
    }

    fun setCursorMovementThresholdY(ratio: Float) {
        cursorMovementThresholdRatioY = ratio.coerceIn(0.005f, 0.05f)
    }

    fun setCursorMovementSpeed(speed: Float) {
        cursorMovementSpeed = speed.coerceAtLeast(0.5f)
    }

    fun setCursorOffsetNormalized(
        x: Float,
        y: Float,
        calibrationDistanceX: Float = 0f,
        calibrationDistanceY: Float = 0f,
    ) {
        val clampedX = x.coerceIn(-1f, 1f)
        val clampedY = y.coerceIn(-1f, 1f)

        val wasActive = isCursorActive
        isCursorActive = calibrationDistanceX > cursorMovementThresholdRatioX ||
            calibrationDistanceY > cursorMovementThresholdRatioY

        if (isCursorActive) {
            targetNormalizedX = clampedX
            targetNormalizedY = clampedY

            val deltaX = targetNormalizedX - normalizedX
            val deltaY = targetNormalizedY - normalizedY

            if (abs(deltaX) >= 0.001f || abs(deltaY) >= 0.001f) {
                val distance = kotlin.math.sqrt((deltaX * deltaX) + (deltaY * deltaY))
                if (distance > 0f) {
                    val speed = cursorMovementSpeed * 0.01f
                    val stepSize = speed.coerceAtMost(distance)
                    normalizedX += (deltaX / distance) * stepSize
                    normalizedY += (deltaY / distance) * stepSize

                    normalizedX = normalizedX.coerceIn(-1f, 1f)
                    normalizedY = normalizedY.coerceIn(-1f, 1f)
                    postInvalidateOnAnimation()
                }
            }
        } else if (!isCursorActive && wasActive) {
            postInvalidateOnAnimation()
        }
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
        val edgePadding = 8f * resources.displayMetrics.density
        val anchorRadius = 10f * resources.displayMetrics.density
        val cursorRadius = 14f * resources.displayMetrics.density
        val minCursorX = cursorRadius + edgePadding
        val maxCursorX = width - cursorRadius - edgePadding
        val minCursorY = cursorRadius + edgePadding
        val maxCursorY = height - cursorRadius - edgePadding
        val maxTravelX = (centerX - minCursorX).coerceAtLeast(0f)
        val maxTravelY = (centerY - minCursorY).coerceAtLeast(0f)
        val cursorX = (centerX + (normalizedX * maxTravelX)).coerceIn(minCursorX, maxCursorX)
        val cursorY = (centerY + (normalizedY * maxTravelY)).coerceIn(minCursorY, maxCursorY)

        canvas.drawCircle(centerX, centerY, anchorRadius, anchorPaint)
        canvas.drawCircle(centerX, centerY, 3f * resources.displayMetrics.density, anchorDotPaint)
        canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorPaint)
        canvas.drawCircle(cursorX, cursorY, cursorRadius, cursorOutlinePaint)
    }
}
