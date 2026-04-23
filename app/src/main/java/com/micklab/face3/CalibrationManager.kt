package com.micklab.face3

class CalibrationManager {

    private var calibrationState: CalibrationState? = null

    fun calibrate(frame: FaceMeshProcessor.FaceMeshFrame): CalibrationState? {
        val eyeCalibrations = buildMap {
            frame.rightEye?.let { put(it.side, EyeCalibration.from(it)) }
            frame.leftEye?.let { put(it.side, EyeCalibration.from(it)) }
        }
        if (eyeCalibrations.isEmpty()) {
            return null
        }

        val calibrationIrisCenter = computeIrisCenter(frame)
        return CalibrationState(
            preferredEye = frame.preferredEye()?.side ?: eyeCalibrations.keys.first(),
            distanceReference = frame.distanceEstimate,
            faceYaw = frame.faceYaw,
            facePitch = frame.facePitch,
            eyes = eyeCalibrations,
            calibrationIrisCenter = calibrationIrisCenter,
            calibrationNoseTip = frame.noseTip,
        ).also {
            calibrationState = it
        }
    }

    fun isCalibrated(): Boolean = calibrationState != null

    fun currentState(): CalibrationState? = calibrationState

    fun clear() {
        calibrationState = null
    }

    fun correctionFor(
        frame: FaceMeshProcessor.FaceMeshFrame,
        eye: FaceMeshProcessor.EyeMetrics,
    ): Correction? {
        val snapshot = calibrationState ?: return null
        val baseline = snapshot.eyes[eye.side] ?: return null
        return Correction(
            baseline = baseline,
            distanceScale = (baseline.eyeWidth / eye.eyeWidth).coerceIn(MIN_DISTANCE_SCALE, MAX_DISTANCE_SCALE),
            yawDelta = frame.faceYaw - snapshot.faceYaw,
            pitchDelta = frame.facePitch - snapshot.facePitch,
            noseDeltaX = eye.noseOffsetX - baseline.noseOffsetX,
            noseDeltaY = eye.noseOffsetY - baseline.noseOffsetY,
        )
    }

    fun compute3DDistanceFromCalibration(
        currentIrisCenter: FaceMeshProcessor.Point3,
    ): Float {
        val snapshot = calibrationState ?: return 0f
        val delta = currentIrisCenter.minus3D(snapshot.calibrationIrisCenter)
        return delta.magnitude3D()
    }

    fun getCalibrationIrisCenter(): FaceMeshProcessor.Point3? {
        return calibrationState?.calibrationIrisCenter
    }

    fun getCalibrationNoseTip(): FaceMeshProcessor.Point3? {
        return calibrationState?.calibrationNoseTip
    }

    data class CalibrationState(
        val preferredEye: FaceMeshProcessor.EyeSide,
        val distanceReference: Float,
        val faceYaw: Float,
        val facePitch: Float,
        val eyes: Map<FaceMeshProcessor.EyeSide, EyeCalibration>,
        val calibrationIrisCenter: FaceMeshProcessor.Point3,
        val calibrationNoseTip: FaceMeshProcessor.Point3,
    )

    data class EyeCalibration(
        val side: FaceMeshProcessor.EyeSide,
        val eyeWidth: Float,
        val irisOffsetX: Float,
        val irisOffsetY: Float,
        val noseOffsetX: Float,
        val noseOffsetY: Float,
    ) {
        companion object {
            fun from(eye: FaceMeshProcessor.EyeMetrics): EyeCalibration =
                EyeCalibration(
                    side = eye.side,
                    eyeWidth = eye.eyeWidth,
                    irisOffsetX = eye.irisOffsetX,
                    irisOffsetY = eye.irisOffsetY,
                    noseOffsetX = eye.noseOffsetX,
                    noseOffsetY = eye.noseOffsetY,
                )
        }
    }

    data class Correction(
        val baseline: EyeCalibration,
        val distanceScale: Float,
        val yawDelta: Float,
        val pitchDelta: Float,
        val noseDeltaX: Float,
        val noseDeltaY: Float,
    )

    private companion object {
        private const val MIN_DISTANCE_SCALE = 0.65f
        private const val MAX_DISTANCE_SCALE = 1.60f

        fun computeIrisCenter(frame: FaceMeshProcessor.FaceMeshFrame): FaceMeshProcessor.Point3 {
            val eyes = frame.visibleEyes()
            if (eyes.isEmpty()) {
                return FaceMeshProcessor.Point3(0f, 0f, 0f)
            }
            val meanX = eyes.sumOf { it.irisCenter.x.toDouble() } / eyes.size
            val meanY = eyes.sumOf { it.irisCenter.y.toDouble() } / eyes.size
            val meanZ = eyes.sumOf { it.irisCenter.z.toDouble() } / eyes.size
            return FaceMeshProcessor.Point3(
                x = meanX.toFloat(),
                y = meanY.toFloat(),
                z = meanZ.toFloat(),
            )
        }
    }
}
