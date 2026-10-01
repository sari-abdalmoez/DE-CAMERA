package com.sari.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var preview: TextureView
    private lateinit var shutter: ImageButton
    private lateinit var capabilityText: TextView
    private lateinit var thermalText: TextView
    private lateinit var processingText: TextView
    private lateinit var statusPill: TextView
    private lateinit var focusView: View
    private lateinit var modeBar: LinearLayout
    private lateinit var zoomBar: LinearLayout
    private lateinit var flashButton: ImageButton
    private lateinit var switchCameraButton: ImageButton

    private lateinit var manager: CameraManager
    private val cameraThread = HandlerThread("SARI-Camera", android.os.Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler
    private val processingExecutor = Executors.newSingleThreadExecutor()

    private var cameraId = ""
    private var backCameraId: String? = null
    private var frontCameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rawSupported = false
    private var currentSessionHasRaw = false

    private var mode = "PHOTO"
    private var currentZoom = 1f
    private var flashMode = FLASH_AUTO
    private var lastTapX = 0.5f
    private var lastTapY = 0.5f
    private var hasFocusPoint = false
    private var lastResult: TotalCaptureResult? = null
    private var pendingRawSave = false

    private lateinit var controller: MemoryThermalController
    private lateinit var ai: AiEngine
    private val captureBusy = AtomicBoolean(false)

    private var burstMode = false
    private var burstType = ""
    private var burstRemaining = 0
    private var burstTotal = 0
    private var burstZoom = 1f
    private var burstFiles = mutableListOf<File>()
    private var astroDeadline = 0L
    private var astroRawSaved = false
    private fun astroDurationMs(): Long = getSharedPreferences("settings", 0).getInt("astroMinutes", 4).coerceIn(1, 8) * 60_000L

    private var mediaRecorder: MediaRecorder? = null
    private var videoSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var videoUri: Uri? = null
    private var videoPfd: android.os.ParcelFileDescriptor? = null
    private var videoRecording = false

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        runOnUiThread {
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                thermalText.text = "THERMAL • LOW POWER"
            } else if (ai.ncnnVulkanAvailable) {
                thermalText.text = if (ai.ncnnAvailable) "AI • VULKAN" else "AI • CPU"
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraOk = grants[Manifest.permission.CAMERA] == true
        val storageOk = Build.VERSION.SDK_INT >= 29 || grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        if (cameraOk && storageOk && preview.isAvailable) openCamera()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(this, false)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        shutter = findViewById(R.id.shutter)
        capabilityText = findViewById(R.id.capabilityText)
        thermalText = findViewById(R.id.thermalText)
        processingText = findViewById(R.id.processingText)
        statusPill = findViewById(R.id.statusPill)
        focusView = findViewById(R.id.focusView)
        modeBar = findViewById(R.id.modeBar)
        zoomBar = findViewById(R.id.zoomBar)
        flashButton = findViewById(R.id.flashButton)
        switchCameraButton = findViewById(R.id.switchCamera)

        manager = getSystemService(CameraManager::class.java)
        controller = MemoryThermalController(this)
        ai = AiEngine(this)

        cameraThread.start()
        handler = Handler(cameraThread.looper)

        setupModes()
        setupZoom()
        setupControls()
        setupGestures()
        loadAiAsync()

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (hasCameraPermission()) openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener)
        }
        if (!hasCameraPermission()) permissionLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT <= 28) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.CAMERA)
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.gallery).setOnClickListener {
            startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }
        findViewById<ImageButton>(R.id.settings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        switchCameraButton.setOnClickListener { switchCamera() }
        flashButton.setOnClickListener { cycleFlash() }
        shutter.setOnClickListener { capture() }
    }

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(currentZoom * detector.scaleFactor)
                return true
            }
        })
        preview.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleDetector.isInProgress) {
                focusAt(event.x, event.y)
            }
            true
        }
    }

    private fun loadAiAsync() {
        Thread {
            try {
                val dir = File(filesDir, "models").apply { mkdirs() }
                copyAssetIfPresent("real_esrgan_x2.onnx", File(dir, "real_esrgan_x2.onnx"))
                copyAssetIfPresent("deblurring_nafnet_2025may.onnx", File(dir, "deblurring_nafnet_2025may.onnx"))
                copyAssetIfPresent("realesrgan-x4plus.param", File(dir, "realesrgan-x4plus.param"))
                copyAssetIfPresent("realesrgan-x4plus.bin", File(dir, "realesrgan-x4plus.bin"))
                ai.loadModels(
                    File(dir, "real_esrgan_x2.onnx"),
                    File(dir, "deblurring_nafnet_2025may.onnx"),
                    File(dir, "realesrgan-x4plus.param"),
                    File(dir, "realesrgan-x4plus.bin")
                )
            } catch (_: Throwable) { }
            runOnUiThread {
                thermalText.text = when {
                    ai.ncnnAvailable && ai.ncnnVulkanAvailable -> "AI • VULKAN"
                    ai.ncnnAvailable -> "AI • NCNN CPU"
                    ai.superResolutionAvailable || ai.deblurAvailable -> "AI • ONNX"
                    else -> "AI • FALLBACK"
                }
            }
        }.start()
    }

    private fun copyAssetIfPresent(name: String, target: File) {
        if (target.isFile && target.length() > 1024) return
        val exists = assets.list("models")?.contains(name) == true
        if (!exists) return
        assets.open("models/$name").use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    private fun setupModes() {
        modeBar.removeAllViews()
        listOf("PHOTO", "NIGHT", "ASTRO", "PRO", "RAW", "VIDEO").forEach { label ->
            val v = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                isSingleLine = true
                setOnClickListener { selectMode(label) }
                tag = label
                layoutParams = LinearLayout.LayoutParams(-2, dp(42))
            }
            modeBar.addView(v)
        }
        updateModeUi()
    }

    private fun setupZoom() {
        zoomBar.removeAllViews()
        listOf(1f, 2f, 5f, 6f, 10f, 13f, 20f).forEach { zoom ->
            val v = TextView(this).apply {
                text = if (zoom < 1f) "0.6×" else "${zoom.toInt()}×"
                textSize = if (zoom == 1f) 14f else 12f
                gravity = android.view.Gravity.CENTER
                minWidth = dp(if (zoom == 1f) 52 else 46)
                setOnClickListener { setZoom(zoom) }
                tag = zoom
            }
            zoomBar.addView(v)
        }
        updateZoomUi()
    }

    private fun selectMode(label: String) {
        if (videoRecording && label != "VIDEO") stopVideo()
        mode = label
        burstMode = false
        burstType = ""
        burstRemaining = 0
        astroRawSaved = false
        statusPill.text = when (label) {
            "ASTRO" -> {
                val minutes = getSharedPreferences("settings", 0).getInt("astroMinutes", 4).coerceIn(1, 8)
                "ASTRO • ${minutes}:00"
            }
            "NIGHT" -> "NIGHT • MULTI-FRAME"
            "PRO" -> "PRO"
            "RAW" -> "RAW DNG"
            else -> label
        }
        updateModeUi()
        if (label == "VIDEO") {
            switchToVideo()
        } else {
            switchToPhotoSession(includeRaw = label == "RAW" || label == "ASTRO")
        }
    }

    private fun updateModeUi() {
        for (i in 0 until modeBar.childCount) {
            val v = modeBar.getChildAt(i) as TextView
            val selected = v.tag == mode
            v.setTextColor(if (selected) ContextCompat.getColor(this, R.color.sari_gold) else ContextCompat.getColor(this, R.color.sari_white))
            v.alpha = if (selected) 1f else 0.66f
        }
    }

    private fun updateZoomUi() {
        for (i in 0 until zoomBar.childCount) {
            val v = zoomBar.getChildAt(i) as TextView
            val z = (v.tag as Float)
            val selected = kotlin.math.abs(currentZoom - z) < 0.25f
            v.setTextColor(if (selected) ContextCompat.getColor(this, R.color.sari_black) else ContextCompat.getColor(this, R.color.sari_white))
            v.background = rounded(selected)
        }
    }

    private fun setZoom(requested: Float) {
        val minZoom = 1f
        currentZoom = requested.coerceIn(minZoom, 20f)
        updateZoomUi()
        applyPreviewSoftwareZoom()
        updatePreviewRepeating()
        statusPill.text = when {
            mode == "ASTRO" -> "ASTRO • ${getSharedPreferences("settings", 0).getInt("astroMinutes", 4).coerceIn(1, 8)}:00"
            currentZoom >= 13f -> String.format(Locale.US, "%.1fx • AI DETAIL", currentZoom)
            currentZoom >= 6f -> String.format(Locale.US, "%.1fx • AI SUPER RES", currentZoom)
            else -> String.format(Locale.US, "%.1fx", currentZoom)
        }
    }

    private fun cycleFlash() {
        val hasFlash = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (!hasFlash) {
            flashMode = FLASH_OFF
        } else {
            flashMode = when (flashMode) {
                FLASH_OFF -> FLASH_AUTO
                FLASH_AUTO -> FLASH_ON
                else -> FLASH_OFF
            }
        }
        flashButton.setImageResource(
            when (flashMode) {
                FLASH_ON -> R.drawable.ic_flash
                FLASH_AUTO -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
        )
    }

    private fun openCamera() {
        if (!hasCameraPermission()) return
        try {
            val caps = CapabilityDetector.detect(manager)
            backCameraId = caps.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK }?.cameraId
            frontCameraId = caps.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }?.cameraId
            val selected = cameraId.takeIf { it.isNotBlank() } ?: backCameraId ?: caps.firstOrNull()?.cameraId ?: return
            openCameraId(selected)
        } catch (e: Throwable) {
            Toast.makeText(this, "Camera unavailable: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openCameraId(id: String) {
        if (!hasCameraPermission()) return
        cameraId = id
        characteristics = manager.getCameraCharacteristics(id)
        rawSupported = capabilitiesForCurrent().raw
        capabilityText.text = buildString {
            append(if (cameraIsBack()) "BACK" else "FRONT")
            append(" • RAW ").append(rawSupported)
            if (capabilitiesForCurrent().ois) append(" • OIS")
            if (capabilitiesForCurrent().eis) append(" • EIS")
        }
        manager.openCamera(id, stateCallback, handler)
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            prepareReaders()
            switchToPhotoSession(includeRaw = mode == "RAW" || mode == "ASTRO")
        }
        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) device = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (device === camera) device = null
            runOnUiThread { Toast.makeText(this@MainActivity, "Camera error $error", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun prepareReaders() {
        val c = characteristics ?: return
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val jpegSize = chooseJpegSize(map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty())
        jpegReader?.close()
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, android.graphics.ImageFormat.JPEG, 3).also {
            it.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.let(::onJpeg) }, handler)
        }
        rawReader?.close()
        rawReader = null
        if (rawSupported) {
            val rawSize = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            if (rawSize != null) {
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, android.graphics.ImageFormat.RAW_SENSOR, 2).also {
                    it.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.let(::onRaw) }, handler)
                }
            }
        }
    }

    private fun chooseJpegSize(sizes: List<android.util.Size>): android.util.Size {
        val safe = sizes.filter { it.width.toLong() * it.height <= 16_000_000L && it.width <= 4096 }
        return (safe.maxByOrNull { it.width.toLong() * it.height } ?: sizes.maxByOrNull { it.width.toLong() * it.height }
            ?: android.util.Size(1920, 1080))
    }

    private fun switchToPhotoSession(includeRaw: Boolean) {
        val d = device ?: return
        val texture = preview.surfaceTexture ?: return
        val jpeg = jpegReader ?: return
        currentSessionHasRaw = includeRaw && rawReader != null
        session?.close()
        session = null
        previewSurface?.release()
        previewSurface = Surface(texture)
        val previewOut = previewSurface!!
        val surfaces = mutableListOf(previewOut, jpeg.surface)
        if (currentSessionHasRaw) rawReader?.let { surfaces.add(it.surface) }

        try {
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    updatePreviewRepeating()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (includeRaw) {
                        currentSessionHasRaw = false
                        runOnUiThread { Toast.makeText(this@MainActivity, "RAW session unavailable; continuing without RAW", Toast.LENGTH_SHORT).show() }
                        handler.post { switchToPhotoSession(false) }
                    } else {
                        runOnUiThread { Toast.makeText(this@MainActivity, "Camera configuration failed for $mode", Toast.LENGTH_LONG).show() }
                    }
                }
            }, handler)
        } catch (e: Throwable) {
            runOnUiThread { Toast.makeText(this, "Camera session failed: ${e.message ?: "unsupported"}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun updatePreviewRepeating() {
        val d = device ?: return
        val s = session ?: return
        val texture = preview.surfaceTexture ?: return
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(Surface(texture))
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, supportedAfMode(true))
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.SCALER_CROP_REGION, currentCrop())
                applyMetering(this)
            }
            s.setRepeatingRequest(req.build(), null, handler)
        } catch (_: Throwable) { }
    }

    private fun switchToVideo() {
        val d = device ?: return
        val texture = preview.surfaceTexture ?: return
        try {
            session?.close(); session = null
            mediaRecorder?.release()
            val recorder = MediaRecorder()
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "SARI_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SARI Camera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("Output unavailable")
            videoPfd = pfd
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setVideoFrameRate(30)
            val videoSizes = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            val videoSize = videoSizes.filter { it.width <= 1920 && it.height <= 1080 }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: android.util.Size(1280, 720)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.prepare()
            mediaRecorder = recorder
            videoUri = uri
            videoSurface = recorder.surface

            previewSurface?.release()
            previewSurface = Surface(texture)
            val previewOut = previewSurface!!
            d.createCaptureSession(listOf(previewOut, recorder.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewOut)
                        addTarget(recorder.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, supportedAfMode(false))
                        set(CaptureRequest.SCALER_CROP_REGION, currentCrop())
                        val stabilizationEnabled = getSharedPreferences("settings", 0).getBoolean("stabilization", true)
                        if (stabilizationEnabled) {
                            val modes = characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
                            if (modes.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                            }
                        }
                    }
                    s.setRepeatingRequest(req.build(), null, handler)
                    runOnUiThread { statusPill.text = "VIDEO • READY" }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Video configuration failed", Toast.LENGTH_LONG).show() }
                }
            }, handler)
        } catch (e: Throwable) {
            mediaRecorder?.release(); mediaRecorder = null
            videoPfd?.close(); videoPfd = null
            videoUri?.let { contentResolver.delete(it, null, null) }; videoUri = null
            videoSurface = null
            switchToPhotoSession(false)
            Toast.makeText(this, "Video unavailable: ${e.message ?: "unsupported"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startVideo() {
        try {
            mediaRecorder?.start()
            videoRecording = true
            shutter.alpha = 0.8f
            statusPill.text = "VIDEO • RECORDING"
        } catch (_: Throwable) {
            Toast.makeText(this, "Could not start video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVideo() {
        val recorder = mediaRecorder ?: return
        val uri = videoUri
        try { recorder.stop() } catch (_: Throwable) { uri?.let { contentResolver.delete(it, null, null) } }
        recorder.reset(); recorder.release(); mediaRecorder = null
        videoPfd?.close(); videoPfd = null
        if (uri != null && Build.VERSION.SDK_INT >= 29) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        videoUri = null; videoSurface = null; videoRecording = false
        shutter.alpha = 1f
        statusPill.text = "VIDEO • READY"
        switchToPhotoSession(false)
    }

    private fun capture() {
        if (mode == "VIDEO") {
            if (videoRecording) stopVideo() else startVideo()
            return
        }
        if ((mode == "PHOTO" || mode == "PRO") && currentZoom >= 6f && !burstMode) {
            startZoomBurst()
            return
        }
        if (mode == "ASTRO" && !burstMode) {
            startBurstSequence(true)
            return
        }
        if (mode == "NIGHT" && !burstMode) {
            startBurstSequence(false)
            return
        }
        captureSingle()
    }

    private fun startZoomBurst() {
        if (captureBusy.get()) return
        burstFiles.clear()
        burstType = "ZOOM"
        burstZoom = currentZoom
        burstMode = true
        val b = controller.budget()
        burstTotal = when (b.profile) {
            MemoryThermalController.Profile.LOW_MEMORY -> 3
            MemoryThermalController.Profile.BALANCED -> 4
            MemoryThermalController.Profile.HIGH_PERFORMANCE -> 5
        }
        burstRemaining = burstTotal
        astroRawSaved = true
        statusPill.text = String.format(Locale.US, "%.0fx • MULTI-FRAME", currentZoom)
        captureSingle()
    }

    private fun startBurstSequence(astro: Boolean) {
        if (captureBusy.get()) return
        burstFiles.clear()
        burstType = if (astro) "ASTRO" else "NIGHT"
        burstZoom = currentZoom
        burstMode = true
        val b = controller.budget()
        burstTotal = if (astro) b.maxFrames.coerceAtLeast(6).coerceAtMost(16) else b.maxFrames.coerceAtLeast(4).coerceAtMost(10)
        burstRemaining = burstTotal
        astroRawSaved = false
        astroDeadline = if (astro) SystemClock.uptimeMillis() + astroDurationMs() else 0L
        val minutes = getSharedPreferences("settings", 0).getInt("astroMinutes", 4).coerceIn(1, 8)
        statusPill.text = if (astro) "ASTRO • ${minutes}:00" else "NIGHT • STACKING"
        captureSingle()
    }

    private fun captureSingle() {
        val s = session ?: return
        val d = device ?: return
        val j = jpegReader ?: return
        if (!captureBusy.compareAndSet(false, true)) return

        val isBurst = burstMode
        val rawEnabled = getSharedPreferences("settings", 0).getBoolean("raw", true)
        val captureRaw = rawEnabled && rawSupported && currentSessionHasRaw && (
            mode == "RAW" || (mode == "ASTRO" && !astroRawSaved)
        )
        pendingRawSave = captureRaw

        try {
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(j.surface)
                if (captureRaw) rawReader?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, supportedAfMode(true))
                set(CaptureRequest.CONTROL_AE_MODE, when {
                    flashMode == FLASH_AUTO && capabilitiesForCurrent().flash -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    flashMode == FLASH_ON && capabilitiesForCurrent().flash -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    else -> CaptureRequest.CONTROL_AE_MODE_ON
                })
                set(CaptureRequest.FLASH_MODE, if (flashMode == FLASH_ON && capabilitiesForCurrent().flash) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                set(CaptureRequest.SCALER_CROP_REGION, currentCrop())
                applyMetering(this)
                when (mode) {
                    "ASTRO" -> configureAstro(this)
                    "PRO" -> configurePro(this)
                    "NIGHT" -> configureNight(this)
                }
            }
            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    lastResult = result
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    captureBusy.set(false)
                }
            }, handler)
        } catch (_: Throwable) {
            captureBusy.set(false)
            Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureNight(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AF_MODE, supportedAfMode(true))
    }

    private fun configurePro(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AF_MODE, supportedAfMode(true))
    }

    private fun configureAstro(builder: CaptureRequest.Builder) {
        val c = characteristics ?: return
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val safeIso = ((iso?.upper ?: 1600).coerceAtMost(2500)).coerceAtLeast(iso?.lower ?: 100)
        val preferredExposure = 4_000_000_000L
        val safeExposure = preferredExposure.coerceIn(exp?.lower ?: 1_000_000L, exp?.upper ?: preferredExposure)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExposure)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
    }

    private fun onJpeg(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            if (burstMode) {
                val file = File(cacheDir, "sari_burst_${System.nanoTime()}.jpg")
                FileOutputStream(file).use { it.write(bytes) }
                burstFiles.add(file)
                burstRemaining--
                captureBusy.set(false)

                val shouldFinish = burstRemaining <= 0 ||
                    (burstType == "ASTRO" && SystemClock.uptimeMillis() >= astroDeadline)
                if (shouldFinish) {
                    val isAstro = burstType == "ASTRO"
                    val kind = burstType
                    val zoom = burstZoom
                    burstMode = false
                    val files = burstFiles.toList()
                    burstFiles.clear()
                    processBurst(files, isAstro, zoom, kind)
                } else {
                    val delay = when (burstType) {
                        "ASTRO" -> {
                            val remainingTime = (astroDeadline - SystemClock.uptimeMillis()).coerceAtLeast(1000L)
                            (remainingTime / (burstRemaining + 1)).coerceIn(3500L, 45_000L)
                        }
                        "ZOOM" -> 90L
                        else -> 240L
                    }
                    handler.postDelayed({ captureSingle() }, delay)
                }
            } else {
                captureBusy.set(false)
                processLatest(bytes, currentZoom)
            }
        } finally {
            image.close()
        }
    }

    private fun onRaw(image: Image) {
        try {
            val result = lastResult
            val rawEnabled = getSharedPreferences("settings", 0).getBoolean("raw", true)
            val shouldSave = rawEnabled && pendingRawSave
            if (!shouldSave || result == null) {
                pendingRawSave = false
                return
            }

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "SARI_${System.currentTimeMillis()}.dng")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SARI Camera/RAW")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        android.hardware.camera2.DngCreator(characteristics!!, result).use { creator -> creator.writeImage(out, image) }
                    }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    if (mode == "ASTRO") astroRawSaved = true
                } catch (_: Throwable) {
                    contentResolver.delete(uri, null, null)
                }
                pendingRawSave = false
            } else {
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "SARI Camera/RAW").apply { mkdirs() }
                val file = File(dir, "SARI_${System.currentTimeMillis()}.dng")
                try {
                    file.outputStream().use { out ->
                        android.hardware.camera2.DngCreator(characteristics!!, result).use { creator -> creator.writeImage(out, image) }
                    }
                    android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/x-adobe-dng"), null)
                    if (mode == "ASTRO") astroRawSaved = true
                } catch (_: Throwable) { file.delete() }
                pendingRawSave = false
            }
        } finally {
            image.close()
        }
    }

    private fun saveJpegBytes(bytes: ByteArray, astro: Boolean) {
        val name = "SARI_ORIGINAL_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, if (astro) "Pictures/SARI Camera/Astro" else "Pictures/SARI Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (_: Throwable) { contentResolver.delete(uri, null, null) }
            } else {
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), if (astro) "SARI Camera/Astro" else "SARI Camera")
                dir.mkdirs()
                val file = File(dir, name)
                file.outputStream().use { it.write(bytes) }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            }
        } catch (_: Throwable) { }
    }

    private fun processLatest(bytes: ByteArray, zoom: Float) {
        processingExecutor.execute {
            try {
                runOnUiThread { showProcessing("Processing • ${if (zoom >= 13f) "AI RECONSTRUCTION" else if (zoom >= 6f) "AI SUPER RES" else "IMAGE ENGINE"}", 0) }
                val budget = controller.budget()
                val decoded = decodeOriented(bytes, budget.imageMaxDimension) ?: return@execute
                val bitmap = applySoftwareCrop(decoded, zoom)
                if (bitmap !== decoded) decoded.recycle()
                val focus = if (hasFocusPoint && zoom >= 6f) focusRoi(bitmap, zoom) else null
                val faces = FaceProtection.detect(bitmap)
                val processor = TileProcessor(ai, controller)
                val enhanced = processor.processZoom(bitmap, zoom, focus, faces) { progress ->
                    runOnUiThread { showProcessing("Processing • $progress%", progress) }
                }
                if (getSharedPreferences("settings", 0).getBoolean("saveOriginal", false)) {
                    saveJpegBytes(bytes, false)
                }
                ImageSaver.saveJpeg(this, enhanced, false)
                enhanced.recycle()
                bitmap.recycle()
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Processing fallback: ${e.message ?: "unknown"}", Toast.LENGTH_SHORT).show() }
            } finally {
                runOnUiThread { hideProcessing() }
            }
        }
    }

    private fun processBurst(files: List<File>, astro: Boolean, zoom: Float = currentZoom, kind: String = if (astro) "ASTRO" else "NIGHT") {
        if (files.size < 2) {
            files.forEach(File::delete)
            return
        }
        processingExecutor.execute {
            val bitmaps = mutableListOf<Bitmap>()
            try {
                runOnUiThread { showProcessing(when (kind) {
                    "ZOOM" -> "ZOOM • MULTI-FRAME ALIGN"
                    "ASTRO" -> "ASTRO • ALIGN + STACK"
                    else -> "NIGHT • ALIGN + STACK"
                }, 0) }
                val budget = controller.budget()
                val stackMax = min(budget.imageMaxDimension, if (astro) 2048 else 2304)
                for (file in files.take(budget.maxFrames)) {
                    decodeOriented(file.readBytes(), stackMax)?.let { decoded ->
                        val cropped = applySoftwareCrop(decoded, zoom)
                        if (cropped !== decoded) decoded.recycle()
                        bitmaps.add(cropped)
                    }
                }
                if (bitmaps.size < 2) return@execute
                val targetW = bitmaps.minOf { it.width }
                val targetH = bitmaps.minOf { it.height }
                val normalized = bitmaps.map { if (it.width == targetW && it.height == targetH) it else Bitmap.createScaledBitmap(it, targetW, targetH, true) }
                val buffers = normalized.map { bitmap ->
                    ByteBuffer.allocateDirect(bitmap.byteCount).also { b -> bitmap.copyPixelsToBuffer(b); b.rewind() }
                }
                val out = ByteBuffer.allocateDirect(targetW * targetH * 4)
                val aligned = NativeEngine.alignAndStackRGBA(buffers.toTypedArray(), out, targetW, targetH, if (astro) 2.2f else 2.6f)
                if (aligned >= 2) {
                    out.rewind()
                    val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    result.copyPixelsFromBuffer(out)
                    val faces = FaceProtection.detect(result)
                    val focus = if (hasFocusPoint && zoom >= 6f) focusRoi(result, zoom) else null
                    val enhanced = TileProcessor(ai, controller).processZoom(result, zoom, focus, faces) { p ->
                        val label = when (kind) { "ZOOM" -> "ZOOM"; "ASTRO" -> "ASTRO"; else -> "NIGHT" }
                        runOnUiThread { showProcessing("$label • $p%", p) }
                    }
                    ImageSaver.saveJpeg(this, enhanced, astro)
                    enhanced.recycle()
                    result.recycle()
                }
                normalized.forEach { if (it !in bitmaps) it.recycle() }
                bitmaps.forEach { it.recycle() }
            } catch (e: Throwable) {
                bitmaps.forEach { try { it.recycle() } catch (_: Throwable) {} }
                runOnUiThread { Toast.makeText(this, "Stack fallback: ${e.message ?: "unknown"}", Toast.LENGTH_SHORT).show() }
            } finally {
                files.forEach(File::delete)
                runOnUiThread {
                    hideProcessing()
                    statusPill.text = when (kind) {
                        "ZOOM" -> "${zoom.toInt()}× • READY"
                        "ASTRO" -> "ASTRO • READY"
                        else -> "NIGHT • READY"
                    }
                }
            }
        }
    }

    private fun decodeOriented(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            val degrees = exif.rotationDegrees
            if (degrees == 0) decoded else Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(degrees.toFloat()) },
                true
            ).also { decoded.recycle() }
        } catch (_: Throwable) { decoded }
    }

    private fun applySoftwareCrop(src: Bitmap, zoom: Float): Bitmap {
        val factor = (zoom / ((characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 20f).coerceAtLeast(1f).coerceAtMost(zoom))).coerceAtLeast(1f)
        if (factor <= 1.01f) return src
        val cropW = (src.width / factor).toInt().coerceAtLeast(2)
        val cropH = (src.height / factor).toInt().coerceAtLeast(2)
        val cx = if (hasFocusPoint) lastTapX * src.width else src.width / 2f
        val cy = if (hasFocusPoint) lastTapY * src.height else src.height / 2f
        val left = (cx - cropW / 2f).toInt().coerceIn(0, src.width - cropW)
        val top = (cy - cropH / 2f).toInt().coerceIn(0, src.height - cropH)
        return Bitmap.createBitmap(src, left, top, cropW, cropH).let { crop ->
            Bitmap.createScaledBitmap(crop, src.width, src.height, true).also { crop.recycle() }
        }
    }

    private fun focusRoi(bitmap: Bitmap, zoom: Float): RectF {
        val size = if (zoom >= 13f) 0.28f else 0.42f
        val w = bitmap.width * size
        val h = bitmap.height * size
        val cx = bitmap.width * lastTapX
        val cy = bitmap.height * lastTapY
        return RectF(
            (cx - w / 2f).coerceIn(0f, bitmap.width - w),
            (cy - h / 2f).coerceIn(0f, bitmap.height - h),
            (cx + w / 2f).coerceIn(w, bitmap.width.toFloat()),
            (cy + h / 2f).coerceIn(h, bitmap.height.toFloat())
        )
    }

    private fun focusAt(x: Float, y: Float) {
        val w = preview.width.toFloat().coerceAtLeast(1f)
        val h = preview.height.toFloat().coerceAtLeast(1f)
        val software = softwareZoomFactor()
        val unzoomedX = ((x - w / 2f) / software + w / 2f).coerceIn(0f, w)
        val unzoomedY = ((y - h / 2f) / software + h / 2f).coerceIn(0f, h)
        lastTapX = (unzoomedX / w).coerceIn(0f, 1f)
        lastTapY = (unzoomedY / h).coerceIn(0f, 1f)
        hasFocusPoint = true
        focusView.visibility = View.VISIBLE
        focusView.x = x - focusView.width / 2f
        focusView.y = y - focusView.height / 2f
        focusView.alpha = 1f
        focusView.animate().alpha(0f).setDuration(700).start()

        val d = device ?: return
        val s = session ?: return
        try {
            val trigger = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface ?: return)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.SCALER_CROP_REGION, currentCrop())
                applyMetering(this)
            }
            s.capture(trigger.build(), null, handler)
            handler.postDelayed({ updatePreviewRepeating() }, 250L)
        } catch (_: Throwable) { }
    }

    private fun applyMetering(builder: CaptureRequest.Builder) {
        if (!hasFocusPoint) return
        val c = characteristics ?: return
        val crop = currentCrop()
        val sx = crop.width() * lastTapX + crop.left
        val sy = crop.height() * lastTapY + crop.top
        val halfW = (crop.width() * 0.08f).toInt().coerceAtLeast(20)
        val halfH = (crop.height() * 0.08f).toInt().coerceAtLeast(20)
        val rect = Rect(
            (sx - halfW).toInt().coerceIn(crop.left, crop.right - 1),
            (sy - halfH).toInt().coerceIn(crop.top, crop.bottom - 1),
            (sx + halfW).toInt().coerceIn(crop.left + 1, crop.right),
            (sy + halfH).toInt().coerceIn(crop.top + 1, crop.bottom)
        )
        val maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(android.hardware.camera2.params.MeteringRectangle(rect, 900)))
        if (maxAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(android.hardware.camera2.params.MeteringRectangle(rect, 900)))
    }

    private fun hardwareZoom(): Float {
        val hwMax = (characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 20f).coerceAtLeast(1f)
        return currentZoom.coerceAtMost(hwMax)
    }

    private fun softwareZoomFactor(): Float = (currentZoom / hardwareZoom()).coerceAtLeast(1f)

    private fun applyPreviewSoftwareZoom() {
        val factor = softwareZoomFactor()
        preview.pivotX = preview.width / 2f
        preview.pivotY = preview.height / 2f
        preview.scaleX = factor
        preview.scaleY = factor
    }

    private fun currentCrop(): Rect {
        val c = characteristics
        val active = c?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return Rect()
        val z = hardwareZoom()
        val cropW = (active.width() / z).toInt().coerceAtLeast(1)
        val cropH = (active.height() / z).toInt().coerceAtLeast(1)

        val targetX = if (hasFocusPoint) {
            active.left + (active.width() * lastTapX).toInt()
        } else active.centerX()
        val targetY = if (hasFocusPoint) {
            active.top + (active.height() * lastTapY).toInt()
        } else active.centerY()

        val left = (targetX - cropW / 2).coerceIn(active.left, active.right - cropW)
        val top = (targetY - cropH / 2).coerceIn(active.top, active.bottom - cropH)
        return Rect(left, top, left + cropW, top + cropH).also { it.intersect(active) }
    }

    private fun supportedAfMode(video: Boolean): Int {
        val modes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val preferred = if (video) CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        return if (modes.contains(preferred)) preferred
        else if (modes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO)) CameraCharacteristics.CONTROL_AF_MODE_AUTO
        else CameraCharacteristics.CONTROL_AF_MODE_OFF
    }

    private fun capabilitiesForCurrent(): CameraCapabilities =
        CapabilityDetector.detect(manager).firstOrNull { it.cameraId == cameraId }
            ?: CameraCapabilities(
                cameraId = cameraId,
                facing = CameraCharacteristics.LENS_FACING_BACK,
                raw = false,
                yuv = false,
                jpeg = true,
                heif = false,
                hardwareLevel = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                focalLengths = floatArrayOf(),
                activeArray = null,
                sensorSize = null,
                ois = false,
                eis = false,
                flash = false,
                isoRange = null,
                exposureRange = null,
                focusModes = intArrayOf(),
                outputSizes = emptyList(),
                maxDigitalZoom = 20f
            )

    private fun cameraIsBack(): Boolean = characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK

    private fun switchCamera() {
        if (videoRecording) stopVideo()
        val target = if (cameraIsBack()) frontCameraId else backCameraId
        if (target == null) {
            Toast.makeText(this, "Second camera is not available", Toast.LENGTH_SHORT).show()
            return
        }
        session?.close(); session = null
        device?.close(); device = null
        cameraId = target
        currentZoom = 1f
        hasFocusPoint = false
        applyPreviewSoftwareZoom()
        flashMode = FLASH_AUTO
        flashButton.setImageResource(R.drawable.ic_flash_auto)
        handler.postDelayed({ openCameraId(target) }, 300L)
    }

    private fun showProcessing(text: String, progress: Int) {
        processingText.visibility = View.VISIBLE
        processingText.text = text
        processingText.alpha = 1f
    }

    private fun hideProcessing() {
        processingText.animate().alpha(0f).setDuration(220).withEndAction { processingText.visibility = View.GONE }.start()
    }

    private fun rounded(selected: Boolean): android.graphics.drawable.Drawable = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(if (selected) ContextCompat.getColor(this@MainActivity, R.color.sari_white) else 0x22000000)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun hasCameraPermission() = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        try { if (videoRecording) mediaRecorder?.stop() } catch (_: Throwable) {}
        mediaRecorder?.release()
        videoPfd?.close()
        session?.close(); device?.close()
        previewSurface?.release(); previewSurface = null
        jpegReader?.close(); rawReader?.close()
        processingExecutor.shutdownNow()
        try { processingExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        ai.close()
        cameraThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val FLASH_OFF = 0
        private const val FLASH_AUTO = 1
        private const val FLASH_ON = 2
    }
}
