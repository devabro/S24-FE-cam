package com.s24fe.nativecamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.s24fe.nativecamera.camera.CameraEnumerator
import com.s24fe.nativecamera.camera.CameraSession
import com.s24fe.nativecamera.camera.LensInfo
import com.s24fe.nativecamera.camera.ManualSettings
import com.s24fe.nativecamera.camera.Preset
import com.s24fe.nativecamera.camera.PresetStore
import com.s24fe.nativecamera.camera.PreviewAnalyzer
import com.s24fe.nativecamera.camera.VideoRecorder
import com.s24fe.nativecamera.ui.HistogramView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-screen camera. The layout is intentionally plain: preview on top,
 * controls below in a scroll view. Every control does something real.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var session: CameraSession? = null
    private var analyzer: PreviewAnalyzer? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var lenses: List<LensInfo> = emptyList()
    private var currentLens: LensInfo? = null
    private var settings = ManualSettings()
    private var surfaceReady = false
    private var videoFile: File? = null

    // Views
    private lateinit var textureView: TextureView
    private lateinit var zebraOverlay: ImageView
    private lateinit var histogramView: HistogramView
    private lateinit var lensContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var rawNoteText: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnRecord: Button
    private lateinit var switchNatural: Switch
    private lateinit var switchLog: Switch
    private lateinit var logNoteText: TextView
    private lateinit var seekIso: SeekBar
    private lateinit var tvIso: TextView
    private lateinit var seekShutter: SeekBar
    private lateinit var tvShutter: TextView
    private lateinit var seekFocus: SeekBar
    private lateinit var btnFocusAuto: Button
    private lateinit var spinnerWb: Spinner
    private lateinit var seekZebra: SeekBar
    private lateinit var tvZebra: TextView
    private lateinit var etPresetName: EditText
    private lateinit var spinnerPresets: Spinner

    private val wbModes = listOf(
        "Auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
        "Incandescent" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        "Fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
        "Daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
        "Cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        "Shade" to CaptureRequest.CONTROL_AWB_MODE_SHADE,
        "Twilight" to CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                startCamera()
            }

            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                surfaceReady = false
                return true
            }

            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }

        btnCapture.setOnClickListener { session?.capturePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        switchNatural.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(naturalOn = checked)
            session?.applySettings(settings)
        }
        switchLog.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(logOn = checked)
            status("LOG ${if (checked) "on — 10-bit HLG, grade it in editing" else "off"}")
        }
        findViewById<Button>(R.id.btn_auto_reset).setOnClickListener {
            settings = settings.copy(
                isoIndex = -1, shutterIndex = -1, focusProgress = -1,
                awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            syncUiFromSettings()
            session?.applySettings(settings)
            status("Back to auto")
        }
        btnFocusAuto.setOnClickListener {
            settings = settings.copy(focusProgress = -1)
            session?.applySettings(settings)
            status("Autofocus on")
        }

        setupSliders()
        setupWbSpinner()
        setupPresets()

        if (hasPermissions()) {
            discoverLenses()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_PERMISSIONS
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (surfaceReady) startCamera()
    }

    override fun onPause() {
        releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        backgroundThread.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            discoverLenses()
        } else {
            status("Camera + mic permission needed")
        }
    }

    // ------------------------------------------------------------------
    // Camera setup
    // ------------------------------------------------------------------

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun discoverLenses() {
        lenses = CameraEnumerator.enumerateBackCameras(cameraManager)
        if (lenses.isEmpty()) {
            status("No back camera found")
            return
        }
        lensContainer.removeAllViews()
        for (lens in lenses) {
            val button = Button(this).apply {
                text = lens.label
                setOnClickListener { switchLens(lens) }
            }
            lensContainer.addView(button)
        }
        // Default to the main lens (middle focal length), else the first one.
        currentLens = lenses.firstOrNull { it.label == "Main" } ?: lenses.first()
        if (surfaceReady) startCamera()
    }

    private fun switchLens(lens: LensInfo) {
        if (lens.cameraId == currentLens?.cameraId) return
        currentLens = lens
        if (surfaceReady) startCamera()
    }

    private fun startCamera() {
        val lens = currentLens ?: return
        val texture = textureView.surfaceTexture ?: return
        releaseCameraSessionOnly()

        val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = choosePreviewSize(map)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width * it.height } ?: previewSize
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        jpegReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val file = newMediaFile("Pictures", "IMG", ".jpg")
                file.writeBytes(bytes)
                scanFile(file)
                runOnUiThread { status("Saved ${file.name}") }
            } finally {
                image.close()
            }
        }, backgroundHandler)

        if (lens.supportsRaw) {
            val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width * it.height }
            if (rawSize != null) {
                rawReader = ImageReader.newInstance(
                    rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2
                )
                rawReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val file = newMediaFile("Pictures", "IMG", ".dng")
                        file.writeBytes(bytes)
                        scanFile(file)
                    } finally {
                        image.close()
                    }
                }, backgroundHandler)
                rawNoteText.text = "RAW (DNG) will save next to each JPEG"
            } else {
                rawNoteText.text = "RAW reported but no RAW size — JPEG only"
            }
        } else {
            rawNoteText.text = "This lens does not report RAW support — JPEG only"
        }

        val analyzerSize = chooseAnalyzerSize(map)
        analyzer = PreviewAnalyzer(analyzerSize.width, analyzerSize.height, backgroundHandler) { histogram, zebra ->
            // The analyzer runs on the background thread; views must be touched on the main thread.
            runOnUiThread {
                histogramView.setHistogram(histogram)
                zebraOverlay.setImageBitmap(zebra)
            }
        }.also { it.threshold = settings.zebraThreshold }

        val sess = CameraSession(this, cameraManager, backgroundHandler)
        sess.onError = { message -> runOnUiThread { status(message) } }
        session = sess
        sess.start(lens, previewSurface, analyzer!!.reader.surface, jpegReader!!, rawReader)

        // LOG toggle only exists when this lens actually supports 10-bit.
        if (lens.supports10Bit) {
            switchLog.visibility = View.VISIBLE
            logNoteText.visibility = View.VISIBLE
            logNoteText.text = "LOG = 10-bit HLG. Flat file for grading — LUTs are applied in editing (v2: load .cube)."
        } else {
            switchLog.visibility = View.GONE
            logNoteText.visibility = View.VISIBLE
            logNoteText.text = "LOG hidden: this lens does not report 10-bit support."
            settings = settings.copy(logOn = false)
            switchLog.isChecked = false
        }
        sess.applySettings(settings)
        status("Lens: ${lens.label}")
    }

    private fun releaseCameraSessionOnly() {
        session?.stop()
        session = null
        analyzer?.close()
        analyzer = null
        try {
            jpegReader?.close()
        } catch (ignored: Exception) {
        }
        try {
            rawReader?.close()
        } catch (ignored: Exception) {
        }
        jpegReader = null
        rawReader = null
    }

    private fun releaseCamera() {
        if (session?.isRecording() == true) {
            session?.stopRecording()
            btnRecord.text = "Record video"
        }
        releaseCameraSessionOnly()
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private fun toggleRecording() {
        val sess = session ?: return
        val lens = currentLens ?: return
        if (sess.isRecording()) {
            sess.stopRecording()
            btnRecord.text = "Record video"
            videoFile?.let { scanFile(it) }
            status("Video saved")
            return
        }
        val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSize = chooseVideoSize(map)
        val file = newMediaFile("Movies", "VID", ".mp4")
        videoFile = file
        val useHlg = settings.logOn && lens.supports10Bit
        sess.startRecording(VideoRecorder(file, videoSize.width, videoSize.height, useHlg))
        btnRecord.text = "Stop recording"
        status(if (useHlg) "Recording LOG (10-bit HLG)" else "Recording")
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    private fun setupSliders() {
        // Progress 0 = auto, 1..N = value index + 1. Keeps "auto" one swipe away.
        seekIso.max = ManualSettings.ISO_VALUES.size
        seekIso.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings = settings.copy(isoIndex = if (progress == 0) -1 else progress - 1)
                tvIso.text = settings.isoValue?.let { "ISO $it" } ?: "ISO auto"
                session?.applySettings(settings)
            }
        })
        seekShutter.max = ManualSettings.SHUTTER_VALUES_NS.size
        seekShutter.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings = settings.copy(shutterIndex = if (progress == 0) -1 else progress - 1)
                tvShutter.text = settings.shutterNs?.let { ManualSettings.shutterLabel(it) } ?: "Shutter auto"
                session?.applySettings(settings)
            }
        })
        seekFocus.max = 100
        seekFocus.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings = settings.copy(focusProgress = progress)
                session?.applySettings(settings)
            }
        })
        seekZebra.max = 255
        seekZebra.progress = settings.zebraThreshold
        seekZebra.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                settings = settings.copy(zebraThreshold = progress)
                analyzer?.threshold = progress
                tvZebra.text = "Zebra $progress"
            }
        })
    }

    private fun setupWbSpinner() {
        spinnerWb.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, wbModes.map { it.first }
        )
        spinnerWb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                settings = settings.copy(awbMode = wbModes[position].second)
                session?.applySettings(settings)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setupPresets() {
        refreshPresetSpinner()
        findViewById<Button>(R.id.btn_preset_save).setOnClickListener {
            val name = etPresetName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Type a preset name first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val lensId = currentLens?.cameraId ?: return@setOnClickListener
            PresetStore.save(this, Preset(name, settings.copy(), lensId))
            refreshPresetSpinner()
            status("Preset saved: $name")
        }
        findViewById<Button>(R.id.btn_preset_load).setOnClickListener {
            val all = PresetStore.loadAll(this)
            val position = spinnerPresets.selectedItemPosition
            val preset = all.getOrNull(position) ?: return@setOnClickListener
            settings = preset.settings.copy()
            syncUiFromSettings()
            val lens = lenses.firstOrNull { it.cameraId == preset.lensId }
            if (lens != null && lens.cameraId != currentLens?.cameraId) {
                currentLens = lens
                if (surfaceReady) startCamera()
            } else {
                session?.applySettings(settings)
            }
            status("Preset loaded: ${preset.name}")
        }
    }

    private fun refreshPresetSpinner() {
        val names = PresetStore.loadAll(this).map { it.name }
        spinnerPresets.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            names.ifEmpty { listOf("(no presets yet)") }
        )
    }

    private fun syncUiFromSettings() {
        seekIso.progress = if (settings.isoIndex < 0) 0 else settings.isoIndex + 1
        tvIso.text = settings.isoValue?.let { "ISO $it" } ?: "ISO auto"
        seekShutter.progress = if (settings.shutterIndex < 0) 0 else settings.shutterIndex + 1
        tvShutter.text = settings.shutterNs?.let { ManualSettings.shutterLabel(it) } ?: "Shutter auto"
        seekFocus.progress = if (settings.focusProgress < 0) 0 else settings.focusProgress
        val wbIndex = wbModes.indexOfFirst { it.second == settings.awbMode }.takeIf { it >= 0 } ?: 0
        spinnerWb.setSelection(wbIndex)
        switchNatural.isChecked = settings.naturalOn
        switchLog.isChecked = settings.logOn
        seekZebra.progress = settings.zebraThreshold
        tvZebra.text = "Zebra ${settings.zebraThreshold}"
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun bindViews() {
        textureView = findViewById(R.id.preview_texture)
        zebraOverlay = findViewById(R.id.zebra_overlay)
        histogramView = findViewById(R.id.histogram_view)
        lensContainer = findViewById(R.id.lens_container)
        statusText = findViewById(R.id.tv_status)
        rawNoteText = findViewById(R.id.tv_raw_note)
        btnCapture = findViewById(R.id.btn_capture)
        btnRecord = findViewById(R.id.btn_record)
        switchNatural = findViewById(R.id.switch_natural)
        switchLog = findViewById(R.id.switch_log)
        logNoteText = findViewById(R.id.tv_log_note)
        seekIso = findViewById(R.id.seek_iso)
        tvIso = findViewById(R.id.tv_iso)
        seekShutter = findViewById(R.id.seek_shutter)
        tvShutter = findViewById(R.id.tv_shutter)
        seekFocus = findViewById(R.id.seek_focus)
        btnFocusAuto = findViewById(R.id.btn_focus_auto)
        spinnerWb = findViewById(R.id.spinner_wb)
        seekZebra = findViewById(R.id.seek_zebra)
        tvZebra = findViewById(R.id.tv_zebra)
        etPresetName = findViewById(R.id.et_preset_name)
        spinnerPresets = findViewById(R.id.spinner_presets)
    }

    private fun status(message: String) {
        statusText.text = message
    }

    private fun newMediaFile(type: String, prefix: String, extension: String): File {
        val base = if (type == "Movies") {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_PICTURES
        }
        val folder = File(getExternalFilesDir(base), "S24FECamera")
        if (!folder.exists()) folder.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(folder, "${prefix}_$stamp$extension")
    }

    private fun scanFile(file: File) {
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
    }

    private fun choosePreviewSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1920, 1080)
        sizes.firstOrNull { it.width == 1920 && it.height == 1080 }?.let { return it }
        return sizes.filter { it.width <= 1920 }
            .maxByOrNull { it.width * it.height } ?: sizes.first()
    }

    private fun chooseVideoSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: return Size(1920, 1080)
        sizes.firstOrNull { it.width == 3840 && it.height == 2160 }?.let { return it }
        return sizes.filter { it.width <= 3840 }
            .maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    private fun chooseAnalyzerSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(320, 240)
        return sizes.filter { it.width >= 320 }
            .minByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height }!!
    }

    // Methods are open: Kotlin makes them final by default, and the slider
    // listeners below override onProgressChanged.
    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        open override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
