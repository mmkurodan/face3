package com.micklab.face3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.micklab.face3.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), FaceMeshProcessor.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var faceMeshProcessor: FaceMeshProcessor

    private val calibrationManager = CalibrationManager()
    private val gazeEstimator = GazeEstimator()

    private var latestFrame: FaceMeshProcessor.FaceMeshFrame? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var hasRequestedPermission = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showCameraUi()
                binding.previewView.post { startCamera() }
            } else {
                showPermissionUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()
        faceMeshProcessor = FaceMeshProcessor(
            context = applicationContext,
            listener = this,
        )

        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        binding.previewView.scaleX = -1f

        binding.permissionButton.setOnClickListener {
            if (shouldOpenAppSettings()) {
                openAppSettings()
            } else {
                hasRequestedPermission = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.settingsPanel.calibrationButton.setOnClickListener {
            calibrateFromLatestFrame()
        }
        binding.settingsPanel.horizontalSensitivitySlider.addOnChangeListener { _, _, _ ->
            updateSensitivityLabels()
        }
        binding.settingsPanel.verticalSensitivitySlider.addOnChangeListener { _, _, _ ->
            updateSensitivityLabels()
        }
        binding.settingsPanel.cursorThresholdSlider.addOnChangeListener { _, value, _ ->
            binding.cursorOverlayView.setCursorMovementThreshold(value)
            updateSensitivityLabels()
        }
        updateSensitivityLabels()

        if (hasCameraPermission()) {
            showCameraUi()
        } else {
            showPermissionUi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            showCameraUi()
            if (cameraProvider == null) {
                binding.previewView.post { startCamera() }
            }
        } else {
            showPermissionUi()
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        faceMeshProcessor.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onFaceFrame(frame: FaceMeshProcessor.FaceMeshFrame) {
        runOnUiThread {
            latestFrame = frame
            val estimate = gazeEstimator.estimate(
                frame = frame,
                calibrationManager = calibrationManager,
                horizontalSensitivity = binding.settingsPanel.horizontalSensitivitySlider.value,
                verticalSensitivity = binding.settingsPanel.verticalSensitivitySlider.value,
            )
            val trackedPoint = trackedPoint(frame, estimate)
            if (trackedPoint == null) {
                binding.faceMarkerOverlayView.clearMarkers()
            } else {
                binding.faceMarkerOverlayView.setMarkers(
                    referencePoint = frame.noseTip,
                    trackedPoint = trackedPoint,
                    sourceWidth = frame.displayWidth,
                    sourceHeight = frame.displayHeight,
                    rotationDegrees = frame.rotationDegrees,
                )
            }

            if (estimate == null) {
                binding.cursorOverlayView.centerCursor()
                updateStatus(
                    status = if (calibrationManager.isCalibrated()) {
                        getString(R.string.status_camera_ready)
                    } else {
                        getString(R.string.status_calibration_required)
                    },
                    details = buildDetailsText(frame = frame, estimate = null),
                )
            } else {
                binding.cursorOverlayView.setCursorOffsetNormalized(
                    x = estimate.offsetX,
                    y = estimate.offsetY,
                    calibrationDistance = estimate.calibrationDistance,
                )
                updateStatus(
                    status = getString(R.string.status_tracking, estimate.trackingMode.label),
                    details = buildDetailsText(frame = frame, estimate = estimate),
                )
            }
        }
    }

    override fun onNoFaceDetected() {
        runOnUiThread {
            latestFrame = null
            binding.faceMarkerOverlayView.clearMarkers()
            binding.cursorOverlayView.centerCursor()
            updateStatus(
                status = getString(R.string.status_no_face),
                details = if (calibrationManager.isCalibrated()) {
                    getString(R.string.details_keep_pose)
                } else {
                    getString(R.string.details_calibration_help)
                },
            )
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            binding.faceMarkerOverlayView.clearMarkers()
            binding.cursorOverlayView.centerCursor()
            updateStatus(
                status = getString(R.string.status_error, message),
                details = getString(R.string.details_calibration_help),
            )
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun calibrateFromLatestFrame() {
        val frame = latestFrame
        if (frame == null) {
            updateStatus(
                status = getString(R.string.status_calibration_face_needed),
                details = getString(R.string.details_calibration_help),
            )
            return
        }

        val snapshot = calibrationManager.calibrate(frame)
        if (snapshot == null) {
            updateStatus(
                status = getString(R.string.status_calibration_face_needed),
                details = getString(R.string.details_calibration_help),
            )
            return
        }

        gazeEstimator.reset()
        binding.cursorOverlayView.centerCursor()
        updateStatus(
            status = getString(R.string.status_calibrated, calibrationTrackingLabel(snapshot)),
            details = buildCalibrationText(snapshot),
        )
    }

    private fun startCamera() {
        cameraProvider?.let {
            bindUseCases(it)
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                cameraProvider = provider

                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    onError("Front camera is not available on this device.")
                    return@addListener
                }

                bindUseCases(provider)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(binding.previewView.display.rotation)
            .setTargetFrameRate(Range(15, 30))
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(binding.previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackgroundExecutor(analysisExecutor)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    faceMeshProcessor.process(imageProxy)
                }
            }

        provider.unbindAll()
        provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            imageAnalysis,
        )

        updateStatus(
            status = if (calibrationManager.isCalibrated()) {
                getString(R.string.status_camera_ready)
            } else {
                getString(R.string.status_calibration_required)
            },
            details = if (calibrationManager.isCalibrated()) {
                getString(R.string.details_keep_pose)
            } else {
                getString(R.string.details_calibration_help)
            },
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldOpenAppSettings(): Boolean {
        return hasRequestedPermission &&
            !hasCameraPermission() &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
    }

    private fun showPermissionUi() {
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider = null
        latestFrame = null
        gazeEstimator.reset()
        binding.faceMarkerOverlayView.clearMarkers()
        binding.cursorOverlayView.centerCursor()
        binding.permissionButton.visibility = android.view.View.VISIBLE
        binding.permissionButton.text = getString(
            if (shouldOpenAppSettings()) R.string.open_app_settings
            else R.string.grant_camera_permission,
        )
        updateStatus(
            status = getString(R.string.status_permission_required),
            details = getString(R.string.details_permission_help),
        )
    }

    private fun showCameraUi() {
        binding.permissionButton.visibility = android.view.View.GONE
        updateStatus(
            status = getString(R.string.status_initializing),
            details = if (calibrationManager.isCalibrated()) {
                getString(R.string.details_keep_pose)
            } else {
                getString(R.string.details_calibration_help)
            },
        )
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }

    private fun updateSensitivityLabels() {
        binding.settingsPanel.horizontalValueTextView.text = getString(
            R.string.sensitivity_value,
            binding.settingsPanel.horizontalSensitivitySlider.value,
        )
        binding.settingsPanel.verticalValueTextView.text = getString(
            R.string.sensitivity_value,
            binding.settingsPanel.verticalSensitivitySlider.value,
        )
        binding.settingsPanel.cursorThresholdValueTextView.text = String.format(
            Locale.US,
            "%.2f",
            binding.settingsPanel.cursorThresholdSlider.value,
        )
    }

    private fun updateStatus(status: String, details: String) {
        binding.settingsPanel.statusTextView.text = status
        binding.settingsPanel.detailsTextView.text = details
    }

    private fun buildCalibrationText(snapshot: CalibrationManager.CalibrationState): String {
        val right = snapshot.eyes[FaceMeshProcessor.EyeSide.RIGHT]
        val left = snapshot.eyes[FaceMeshProcessor.EyeSide.LEFT]
        return buildString {
            append(
                String.format(
                    Locale.US,
                    "yaw=%+.3f  pitch=%+.3f  distance=%.3f",
                    snapshot.faceYaw,
                    snapshot.facePitch,
                    snapshot.distanceReference,
                ),
            )
            if (right != null || left != null) {
                appendLine()
                append(
                    String.format(
                        Locale.US,
                        "right=%.4f  left=%.4f",
                        right?.eyeWidth ?: 0f,
                        left?.eyeWidth ?: 0f,
                    ),
                )
            }
        }
    }

    private fun buildDetailsText(
        frame: FaceMeshProcessor.FaceMeshFrame,
        estimate: GazeEstimator.GazeEstimate?,
    ): String {
        val eyes = trackedEyes(frame, estimate)
        if (eyes.isEmpty()) {
            return getString(R.string.details_waiting)
        }

        val averageWidth = eyes.map { it.eyeWidth }.average().toFloat()
        val trackingLabel = trackingLabel(frame, estimate)
        val distanceScale = estimate?.distanceScale ?: 1f
        return buildString {
            append(
                String.format(
                    Locale.US,
                    "track=%s  width=%.4f  infer=%dms",
                    trackingLabel,
                    averageWidth,
                    frame.inferenceTimeMs,
                ),
            )
            appendLine()
            append(
                String.format(
                    Locale.US,
                    "yaw=%+.3f  pitch=%+.3f  distance=%.2fx",
                    estimate?.poseYawDelta ?: frame.faceYaw,
                    estimate?.posePitchDelta ?: frame.facePitch,
                    distanceScale,
                ),
            )
        }
    }

    private fun trackedEye(
        frame: FaceMeshProcessor.FaceMeshFrame,
        estimate: GazeEstimator.GazeEstimate?,
    ): FaceMeshProcessor.EyeMetrics? = trackedEyes(frame, estimate).firstOrNull()

    private fun trackedEyes(
        frame: FaceMeshProcessor.FaceMeshFrame,
        estimate: GazeEstimator.GazeEstimate?,
    ): List<FaceMeshProcessor.EyeMetrics> {
        return when (estimate?.trackingMode) {
            GazeEstimator.TrackingMode.BOTH -> frame.visibleEyes()
            GazeEstimator.TrackingMode.RIGHT -> listOfNotNull(frame.eyeFor(FaceMeshProcessor.EyeSide.RIGHT))
            GazeEstimator.TrackingMode.LEFT -> listOfNotNull(frame.eyeFor(FaceMeshProcessor.EyeSide.LEFT))
            null -> frame.visibleEyes()
        }
    }

    private fun trackedPoint(
        frame: FaceMeshProcessor.FaceMeshFrame,
        estimate: GazeEstimator.GazeEstimate?,
    ): FaceMeshProcessor.Point3? {
        val eyes = trackedEyes(frame, estimate)
        if (eyes.isEmpty()) {
            return null
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

    private fun trackingLabel(
        frame: FaceMeshProcessor.FaceMeshFrame,
        estimate: GazeEstimator.GazeEstimate?,
    ): String {
        return estimate?.trackingMode?.label ?: when (trackedEyes(frame, estimate).size) {
            0 -> getString(R.string.tracking_mode_waiting)
            1 -> trackedEye(frame, estimate)?.side?.label ?: getString(R.string.tracking_mode_waiting)
            else -> getString(R.string.tracking_mode_both)
        }
    }

    private fun calibrationTrackingLabel(snapshot: CalibrationManager.CalibrationState): String {
        return if (snapshot.eyes.size >= 2) {
            getString(R.string.tracking_mode_both)
        } else {
            snapshot.preferredEye.label
        }
    }
}
