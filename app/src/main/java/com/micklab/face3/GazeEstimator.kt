package com.micklab.face3

class GazeEstimator {

    private var smoothedX = 0f
    private var smoothedY = 0f
    private var hasEstimate = false

    fun reset() {
        smoothedX = 0f
        smoothedY = 0f
        hasEstimate = false
    }

    fun estimate(
        frame: FaceMeshProcessor.FaceMeshFrame,
        calibrationManager: CalibrationManager,
        horizontalSensitivity: Float,
        verticalSensitivity: Float,
    ): GazeEstimate? {
        val eye = frame.preferredEye() ?: return null
        val correction = calibrationManager.correctionFor(frame, eye) ?: return null

        val irisDeltaX = eye.irisOffsetX - correction.baseline.irisOffsetX
        val irisDeltaY = eye.irisOffsetY - correction.baseline.irisOffsetY

        val correctedX = (
            irisDeltaX -
                (correction.noseDeltaX * NOSE_COMPENSATION_X) -
                (correction.yawDelta * YAW_COMPENSATION_X)
            ) * correction.distanceScale
        val correctedY = (
            irisDeltaY -
                (correction.noseDeltaY * NOSE_COMPENSATION_Y) -
                (correction.pitchDelta * PITCH_COMPENSATION_Y)
            ) * correction.distanceScale

        val targetX = (correctedX * horizontalSensitivity * HORIZONTAL_GAIN).coerceIn(-1f, 1f)
        val targetY = (correctedY * verticalSensitivity * VERTICAL_GAIN).coerceIn(-1f, 1f)

        if (!hasEstimate) {
            smoothedX = targetX
            smoothedY = targetY
            hasEstimate = true
        } else {
            smoothedX += (targetX - smoothedX) * SMOOTHING_ALPHA
            smoothedY += (targetY - smoothedY) * SMOOTHING_ALPHA
        }

        return GazeEstimate(
            eyeSide = eye.side,
            offsetX = smoothedX,
            offsetY = smoothedY,
            distanceScale = correction.distanceScale,
            poseYawDelta = correction.yawDelta,
            posePitchDelta = correction.pitchDelta,
        )
    }

    data class GazeEstimate(
        val eyeSide: FaceMeshProcessor.EyeSide,
        val offsetX: Float,
        val offsetY: Float,
        val distanceScale: Float,
        val poseYawDelta: Float,
        val posePitchDelta: Float,
    )

    private companion object {
        private const val HORIZONTAL_GAIN = 5.0f
        private const val VERTICAL_GAIN = 4.5f
        private const val NOSE_COMPENSATION_X = 0.55f
        private const val NOSE_COMPENSATION_Y = 0.65f
        private const val YAW_COMPENSATION_X = 0.45f
        private const val PITCH_COMPENSATION_Y = 0.55f
        private const val SMOOTHING_ALPHA = 0.28f
    }
}
