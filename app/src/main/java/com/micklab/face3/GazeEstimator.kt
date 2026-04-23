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
        val samples = frame.visibleEyes().mapNotNull { eye ->
            val correction = calibrationManager.correctionFor(frame, eye) ?: return@mapNotNull null
            EyeSample(eye = eye, correction = correction)
        }
        if (samples.isEmpty()) {
            return null
        }

        val correctedX = samples
            .map { sample ->
                val irisDeltaX = sample.eye.irisOffsetX - sample.correction.baseline.irisOffsetX
                (
                    irisDeltaX -
                        (sample.correction.noseDeltaX * NOSE_COMPENSATION_X) -
                        (sample.correction.yawDelta * YAW_COMPENSATION_X)
                    ) * sample.correction.distanceScale
            }
            .average()
            .toFloat()
        val correctedY = samples
            .map { sample ->
                val irisDeltaY = sample.eye.irisOffsetY - sample.correction.baseline.irisOffsetY
                (
                    irisDeltaY -
                        (sample.correction.noseDeltaY * NOSE_COMPENSATION_Y) -
                        (sample.correction.pitchDelta * PITCH_COMPENSATION_Y)
                    ) * sample.correction.distanceScale
            }
            .average()
            .toFloat()

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

        val currentIrisCenter = samples.map { it.eye.irisCenter }.let { iris ->
            val meanX = iris.sumOf { it.x.toDouble() } / iris.size
            val meanY = iris.sumOf { it.y.toDouble() } / iris.size
            val meanZ = iris.sumOf { it.z.toDouble() } / iris.size
            FaceMeshProcessor.Point3(
                x = meanX.toFloat(),
                y = meanY.toFloat(),
                z = meanZ.toFloat(),
            )
        }
        val calibrationDistance = calibrationManager.compute3DDistanceFromCalibration(currentIrisCenter)

        return GazeEstimate(
            trackingMode = trackingModeFor(samples),
            offsetX = smoothedX,
            offsetY = smoothedY,
            distanceScale = samples.map { it.correction.distanceScale }.average().toFloat(),
            poseYawDelta = samples.map { it.correction.yawDelta }.average().toFloat(),
            posePitchDelta = samples.map { it.correction.pitchDelta }.average().toFloat(),
            calibrationDistance = calibrationDistance,
        )
    }

    data class GazeEstimate(
        val trackingMode: TrackingMode,
        val offsetX: Float,
        val offsetY: Float,
        val distanceScale: Float,
        val poseYawDelta: Float,
        val posePitchDelta: Float,
        val calibrationDistance: Float,
    )

    enum class TrackingMode(
        val label: String,
    ) {
        BOTH("両目平均"),
        RIGHT("右目"),
        LEFT("左目"),
    }

    private data class EyeSample(
        val eye: FaceMeshProcessor.EyeMetrics,
        val correction: CalibrationManager.Correction,
    )

    private fun trackingModeFor(samples: List<EyeSample>): TrackingMode {
        return when {
            samples.size >= 2 -> TrackingMode.BOTH
            samples.first().eye.side == FaceMeshProcessor.EyeSide.RIGHT -> TrackingMode.RIGHT
            else -> TrackingMode.LEFT
        }
    }

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
