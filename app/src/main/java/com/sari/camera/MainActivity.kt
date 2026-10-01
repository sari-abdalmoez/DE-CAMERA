package com.sari.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var preview: TextureView
    private lateinit var shutter: Button
    private lateinit var capabilityText: TextView
    private lateinit var thermalText: TextView
    private lateinit var manager: CameraManager
    private var cameraId = ""
    private var characteristics: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private val cameraThread = HandlerThread("SARI-Camera", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler
    private var mode = "PHOTO"
    private var rawSupported = false
    private lateinit var controller: MemoryThermalController
    private lateinit var ai: AiEngine
    private var burstRemaining = 0
    private var burstMode = false
    private val burstFiles = mutableListOf<File>()
    private var pendingRaw = false
    private var mediaRecorder: MediaRecorder? = null
    private var videoSurface: Surface? = null
    private var videoUri: Uri? = null
    private var videoPfd: android.os.ParcelFileDescriptor? = null
    private var videoRecording = false
    private var lastResult: TotalCaptureResult? = null
    private val captureBusy = AtomicBoolean(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        val storageGranted = Build.VERSION.SDK_INT >= 29 ||
            grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        if (cameraGranted && storageGranted && preview.isAvailable) openCamera()
    }


    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        runOnUiThread {
            thermalText.text = if (status >= PowerManager.THERMAL_STATUS_SEVERE) "Performance reduced" else ""
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        shutter = findViewById(R.id.shutter)
        capabilityText = findViewById(R.id.capabilityText)
        thermalText = findViewById(R.id.thermalText)
        manager = getSystemService(CameraManager::class.java)
        controller = MemoryThermalController(this)
        ai = AiEngine(this)
        Thread {
            prepareAiModel()
            runOnUiThread { thermalText.text = if (ai.superResolutionAvailable || ai.deblurAvailable) "AI ready" else "AI unavailable — OpenCV restoration remains active" }
        }.start()
        cameraThread.start()
        handler = Handler(cameraThread.looper)
        setupModes()
        shutter.setOnClickListener { capture() }
        findViewById<Button>(R.id.gallery).setOnClickListener { startActivity(android.content.Intent(this, GalleryActivity::class.java)) }
        findViewById<Button>(R.id.settings).setOnClickListener { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { if (hasCameraPermission()) openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener)
        if (!hasCameraPermission()) permissionLauncher.launch(requiredPermissions())
    }

    private fun hasCameraPermission() = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT <= 28) {
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.CAMERA)
    }

    private fun setupModes() {
        val bar = findViewById<LinearLayout>(R.id.modeBar)
        listOf("PHOTO", "NIGHT", "ASTRO", "PRO", "RAW", "VIDEO").forEach { label ->
            bar.addView(Button(this).apply {
                text = label
                setOnClickListener { selectMode(label) }
            })
        }
    }

    private fun selectMode(label: String) {
        if (videoRecording && label != "VIDEO") stopVideo()
        mode = label; burstMode = false; burstRemaining = 0; shutter.text = if (label == "VIDEO") "REC" else label
        if (label == "VIDEO") switchToVideo() else if (videoSurface != null) switchToPhotoSession()
    }

    private fun openCamera() {
        if (!hasCameraPermission()) return
        try {
            val caps = CapabilityDetector.detect(manager)
            val back = caps.firstOrNull { manager.getCameraCharacteristics(it.cameraId).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: caps.firstOrNull() ?: return
            cameraId = back.cameraId
            characteristics = manager.getCameraCharacteristics(cameraId)
            rawSupported = back.raw
            capabilityText.text = "RAW ${back.raw} · OIS ${back.ois} · EIS ${back.eis}"
            manager.openCamera(cameraId, stateCallback, handler)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera unavailable: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) { device = camera; prepareReaders(); switchToPhotoSession() }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); if (device === camera) device = null }
        override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (device === camera) device = null; runOnUiThread { Toast.makeText(this@MainActivity, "Camera error $error", Toast.LENGTH_SHORT).show() } }
    }

    private fun prepareReaders() {
        val c = characteristics ?: return
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val jpegSize = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: android.util.Size(1920, 1080)
        jpegReader?.close()
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 3).also {
            it.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.let(::onJpeg) }, handler)
        }
        rawReader?.close()
        if (rawSupported) {
            val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            if (rawSize != null) rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).also {
                it.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.let(::onRaw) }, handler)
            }
        }
    }

    private fun switchToPhotoSession() {
        val d = device ?: return
        val texture = preview.surfaceTexture ?: return
        val jpeg = jpegReader ?: return
        val previewSurface = Surface(texture)
        val surfaces = mutableListOf(previewSurface, jpeg.surface)
        rawReader?.let { surfaces.add(it.surface) }
        try {
            d.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    try { s.setRepeatingRequest(req.build(), null, handler) } catch (_: CameraAccessException) { }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Camera configuration failed", Toast.LENGTH_LONG).show() }
                }
            }, handler)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera session failed: ${e.message ?: "unknown"}", Toast.LENGTH_LONG).show()
        }
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
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("Output unavailable")
            videoPfd = pfd
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoEncodingBitRate(8_000_000)
            recorder.setVideoFrameRate(30)
            val videoSizes = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            val videoSize = videoSizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height }
                ?: videoSizes.firstOrNull() ?: android.util.Size(1280, 720)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setOutputFile(pfd.fileDescriptor)
            recorder.prepare()
            mediaRecorder = recorder; videoUri = uri; videoSurface = recorder.surface
            val previewSurface = Surface(texture)
            d.createCaptureSession(listOf(previewSurface, recorder.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface); addTarget(recorder.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    s.setRepeatingRequest(req.build(), null, handler)
                    runOnUiThread { Toast.makeText(this@MainActivity, "VIDEO ready — press REC", Toast.LENGTH_SHORT).show() }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { Toast.makeText(this@MainActivity, "Video configuration failed", Toast.LENGTH_LONG).show() } }
            }, handler)
        } catch (e: Throwable) {
            videoSurface = null
            mediaRecorder?.release(); mediaRecorder = null
            videoPfd?.close(); videoPfd = null
            videoUri?.let { contentResolver.delete(it, null, null) }; videoUri = null
            Toast.makeText(this, "Video unavailable: ${e.message ?: "unsupported"}", Toast.LENGTH_LONG).show()
            switchToPhotoSession()
        }
    }

    private fun startVideo() {
        try { mediaRecorder?.start(); videoRecording = true; shutter.text = "STOP"; runOnUiThread { thermalText.text = "Recording" } }
        catch (_: Throwable) { Toast.makeText(this, "Could not start video", Toast.LENGTH_SHORT).show() }
    }

    private fun stopVideo() {
        val recorder = mediaRecorder ?: return
        try { recorder.stop() } catch (_: Throwable) { videoUri?.let { contentResolver.delete(it, null, null) }; videoUri = null }
        recorder.reset(); recorder.release(); mediaRecorder = null; videoPfd?.close(); videoPfd = null; videoSurface = null; videoRecording = false
        videoUri?.let { if (Build.VERSION.SDK_INT >= 29) contentResolver.update(it, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
        videoUri = null
        thermalText.text = ""; switchToPhotoSession()
    }

    private fun prepareAiModel() {
        try {
            val dir = File(filesDir, "models").apply { mkdirs() }
            assets.list("models")?.filter { it.endsWith(".onnx") }?.forEach { name ->
                val target = File(dir, name)
                if (!target.exists() || target.length() < 1024) assets.open("models/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            ai.loadModels(File(dir, "real_esrgan_x2.onnx"), File(dir, "deblurring_nafnet_2025may.onnx"))
        } catch (_: Throwable) { }
    }

    private fun capture() {
        if (mode == "VIDEO") { if (videoRecording) stopVideo() else startVideo(); return }
        val s = session ?: return
        val d = device ?: return
        val j = jpegReader ?: return
        if (!captureBusy.compareAndSet(false, true)) return

        if (!burstMode && (mode == "NIGHT" || mode == "ASTRO")) {
            val budget = controller.budget()
            burstRemaining = budget.maxFrames.coerceAtMost(20)
            burstFiles.clear()
            burstMode = true
            runOnUiThread { Toast.makeText(this, "$mode · $burstRemaining frames", Toast.LENGTH_SHORT).show() }
        }
        if (mode == "RAW" && !rawSupported) {
            captureBusy.set(false)
            Toast.makeText(this, "RAW is not supported by this camera", Toast.LENGTH_SHORT).show()
            return
        }
        pendingRaw = mode == "RAW"

        try {
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(j.surface)
                if (pendingRaw) rawReader?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                if (mode == "ASTRO") configureAstro(this)
                else if (mode == "NIGHT") configureNight(this)
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
        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
    }

    private fun configureAstro(builder: CaptureRequest.Builder) {
        val c = characteristics ?: return
        val range = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, ((range?.upper ?: 1600).coerceAtMost(3200)).coerceAtLeast(range?.lower ?: 100))
        val preferred = 2_000_000_000L
        val exposure = preferred.coerceIn(exp?.lower ?: 1_000_000L, exp?.upper ?: preferred)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
    }

    private fun onJpeg(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            if (burstMode) {
                val file = File(cacheDir, "burst_${System.nanoTime()}.jpg")
                FileOutputStream(file).use { it.write(bytes) }
                burstFiles.add(file)
                burstRemaining--
                if (burstRemaining > 0) {
                    captureBusy.set(false)
                    handler.post { capture() }
                } else {
                    burstMode = false
                    captureBusy.set(false)
                    handler.post { processBurst() }
                }
            } else {
                saveJpeg(bytes, false)
                captureBusy.set(false)
                if (mode == "PHOTO" || mode == "PRO") handler.post { processLatest(bytes) }
            }
        } finally { image.close() }
    }

    private fun onRaw(image: Image) {
        try {
            val result = lastResult
            if (result == null) return
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "SARI_${System.currentTimeMillis()}.dng")
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SARI Camera/RAW")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { out -> DngCreator(characteristics!!, result).use { it.writeImage(out, image) } }
                if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) { contentResolver.delete(uri, null, null) }
        } finally { image.close(); pendingRaw = false }
    }

    private fun saveJpeg(bytes: ByteArray, astro: Boolean) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SARI_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, if (astro) "Pictures/SARI Camera/Astro" else "Pictures/SARI Camera")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (_: Throwable) { contentResolver.delete(uri, null, null) }
    }

    private fun processLatest(bytes: ByteArray) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return
        Thread {
            try {
                val budget = controller.budget()
                val aiEnabled = getSharedPreferences("settings", 0).getBoolean("ai", true)
                val strength = if (ai.superResolutionAvailable || ai.deblurAvailable) 0.55f else 0.40f
                val enhanced = TileProcessor(budget.tile, 48, if (aiEnabled) ai else null, FaceProtection.detect(original)).process(original, strength)
                ImageSaver.saveJpeg(this, enhanced, false)
                enhanced.recycle(); original.recycle()
            } catch (_: Throwable) { original.recycle() }
        }.start()
    }

    private fun processBurst() {
        val files = burstFiles.toList(); burstFiles.clear()
        if (files.size < 2) { files.forEach(File::delete); return }
        runOnUiThread { Toast.makeText(this, "Analyzing → aligning → robust stacking → processing", Toast.LENGTH_SHORT).show() }
        Thread {
            val bitmaps = mutableListOf<Bitmap>()
            try {
                val budget = controller.budget()
                val maxFrames = minOf(files.size, budget.maxFrames)
                for (file in files.take(maxFrames)) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val maxDim = when (budget.profile) {
                        MemoryThermalController.Profile.LOW_MEMORY -> 1920
                        MemoryThermalController.Profile.BALANCED -> 2560
                        MemoryThermalController.Profile.HIGH_PERFORMANCE -> 3072
                    }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)?.let { bitmaps.add(it) }
                }
                if (bitmaps.size < 2) return@Thread
                val targetW = bitmaps.minOf { it.width }; val targetH = bitmaps.minOf { it.height }
                val normalized = bitmaps.map { if (it.width == targetW && it.height == targetH) it else Bitmap.createScaledBitmap(it, targetW, targetH, true) }
                val buffers = normalized.map { bitmap -> java.nio.ByteBuffer.allocateDirect(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(it); it.rewind() } }
                val out = java.nio.ByteBuffer.allocateDirect(targetW * targetH * 4)
                val aligned = NativeEngine.alignAndStackRGBA(buffers.toTypedArray(), out, targetW, targetH, 2.5f)
                if (aligned >= 2) {
                    out.rewind()
                    val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    result.copyPixelsFromBuffer(out)
                    val enhanced = TileProcessor(budget.tile, 48, if (getSharedPreferences("settings", 0).getBoolean("ai", true)) ai else null, FaceProtection.detect(result)).process(result, 0.60f)
                    ImageSaver.saveJpeg(this, enhanced, mode == "ASTRO")
                    enhanced.recycle(); result.recycle()
                }
                normalized.forEach { if (it !in bitmaps) it.recycle() }
                bitmaps.forEach { it.recycle() }
            } catch (_: Throwable) {
                bitmaps.forEach { try { it.recycle() } catch (_: Throwable) {} }
            } finally { files.forEach(File::delete) }
        }.start()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener)
        try { if (videoRecording) mediaRecorder?.stop() } catch (_: Throwable) {}
        mediaRecorder?.release(); mediaRecorder = null; videoPfd?.close(); videoPfd = null
        session?.close(); device?.close(); jpegReader?.close(); rawReader?.close(); ai.close(); cameraThread.quitSafely(); super.onDestroy()
    }
}
