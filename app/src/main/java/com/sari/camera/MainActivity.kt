package com.sari.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.media.Image
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var controller: CameraController
    private lateinit var status: TextView
    private lateinit var capability: TextView
    private lateinit var rawToggle: Switch
    private lateinit var proPanel: LinearLayout
    private lateinit var isoSeek: SeekBar
    private lateinit var exposureSeek: SeekBar
    private lateinit var focusSeek: SeekBar
    private lateinit var wbSeek: SeekBar
    private val ui = Handler(Looper.getMainLooper())
    private var caps: CameraCapabilities? = null
    private var profile = HardwareProfile.LOW_END
    private var lastMediaUri: android.net.Uri? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val destroyed = AtomicBoolean(false)
    private val modes = listOf("PHOTO", "NIGHT", "ASTRO", "PRO", "VIDEO")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        capability = findViewById(R.id.capability)
        rawToggle = findViewById(R.id.rawToggle)
        proPanel = findViewById(R.id.proPanel)
        isoSeek = findViewById(R.id.isoSeek)
        exposureSeek = findViewById(R.id.exposureSeek)
        focusSeek = findViewById(R.id.focusSeek)
        wbSeek = findViewById(R.id.wbSeek)

        val preview = findViewById<android.view.TextureView>(R.id.preview)
        controller = CameraController(
            this, preview,
            onCaps = { c, p -> ui.post { updateCapabilities(c, p) } },
            onJpeg = { bytes, processed -> ui.post { saveJpeg(bytes, processed) } },
            onRaw = { image, characteristics, result -> saveDng(image, characteristics, result) },
            onProgress = { text -> ui.post { status.text = text } },
            onError = { text -> ui.post { status.text = text } }
        )

        findViewById<ImageButton>(R.id.shutter).setOnClickListener {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) controller.capture()
            else requestCameraPermission()
        }
        findViewById<ImageButton>(R.id.switchCamera).setOnClickListener { controller.switchCamera() }
        findViewById<ImageButton>(R.id.gallery).setOnClickListener {
            val uri = lastMediaUri ?: contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
            if (uri != null) startActivity(Intent(Intent.ACTION_VIEW, uri).apply { type = "image/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) else status.text = "No photo yet"
        }
        rawToggle.setOnCheckedChangeListener { _, checked -> controller.setRaw(checked) }
        setupModes()
        setupProControls()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                val restricted = status >= PowerManager.THERMAL_STATUS_SEVERE
                controller.setThermalRestricted(restricted)
                ui.post { if (restricted) this.status.text = "Cooling down — processing paused" }
            }
            pm.addThermalStatusListener(mainExecutor, thermalListener!!)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestCameraPermission()
    }

    private fun requestCameraPermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)

    private fun setupModes() {
        val ids = intArrayOf(R.id.photoMode, R.id.nightMode, R.id.astroMode, R.id.proMode, R.id.videoMode)
        ids.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener {
                val mode = modes[index]
                if (mode == "PRO" && caps?.supportsManualExposure != true) {
                    status.text = "Manual exposure is not supported by this camera"
                    return@setOnClickListener
                }
                controller.setMode(mode)
                ids.forEach { findViewById<TextView>(it).isSelected = it == id }
                proPanel.visibility = if (mode == "PRO") View.VISIBLE else View.GONE
                status.text = mode
            }
        }
        findViewById<TextView>(R.id.photoMode).isSelected = true
    }

    private fun setupProControls() {
        isoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val range = caps?.iso ?: return
                val value = range.lower + ((range.upper - range.lower) * p / 100f).toInt()
                controller.iso = value
                findViewById<TextView>(R.id.isoValue).text = "ISO $value"
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        focusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val max = caps?.minFocusDistance ?: return
                controller.focusDistance = max * p / 100f
                findViewById<TextView>(R.id.focusValue).text = "Focus ${(p)}%"
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        wbSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val k = 2500 + p * 50
                controller.whiteBalanceKelvin = k
                findViewById<TextView>(R.id.wbValue).text = "WB ${k}K"
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val range = caps?.exposureNs ?: return
                val logMin = kotlin.math.ln(range.lower.coerceAtLeast(1).toDouble())
                val logMax = kotlin.math.ln(range.upper.coerceAtLeast(range.lower + 1).toDouble())
                val ns = kotlin.math.exp(logMin + (logMax - logMin) * p / 100.0).toLong()
                controller.exposureNs = ns
                findViewById<TextView>(R.id.exposureValue).text = "1/${(1_000_000_000L / ns.coerceAtLeast(1)).coerceAtLeast(1)}s"
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
    }

    private fun updateCapabilities(c: CameraCapabilities, p: HardwareProfile) {
        caps = c; profile = p
        rawToggle.visibility = if (c.supportsRaw) View.VISIBLE else View.GONE
        rawToggle.isEnabled = c.supportsRaw
        if (!c.supportsRaw) rawToggle.isChecked = false
        val facing = if (c.facing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Rear"
        capability.text = "$facing • ${p.name.replace('_', ' ')} • RAW ${if (c.supportsRaw) "ON" else "OFF"} • ISO ${c.iso?.lower ?: "—"}-${c.iso?.upper ?: "—"}"
        val range = c.iso
        isoSeek.isEnabled = c.supportsManualExposure && range != null
        exposureSeek.isEnabled = c.supportsManualExposure && c.exposureNs != null
        focusSeek.isEnabled = c.supportsManualFocus
        wbSeek.isEnabled = c.supportsManualWhiteBalance
        if (range != null) controller.iso = range.lower
        if (c.exposureNs != null) controller.exposureNs = c.exposureNs.lower
    }

    private fun saveJpeg(bytes: ByteArray, processed: Boolean) {
        if (destroyed.get() || bytes.isEmpty()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val sub = if (controller.currentMode() == "ASTRO") "Pictures/SARI Camera/Astro/" else "Pictures/SARI Camera/"
        val name = "SARI_${stamp}${if (processed) "_processed" else ""}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, sub)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        runCatching {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes); out.flush() }
                    ?: throw IllegalStateException("Unable to open image output")
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                lastMediaUri = uri
                status.text = "Saved to SARI Camera"
            } catch (t: Throwable) {
                contentResolver.delete(uri, null, null)
                throw t
            }
        }.onFailure { status.text = "Save failed: storage unavailable" }
    }

    private fun saveDng(image: android.media.Image, characteristics: CameraCharacteristics, result: CaptureResult) {
        if (destroyed.get()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SARI_${stamp}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SARI Camera/RAW/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        runCatching {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("RAW MediaStore insert failed")
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    DngCreator(characteristics, result).use { creator -> creator.writeImage(out, image) }
                    out.flush()
                } ?: throw IllegalStateException("Unable to open RAW output")
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                ui.post { status.text = "RAW DNG saved" }
            } catch (t: Throwable) {
                contentResolver.delete(uri, null, null)
                throw t
            }
        }.onFailure { ui.post { status.text = "RAW save failed safely" } }
    }

    override fun onResume() { super.onResume(); if (::controller.isInitialized && !destroyed.get()) controller.resume() }
    override fun onPause() { if (::controller.isInitialized) controller.pause(); super.onPause() }
    override fun onDestroy() {
        destroyed.set(true)
        if (android.os.Build.VERSION.SDK_INT >= 29 && thermalListener != null) { (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(thermalListener!!) }
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }
}
