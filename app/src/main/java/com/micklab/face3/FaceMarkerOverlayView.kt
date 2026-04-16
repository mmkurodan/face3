package com.micklab.face3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class FaceMarkerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val referenceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.face_reference_marker)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private val referenceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.face_reference_marker)
        style = Paint.Style.FILL
        alpha = 210
    }

    private val trackedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.face_tracked_marker)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private val trackedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.face_tracked_marker)
        style = Paint.Style.FILL
        alpha = 140
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.face_marker_guide)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private var referencePoint: FaceMeshProcessor.Point3? = null
    private var trackedPoint: FaceMeshProcessor.Point3? = null
    private var sourceWidth = 1
    private var sourceHeight = 1

    fun setMarkers(
        referencePoint: FaceMeshProcessor.Point3,
        trackedPoint: FaceMeshProcessor.Point3,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        val normalizedWidth = sourceWidth.coerceAtLeast(1)
        val normalizedHeight = sourceHeight.coerceAtLeast(1)
        if (this.referencePoint == referencePoint &&
            this.trackedPoint == trackedPoint &&
            this.sourceWidth == normalizedWidth &&
            this.sourceHeight == normalizedHeight
        ) {
            return
        }

        this.referencePoint = referencePoint
        this.trackedPoint = trackedPoint
        this.sourceWidth = normalizedWidth
        this.sourceHeight = normalizedHeight
        postInvalidateOnAnimation()
    }

    fun clearMarkers() {
        if (referencePoint == null && trackedPoint == null) {
            return
        }

        referencePoint = null
        trackedPoint = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) {
            return
        }

        val reference = referencePoint ?: return
        val tracked = trackedPoint ?: return
        val imageBounds = computeImageBounds()
        val referenceX = imageBounds.left + ((1f - reference.x.coerceIn(0f, 1f)) * imageBounds.width)
        val referenceY = imageBounds.top + (reference.y.coerceIn(0f, 1f) * imageBounds.height)
        val trackedX = imageBounds.left + ((1f - tracked.x.coerceIn(0f, 1f)) * imageBounds.width)
        val trackedY = imageBounds.top + (tracked.y.coerceIn(0f, 1f) * imageBounds.height)
        val referenceRadius = 10f * resources.displayMetrics.density
        val trackedRadius = 12f * resources.displayMetrics.density
        val crossHalfSize = 16f * resources.displayMetrics.density

        canvas.drawLine(referenceX, referenceY, trackedX, trackedY, guidePaint)

        canvas.drawCircle(trackedX, trackedY, trackedRadius, trackedFillPaint)
        canvas.drawCircle(trackedX, trackedY, trackedRadius, trackedStrokePaint)
        canvas.drawCircle(trackedX, trackedY, trackedRadius * 0.35f, trackedStrokePaint)

        canvas.drawCircle(referenceX, referenceY, referenceRadius, referenceStrokePaint)
        canvas.drawLine(
            referenceX - crossHalfSize,
            referenceY,
            referenceX + crossHalfSize,
            referenceY,
            referenceStrokePaint,
        )
        canvas.drawLine(
            referenceX,
            referenceY - crossHalfSize,
            referenceX,
            referenceY + crossHalfSize,
            referenceStrokePaint,
        )
        canvas.drawCircle(referenceX, referenceY, referenceRadius * 0.35f, referenceFillPaint)
    }

    private fun computeImageBounds(): ImageBounds {
        val sourceWidth = sourceWidth.toFloat()
        val sourceHeight = sourceHeight.toFloat()
        val scale = max(width / sourceWidth, height / sourceHeight)
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        return ImageBounds(
            left = (width - drawnWidth) / 2f,
            top = (height - drawnHeight) / 2f,
            width = drawnWidth,
            height = drawnHeight,
        )
    }

    private data class ImageBounds(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )
}
