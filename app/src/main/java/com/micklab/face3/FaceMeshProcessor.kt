package com.micklab.face3

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class FaceMeshProcessor(
    context: Context,
    private val listener: Listener,
) {

    private val isProcessing = AtomicBoolean(false)
    private val baseOptions = BaseOptions.builder()
        .setModelAssetPath(MODEL_ASSET_PATH)
        .build()
    private val faceLandmarker: FaceLandmarker

    private var bitmapBuffer: Bitmap? = null
    private var lastSubmittedAtMs = 0L
    private var lastFrameDisplayWidth = 1
    private var lastFrameDisplayHeight = 1

    init {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(false)
            .setResultListener(this::onResult)
            .setErrorListener(this::onError)
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun process(imageProxy: ImageProxy) {
        val frameTime = SystemClock.uptimeMillis()
        if (frameTime - lastSubmittedAtMs < MIN_FRAME_INTERVAL_MS ||
            !isProcessing.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }

        val frameWidth = imageProxy.width
        val frameHeight = imageProxy.height
        val bitmap = obtainBitmapBuffer(frameWidth, frameHeight)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val isQuarterTurn = rotationDegrees % 180 != 0

        try {
            imageProxy.use {
                val planeBuffer = imageProxy.planes.firstOrNull()?.buffer
                if (planeBuffer == null) {
                    isProcessing.set(false)
                    listener.onError("Camera frame buffer is unavailable.")
                    return
                }

                planeBuffer.rewind()
                bitmap.copyPixelsFromBuffer(planeBuffer)
            }

            val mpImage = BitmapImageBuilder(bitmap).build()
            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotationDegrees)
                .build()

            lastFrameDisplayWidth = if (isQuarterTurn) frameHeight else frameWidth
            lastFrameDisplayHeight = if (isQuarterTurn) frameWidth else frameHeight
            lastSubmittedAtMs = frameTime
            faceLandmarker.detectAsync(mpImage, imageProcessingOptions, frameTime)
        } catch (t: Throwable) {
            isProcessing.set(false)
            listener.onError(t.message ?: "Face mesh processing failed.")
        }
    }

    fun close() {
        faceLandmarker.close()
    }

    private fun obtainBitmapBuffer(width: Int, height: Int): Bitmap {
        val current = bitmapBuffer
        if (current != null && current.width == width && current.height == height) {
            return current
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            bitmapBuffer = it
        }
    }

    private fun onResult(result: FaceLandmarkerResult, input: MPImage) {
        isProcessing.set(false)

        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks.isNullOrEmpty()) {
            listener.onNoFaceDetected()
            return
        }

        val frame = buildFrame(
            landmarks = landmarks,
            inferenceTimeMs = (SystemClock.uptimeMillis() - result.timestampMs()).coerceAtLeast(0L),
        )
        if (frame == null) {
            listener.onNoFaceDetected()
            return
        }

        listener.onFaceFrame(frame)
    }

    private fun onError(error: RuntimeException) {
        isProcessing.set(false)
        listener.onError(error.message ?: "MediaPipe Face Landmarker reported an error.")
    }

    private fun buildFrame(
        landmarks: List<NormalizedLandmark>,
        inferenceTimeMs: Long,
    ): FaceMeshFrame? {
        val noseTip = landmarks.getPoint(NOSE_TIP_INDEX) ?: return null
        val rightEye = buildEye(
            side = EyeSide.RIGHT,
            landmarks = landmarks,
            noseTip = noseTip,
            outerCornerIndex = RIGHT_OUTER_CORNER,
            innerCornerIndex = RIGHT_INNER_CORNER,
            irisIndices = RIGHT_IRIS_INDICES,
            upperLidIndices = RIGHT_UPPER_LID_INDICES,
            lowerLidIndices = RIGHT_LOWER_LID_INDICES,
        )
        val leftEye = buildEye(
            side = EyeSide.LEFT,
            landmarks = landmarks,
            noseTip = noseTip,
            outerCornerIndex = LEFT_OUTER_CORNER,
            innerCornerIndex = LEFT_INNER_CORNER,
            irisIndices = LEFT_IRIS_INDICES,
            upperLidIndices = LEFT_UPPER_LID_INDICES,
            lowerLidIndices = LEFT_LOWER_LID_INDICES,
        )
        if (rightEye == null && leftEye == null) {
            return null
        }

        val (faceYaw, facePitch) = computeFacePose(noseTip, rightEye, leftEye)
        val distanceEstimate = listOfNotNull(rightEye?.eyeWidth, leftEye?.eyeWidth)
            .average()
            .toFloat()
            .coerceAtLeast(EPSILON)

        return FaceMeshFrame(
            noseTip = noseTip,
            rightEye = rightEye,
            leftEye = leftEye,
            faceYaw = faceYaw,
            facePitch = facePitch,
            distanceEstimate = distanceEstimate,
            inferenceTimeMs = inferenceTimeMs,
            displayWidth = lastFrameDisplayWidth,
            displayHeight = lastFrameDisplayHeight,
        )
    }

    private fun buildEye(
        side: EyeSide,
        landmarks: List<NormalizedLandmark>,
        noseTip: Point3,
        outerCornerIndex: Int,
        innerCornerIndex: Int,
        irisIndices: List<Int>,
        upperLidIndices: List<Int>,
        lowerLidIndices: List<Int>,
    ): EyeMetrics? {
        val outerCorner = landmarks.getPoint(outerCornerIndex) ?: return null
        val innerCorner = landmarks.getPoint(innerCornerIndex) ?: return null
        val irisCenter = averagePoint(landmarks, irisIndices) ?: return null
        val upperLid = averagePoint(landmarks, upperLidIndices) ?: return null
        val lowerLid = averagePoint(landmarks, lowerLidIndices) ?: return null

        val horizontalVector = innerCorner - outerCorner
        val horizontalUnit = horizontalVector.normalizedOrNull() ?: return null
        val verticalSeed = lowerLid - upperLid
        val verticalVector = verticalSeed - (horizontalUnit * verticalSeed.dot(horizontalUnit))
        val verticalUnit = verticalVector.normalizedOrNull() ?: return null

        val eyeWidth = horizontalVector.magnitude()
        val eyeHeight = verticalVector.magnitude()
        if (eyeWidth < EPSILON || eyeHeight < EPSILON) {
            return null
        }

        val eyeCenter = averagePoints(listOf(outerCorner, innerCorner))
        val irisRelative = irisCenter - eyeCenter
        val noseRelative = noseTip - eyeCenter

        return EyeMetrics(
            side = side,
            irisCenter = irisCenter,
            eyeCenter = eyeCenter,
            outerCorner = outerCorner,
            innerCorner = innerCorner,
            eyeWidth = eyeWidth,
            eyeHeight = eyeHeight,
            irisOffsetX = side.horizontalSign * (irisRelative.dot(horizontalUnit) / eyeWidth),
            irisOffsetY = irisRelative.dot(verticalUnit) / eyeHeight,
            noseOffsetX = side.horizontalSign * (noseRelative.dot(horizontalUnit) / eyeWidth),
            noseOffsetY = noseRelative.dot(verticalUnit) / eyeWidth,
        )
    }

    private fun computeFacePose(
        noseTip: Point3,
        rightEye: EyeMetrics?,
        leftEye: EyeMetrics?,
    ): Pair<Float, Float> {
        return when {
            rightEye != null && leftEye != null -> {
                val midpoint = averagePoints(listOf(rightEye.eyeCenter, leftEye.eyeCenter))
                val interEyeDistance = (rightEye.eyeCenter - leftEye.eyeCenter)
                    .magnitude()
                    .coerceAtLeast(EPSILON)
                Pair(
                    first = (noseTip.x - midpoint.x) / interEyeDistance,
                    second = (noseTip.y - midpoint.y) / interEyeDistance,
                )
            }

            rightEye != null -> Pair(rightEye.noseOffsetX, rightEye.noseOffsetY)
            leftEye != null -> Pair(leftEye.noseOffsetX, leftEye.noseOffsetY)
            else -> Pair(0f, 0f)
        }
    }

    private fun averagePoint(
        landmarks: List<NormalizedLandmark>,
        indices: List<Int>,
    ): Point3? {
        val selected = indices.mapNotNull { index -> landmarks.getPoint(index) }
        if (selected.isEmpty()) {
            return null
        }
        return averagePoints(selected)
    }

    private fun averagePoints(points: List<Point3>): Point3 {
        val meanX = points.sumOf { it.x.toDouble() } / points.size
        val meanY = points.sumOf { it.y.toDouble() } / points.size
        val meanZ = points.sumOf { it.z.toDouble() } / points.size
        return Point3(
            x = meanX.toFloat(),
            y = meanY.toFloat(),
            z = meanZ.toFloat(),
        )
    }

    private fun List<NormalizedLandmark>.getPoint(index: Int): Point3? {
        val landmark = getOrNull(index) ?: return null
        return Point3(landmark.x(), landmark.y(), landmark.z())
    }

    data class FaceMeshFrame(
        val noseTip: Point3,
        val rightEye: EyeMetrics?,
        val leftEye: EyeMetrics?,
        val faceYaw: Float,
        val facePitch: Float,
        val distanceEstimate: Float,
        val inferenceTimeMs: Long,
        val displayWidth: Int,
        val displayHeight: Int,
    ) {
        fun preferredEye(): EyeMetrics? = rightEye ?: leftEye
    }

    enum class EyeSide(
        val label: String,
        val horizontalSign: Float,
    ) {
        RIGHT(label = "右目", horizontalSign = -1f),
        LEFT(label = "左目", horizontalSign = 1f),
    }

    data class EyeMetrics(
        val side: EyeSide,
        val irisCenter: Point3,
        val eyeCenter: Point3,
        val outerCorner: Point3,
        val innerCorner: Point3,
        val eyeWidth: Float,
        val eyeHeight: Float,
        val irisOffsetX: Float,
        val irisOffsetY: Float,
        val noseOffsetX: Float,
        val noseOffsetY: Float,
    )

    data class Point3(
        val x: Float,
        val y: Float,
        val z: Float,
    )

    interface Listener {
        fun onFaceFrame(frame: FaceMeshFrame)
        fun onNoFaceDetected()
        fun onError(message: String)
    }

    private operator fun Point3.minus(other: Point3): Point3 =
        Point3(x - other.x, y - other.y, z - other.z)

    private operator fun Point3.times(scale: Float): Point3 =
        Point3(x * scale, y * scale, z * scale)

    private fun Point3.dot(other: Point3): Float =
        (x * other.x) + (y * other.y) + (z * other.z)

    private fun Point3.magnitude(): Float = sqrt(dot(this))

    private fun Point3.normalizedOrNull(): Point3? {
        val length = magnitude()
        if (length < EPSILON) {
            return null
        }
        return Point3(x / length, y / length, z / length)
    }

    private companion object {
        private const val MODEL_ASSET_PATH = "face_landmarker.task"
        private const val MIN_FRAME_INTERVAL_MS = 33L
        private const val EPSILON = 1.0e-6f
        private const val NOSE_TIP_INDEX = 1

        private const val RIGHT_OUTER_CORNER = 33
        private const val RIGHT_INNER_CORNER = 133
        private val RIGHT_IRIS_INDICES = (473..477).toList()
        private val RIGHT_UPPER_LID_INDICES = listOf(246, 161, 160, 159, 158, 157, 173)
        private val RIGHT_LOWER_LID_INDICES = listOf(7, 163, 144, 145, 153, 154, 155)

        private const val LEFT_OUTER_CORNER = 263
        private const val LEFT_INNER_CORNER = 362
        private val LEFT_IRIS_INDICES = (468..472).toList()
        private val LEFT_UPPER_LID_INDICES = listOf(466, 388, 387, 386, 385, 384, 398)
        private val LEFT_LOWER_LID_INDICES = listOf(249, 390, 373, 374, 380, 381, 382)
    }
}
