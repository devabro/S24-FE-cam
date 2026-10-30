package com.s24fe.nativecamera.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.util.Range
import android.view.Surface
import java.io.File
import java.util.concurrent.Executor

/**
 * Owns one open camera: preview, photo capture and video recording.
 * The session is rebuilt when the target set changes (photo <-> video),
 * which is the normal Camera2 pattern — not a bug.
 */
class CameraSession(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val backgroundHandler: Handler
) {
    var onPhotoSaved: ((File) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var lens: LensInfo? = null
    private var chars: CameraCharacteristics? = null
    private var settings = ManualSettings()

    private var previewSurface: Surface? = null
    private var analyzerSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var recorder: VideoRecorder? = null

    private val executor: Executor = Executor { command -> backgroundHandler.post(command) }

    fun start(
        lens: LensInfo,
        previewSurface: Surface,
        analyzerSurface: Surface,
        jpegReader: ImageReader,
        rawReader: ImageReader?
    ) {
        stop()
        this.lens = lens
        this.previewSurface = previewSurface
        this.analyzerSurface = analyzerSurface
        this.jpegReader = jpegReader
        this.rawReader = rawReader
        try {
            chars = cameraManager.getCameraCharacteristics(lens.cameraId)
            cameraManager.openCamera(lens.cameraId, deviceCallback, backgroundHandler)
        } catch (e: Exception) {
            onError?.invoke("Cannot open camera ${lens.label}: ${e.message}")
        }
    }

    fun stop() {
        try {
            session?.close()
        } catch (ignored: Exception) {
        }
        try {
            device?.close()
        } catch (ignored: Exception) {
        }
        session = null
        device = null
        recorder = null
    }

    fun applySettings(newSettings: ManualSettings) {
        settings = newSettings
        rebuildRepeating()
    }

    fun isRecording(): Boolean = recorder != null

    /** Rebuild the repeating preview/record request with the current settings. */
    private fun rebuildRepeating() {
        val dev = device ?: return
        val sess = session ?: return
        try {
            val template = if (recorder != null) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }
            val builder = dev.createCaptureRequest(template)
            val targets = mutableListOf<Surface>()
            previewSurface?.let { targets.add(it); builder.addTarget(it) }
            analyzerSurface?.let { targets.add(it); builder.addTarget(it) }
            if (recorder != null) {
                builder.addTarget(recorder!!.inputSurface)
            }
            applySettingsTo(builder, isRecord = recorder != null)
            sess.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError?.invoke("Preview failed: ${e.message}")
        }
    }

    fun capturePhoto() {
        val dev = device ?: return
        val sess = session ?: return
        val jpeg = jpegReader ?: return
        try {
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jpeg.surface)
            rawReader?.let { builder.addTarget(it.surface) }
            applySettingsTo(builder, isRecord = false)
            val orientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
            sess.capture(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            onError?.invoke("Capture failed: ${e.message}")
        }
    }

    fun startRecording(recorder: VideoRecorder) {
        this.recorder = recorder
        try {
            recorder.prepare()
        } catch (e: Exception) {
            onError?.invoke("Video encoder failed: ${e.message}")
            this.recorder = null
            return
        }
        recreateSession {
            try {
                recorder.start()
            } catch (e: Exception) {
                onError?.invoke("Recording failed to start: ${e.message}")
            }
        }
    }

    fun stopRecording() {
        val rec = recorder
        recorder = null
        try {
            rec?.stop()
        } catch (ignored: Exception) {
        }
        recreateSession()
    }

    // ------------------------------------------------------------------
    // Session plumbing
    // ------------------------------------------------------------------

    private fun recreateSession(onReady: (() -> Unit)? = null) {
        val dev = device ?: return
        try {
            session?.close()
        } catch (ignored: Exception) {
        }
        session = null
        val outputs = mutableListOf<OutputConfiguration>()
        previewSurface?.let { outputs.add(OutputConfiguration(it)) }
        analyzerSurface?.let { outputs.add(OutputConfiguration(it)) }
        if (recorder != null) {
            outputs.add(OutputConfiguration(recorder!!.inputSurface))
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    rebuildRepeating()
                    onReady?.invoke()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError?.invoke("Camera session failed to configure")
                }
            }
        )
        try {
            dev.createCaptureSession(config)
        } catch (e: CameraAccessException) {
            onError?.invoke("Cannot create session: ${e.message}")
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            recreateSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            onError?.invoke("Camera error $error")
        }
    }

    // ------------------------------------------------------------------
    // One place where every user setting lands on a request builder.
    // ------------------------------------------------------------------

    private fun applySettingsTo(builder: CaptureRequest.Builder, isRecord: Boolean) {
        // Natural mode: no forced sharpening, no forced noise smoothing.
        if (settings.naturalOn) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        } else {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        }

        builder.set(CaptureRequest.CONTROL_AWB_MODE, settings.awbMode)

        if (settings.isManualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val range: Range<Int>? =
                chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val iso = settings.isoValue ?: 100
            val clamped = if (range != null) iso.coerceIn(range.lower, range.upper) else iso
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, clamped)
            // AE off needs an explicit exposure time even if the user only moved ISO.
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutterNs ?: 16_666_666L)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        if (settings.isManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            val minFocus =
                chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            // Slider 0 = infinity (0 diopters), 100 = closest focus.
            val distance = if (minFocus > 0f) minFocus * settings.focusProgress / 100f else 0f
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        } else {
            val afMode = if (isRecord) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        }
    }
}
