package com.sari.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque
import kotlin.math.roundToInt

class CameraController(
    private val activity: Activity,
    private val texture: TextureView,
    private val onCaps: (CameraCapabilities, HardwareProfile) -> Unit,
    private val onJpeg: (ByteArray, Boolean) -> Unit,
    private val onRaw: (android.media.Image, CameraCharacteristics, CaptureResult) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("SARI-Camera2").also { it.start() }
    private val handler = Handler(thread.looper)
    private val workers: ExecutorService = Executors.newFixedThreadPool(HardwareProfile.detect(activity).workerThreads)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var cameraId: String? = null
    private var cameraChars: CameraCharacteristics? = null
    private var recoveries = 0
    private val closing = AtomicBoolean(false)
    private val burstFrames = ArrayList<ByteArray>()
    private var burstRemaining = 0
    private var burstTarget = 0
    private var mode = "PHOTO"
    private var wantRaw = false
    private var recording = false
    @Volatile private var thermalRestricted = false
    private var videoFile: java.io.File? = null
    var capabilities: CameraCapabilities? = null
        private set
    var profile: HardwareProfile = HardwareProfile.detect(activity)
        private set
    var iso: Int = 0
    var exposureNs: Long = 0L
    var focusDistance: Float = 0f
    var whiteBalanceKelvin: Int = 5000
    var zoom: Float = 1f

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { open() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCameraOnly(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { }
        }
    }

    fun setMode(value: String) { mode = value }
    fun currentMode(): String = mode
    fun pause() { closeCameraOnly() }
    fun resume() { if (!closing.get() && device == null) open() }
    fun setRaw(enabled: Boolean) {
        val next = enabled && capabilities?.supportsRaw == true
        if (wantRaw == next) return
        wantRaw = next
        if (device != null && !closing.get()) { session?.close(); session = null; createSession() }
    }
    fun setThermalRestricted(value: Boolean) { thermalRestricted = value; if (value && burstRemaining > 0) { burstRemaining = 0; burstFrames.clear(); onProgress("Paused for device temperature") } }

    private fun cameraIds() = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())

    fun open() {
        if (closing.get()) return
        val ids = cameraIds()
        if (ids.isEmpty()) { onError("No camera is available"); return }
        val id = cameraId?.takeIf { ids.contains(it) } ?: ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
        cameraId = id
        runCatching {
            val chars = manager.getCameraCharacteristics(id)
            cameraChars = chars
            capabilities = CameraCapabilities.inspect(manager, id)
            profile = HardwareProfile.detect(activity)
            onCaps(capabilities!!, profile)
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            manager.openCamera(id, stateCallback, handler)
        }.onFailure { onError("Camera initialization failed: ${it.message ?: "unknown error"}") }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
            recoveries = 0
            if (closing.get()) { d.close(); return }
            device = d
            startPreview()
        }
        override fun onDisconnected(d: CameraDevice) {
            d.close(); device = null; onError("Camera disconnected")
            scheduleRecovery()
        }
        override fun onError(d: CameraDevice, error: Int) {
            d.close(); device = null
            val label = when (error) {
                CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Camera device error"
                CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Camera service error"
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Camera is busy"
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled by system"
                else -> "Camera error $error"
            }
            onError(label)
            if (error != CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) scheduleRecovery()
        }
    }

    private fun scheduleRecovery() {
        if (closing.get() || recoveries >= 2) return
        recoveries++
        handler.postDelayed({ if (!closing.get()) open() }, 700L * recoveries)
    }

    private fun startPreview() {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        val caps = capabilities ?: return
        val previewSize = choosePreviewSize(caps.jpegSizes)
        val captureSize = chooseCaptureSize(caps.jpegSizes, profile.maxPixels)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(st)
        jpegReader?.close()
        jpegReader = ImageReader.newInstance(captureSize.width, captureSize.height, android.graphics.ImageFormat.JPEG, 2).also { r ->
            r.setOnImageAvailableListener({ ir ->
                runCatching { ir.acquireLatestImage()?.use { image ->
                    val b = image.planes[0].buffer
                    val bytes = ByteArray(b.remaining())
                    b.get(bytes)
                    handleJpeg(bytes)
                }}.onFailure { onError("Image capture failed") }
            }, handler)
        }
        createSession()
    }

    private fun createSession() {
        val d = device ?: return
        val preview = previewSurface ?: return
        val jpeg = jpegReader?.surface ?: return
        val outputs = mutableListOf(preview, jpeg)
        rawReader?.close(); rawReader = null
        if (capabilities?.supportsRaw == true && wantRaw) {
            val rawSize = capabilities?.rawSizes?.filter { it.width.toLong()*it.height <= profile.maxPixels.toLong() }?.maxByOrNull { it.width.toLong()*it.height }
                ?: capabilities?.rawSizes?.minByOrNull { it.width.toLong()*it.height } ?: Size(1, 1)
            rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, android.graphics.ImageFormat.RAW_SENSOR, 2).also { rr ->
                rr.setOnImageAvailableListener({ ir ->
                    val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    val chars = cameraChars
                    val ts = image.timestamp
                    val result = pendingRawResults.firstOrNull { it.get(CaptureResult.SENSOR_TIMESTAMP) == ts }
                        ?: pendingRawResults.lastOrNull()
                    if (chars == null || result == null) { image.close(); return@setOnImageAvailableListener }
                    pendingRawResults.remove(result)
                    workers.execute {
                        try { onRaw(image, chars, result) } catch (_: Throwable) { onError("RAW processing failed safely") } finally { image.close() }
                    }
                }, handler)
            }
            outputs += rawReader!!.surface
        }
        d.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closing.get()) { s.close(); return }
                session = s
                applyPreview()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { onError("Camera session configuration failed") }
        }, handler)
    }

    private fun applyPreview() {
        val d = device ?: return
        val s = session ?: return
        val preview = previewSurface ?: return
        runCatching {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyZoom(this)
            }
            s.setRepeatingRequest(req.build(), null, handler)
        }.onFailure { onError("Preview start failed") }
    }

    private fun choosePreviewSize(sizes: Array<Size>): Size = sizes
        .filter { it.width <= 1920 && it.height <= 1080 }
        .maxByOrNull { it.width.toLong() * it.height } ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)

    private fun chooseCaptureSize(sizes: Array<Size>, maxPixels: Int): Size = sizes
        .filter { it.width.toLong()*it.height <= maxPixels.toLong() }
        .maxByOrNull { it.width.toLong()*it.height } ?: sizes.minByOrNull { it.width.toLong()*it.height } ?: Size(1280,720)

    private val pendingRawResults = ArrayDeque<CaptureResult>()

    fun capture() {
        if (thermalRestricted) { onError("Processing paused: device temperature is high"); return }
        if (recording) { stopVideo(); return }
        when (mode) {
            "NIGHT", "ASTRO" -> startBurst(if (mode == "ASTRO") profile.maxBurst.coerceAtMost(12) else profile.maxBurst.coerceAtMost(8))
            "VIDEO" -> startVideo()
            else -> singleCapture()
        }
    }

    private fun startBurst(count: Int) {
        val safe = count.coerceIn(2, profile.maxBurst)
        burstFrames.clear(); burstRemaining = safe; burstTarget = safe
        onProgress("Capturing 1/$safe")
        singleCapture()
    }

    private fun singleCapture() {
        val d = device ?: return
        val s = session ?: return
        val jpeg = jpegReader?.surface ?: return
        runCatching {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg)
                set(CaptureRequest.CONTROL_AF_MODE, if (capabilities?.supportsManualFocus == true && mode == "PRO") CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, if (mode == "PRO" && capabilities?.supportsManualExposure == true) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON)
                if (mode == "PRO" && capabilities?.supportsManualExposure == true) {
                    capabilities?.iso?.let { set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(it.lower, it.upper).takeIf { v -> v > 0 }) }
                    capabilities?.exposureNs?.let { set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs.coerceIn(it.lower, it.upper).takeIf { v -> v > 0L }) }
                    if (capabilities?.supportsManualFocus == true && focusDistance >= 0f) {
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance.coerceIn(0f, capabilities?.minFocusDistance ?: focusDistance))
                    }
                    if (capabilities?.supportsManualWhiteBalance == true) {
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                        set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                        run { val g=wbGains(whiteBalanceKelvin); set(CaptureRequest.COLOR_CORRECTION_GAINS, android.hardware.camera2.params.RggbChannelVector(g[0],g[1],g[2],g[3])) }
                    }
                }
                if (wantRaw && capabilities?.supportsRaw == true) rawReader?.surface?.let { addTarget(it) }
                applyZoom(this)
            }
            s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (wantRaw) {
                        pendingRawResults.addLast(result)
                        while (pendingRawResults.size > 4) pendingRawResults.removeFirst()
                    }
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    onError("Capture failed")
                }
            }, handler)
        }.onFailure { onError("Capture request failed") }
    }

    private fun wbGains(kelvin: Int): FloatArray {
        val k = kelvin.coerceIn(2500, 7500).toFloat()
        val r = (6500f / k).coerceIn(1f, 2.6f)
        val b = (k / 4200f).coerceIn(1f, 2.6f)
        return floatArrayOf(r, 1f, 1f, b)
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        val caps = capabilities ?: return
        val active = caps.activeArray ?: return
        val max = caps.maxDigitalZoom.coerceAtLeast(1f)
        val z = zoom.coerceIn(1f, max)
        if (z <= 1f) { builder.set(CaptureRequest.SCALER_CROP_REGION, active); return }
        val cx = active.centerX(); val cy = active.centerY()
        val halfW = (active.width() / (2f * z)).roundToInt().coerceAtLeast(1)
        val halfH = (active.height() / (2f * z)).roundToInt().coerceAtLeast(1)
        builder.set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(cx-halfW, cy-halfH, cx+halfW, cy+halfH))
    }

    private fun handleJpeg(bytes: ByteArray) {
        if (burstRemaining <= 0) { onJpeg(bytes, false); return }
        if (burstFrames.size >= profile.maxBurst) { burstRemaining = 0; return }
        burstFrames.add(bytes)
        burstRemaining--
        val done = burstTarget - burstRemaining
        if (burstRemaining > 0) {
            onProgress("Capturing ${done + 1}/$burstTarget")
            handler.postDelayed({ if (!closing.get() && !thermalRestricted) singleCapture() else { burstRemaining=0; burstFrames.clear() } }, 90L)
        } else {
            val frames = burstFrames.toList()
            burstFrames.clear()
            workers.execute {
                runCatching {
                    onProgress("Analyzing ${frames.size} frames…")
                    val result = ProcessingEngine.stackJpegs(frames, profile.maxPixels)
                    if (result != null) onJpeg(result, true) else onError("Not enough valid frames to process")
                    onProgress("Ready")
                }.onFailure { onError("Processing failed safely") }
            }
        }
    }

    private fun startVideo() {
        val d=device ?: return; val preview=previewSurface ?: return
        runCatching {
            val file=java.io.File(activity.cacheDir, "sari_video_${System.currentTimeMillis()}.mp4"); videoFile=file
            val r=MediaRecorder(); recorder=r
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(1280,720); r.setVideoFrameRate(30); r.setVideoEncodingBitRate(4_000_000); r.setOutputFile(file.absolutePath); r.prepare()
            recorderSurface=r.surface; val videoSurface=recorderSurface ?: error("video surface unavailable")
            session?.close()
            d.createCaptureSession(listOf(preview,videoSurface),object:CameraCaptureSession.StateCallback(){
                override fun onConfigured(s:CameraCaptureSession){
                    session=s
                    val req=d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply{addTarget(preview);addTarget(videoSurface);set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON)}
                    s.setRepeatingRequest(req.build(),null,handler); r.start(); recording=true; onProgress("Recording…")
                }
                override fun onConfigureFailed(s:CameraCaptureSession){r.release();recorder=null;onError("Video session failed") }
            },handler)
        }.onFailure { recorder?.release();recorder=null;onError("Video setup failed") }
    }

    private fun stopVideo() {
        if(!recording && recorder==null)return
        recording=false
        runCatching { recorder?.stop() }.onFailure { }
        recorder?.reset(); recorder?.release(); recorder=null; recorderSurface?.release(); recorderSurface=null
        val file=videoFile; videoFile=null
        if(file!=null && file.exists()) onVideoReady(file)
        closeCameraOnly(); if(!closing.get()) open()
    }

    private fun onVideoReady(file:java.io.File){
        runCatching {
            val values=android.content.ContentValues().apply{put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,file.name);put(android.provider.MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,"Movies/SARI Camera/");put(android.provider.MediaStore.Video.Media.IS_PENDING,1)}
            val uri=activity.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)?:error("Video MediaStore insert failed")
            try{activity.contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)};out.flush()}?:error("Video output unavailable");activity.contentResolver.update(uri,android.content.ContentValues().apply{put(android.provider.MediaStore.Video.Media.IS_PENDING,0)},null,null);file.delete();onProgress("Video saved")}catch(t:Throwable){activity.contentResolver.delete(uri,null,null);throw t}
        }.onFailure{onError("Video save failed safely")}
    }

    fun switchCamera() {
        val ids = cameraIds(); if (ids.size < 2) return
        val current = cameraId ?: ids.first(); val index = ids.indexOf(current).coerceAtLeast(0); val next = ids[(index + 1) % ids.size]
        cameraId = next; closeCameraOnly(); open()
    }

    private fun closeCameraOnly() {
        session?.close(); session = null
        device?.close(); device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
        burstFrames.clear(); burstRemaining = 0
        pendingRawResults.clear()
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        closeCameraOnly()
        stopVideo()
        workers.shutdownNow()
        thread.quitSafely()
    }
}
