package com.sari.camera

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Hybrid AI runtime.
 *
 * - NCNN/Vulkan is preferred for strong 13x-20x reconstruction when present.
 * - Verified ONNX Real-ESRGAN/NAFNet remains the safe fallback.
 * - All inference is tile-based and never runs a full camera frame through the model.
 */
class AiEngine(private val context: android.content.Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private var superSession: OrtSession? = null
    private var deblurSession: OrtSession? = null
    private var superInputW = -1
    private var superInputH = -1
    private var superOutputW = -1
    private var superOutputH = -1

    var superResolutionAvailable = false; private set
    var deblurAvailable = false; private set
    var ncnnAvailable = false; private set
    var ncnnVulkanAvailable = false; private set
    var scale = 1; private set

    fun loadModels(superResolution: File?, deblur: File?, ncnnParam: File? = null, ncnnBin: File? = null): Boolean {
        superResolutionAvailable = loadSuper(superResolution)
        deblurAvailable = loadDeblur(deblur)
        ncnnVulkanAvailable = try { NativeEngine.ncnnVulkanAvailable() } catch (_: Throwable) { false }
        ncnnAvailable = try {
            if (ncnnParam?.isFile == true && ncnnBin?.isFile == true) {
                NativeEngine.ncnnInit(ncnnParam.absolutePath, ncnnBin.absolutePath, ncnnVulkanAvailable)
            } else false
        } catch (_: Throwable) { false }
        if (ncnnAvailable) {
            scale = NativeEngine.ncnnScale().coerceIn(2, 4)
            if (!smokeTestNcnn()) {
                try { NativeEngine.ncnnRelease() } catch (_: Throwable) {}
                ncnnAvailable = false
                ncnnVulkanAvailable = false
            }
        }
        if (superResolutionAvailable && !smokeTestOrtSuper()) {
            superSession?.close()
            superSession = null
            superResolutionAvailable = false
        }
        return superResolutionAvailable || deblurAvailable || ncnnAvailable
    }

    private fun smokeTestNcnn(): Boolean = try {
        val w = 24
        val h = 24
        val probe = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = ncnnResolve(probe)
        probe.recycle()
        out?.recycle()
        out != null
    } catch (_: Throwable) { false }

    private fun smokeTestOrtSuper(): Boolean {
        return try {
        val w = if (superInputW > 0) superInputW else 32
        val h = if (superInputH > 0) superInputH else 32
        val zeros = FloatArray(3 * w * h)
        val raw = infer(superSession, zeros, h, w) ?: return false
        val scaleOut = if (superInputW > 0 && superOutputW > 0) superOutputW.toFloat() / superInputW.toFloat() else 2f
        val expected = 3 * max(1, (w * scaleOut).toInt()) * max(1, (h * scaleOut).toInt())
        raw.size == expected
        } catch (_: Throwable) { false }
    }

    private fun loadSuper(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() < 1024) return false
        return try {
            superSession?.close()
            val s = newSession(file)
            val input = tensorInfo(s.inputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            val output = tensorInfo(s.outputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            if (input.shape.size != 4 || input.shape[1] != 3L || output.shape.size != 4 || output.shape[1] != 3L) {
                s.close(); return false
            }
            superInputH = input.shape[2].takeIf { it > 0 }?.toInt() ?: -1
            superInputW = input.shape[3].takeIf { it > 0 }?.toInt() ?: -1
            superOutputH = output.shape[2].takeIf { it > 0 }?.toInt() ?: -1
            superOutputW = output.shape[3].takeIf { it > 0 }?.toInt() ?: -1
            val ih = superInputH; val oh = superOutputH
            scale = if (ih > 0 && oh > 0) (oh.toFloat() / ih).toInt().coerceIn(1, 4) else 2
            superSession = s
            true
        } catch (_: Throwable) { false }
    }

    private fun loadDeblur(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() < 1024) return false
        return try {
            deblurSession?.close()
            val s = newSession(file)
            val input = tensorInfo(s.inputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            val output = tensorInfo(s.outputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            if (input.shape.size != 4 || input.shape[1] != 3L || output.shape.size != 4 || output.shape[1] != 3L) {
                s.close(); return false
            }
            deblurSession = s
            true
        } catch (_: Throwable) { false }
    }

    private fun newSession(file: File): OrtSession = env.createSession(
        file.absolutePath,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setMemoryPatternOptimization(true)
        }
    )

    private fun tensorInfo(info: ai.onnxruntime.ValueInfo?): ai.onnxruntime.TensorInfo? = info as? ai.onnxruntime.TensorInfo

    /** Returns an AI-restored tile, or null when AI is not usable for the device/model. */
    fun superResolveTile(src: Bitmap, strong: Boolean): Bitmap? {
        return try {
            if (strong && ncnnAvailable) ncnnResolve(src) ?: ortSuperResolve(src)
            else ortSuperResolve(src) ?: if (ncnnAvailable) ncnnResolve(src) else null
        } catch (_: Throwable) { null }
    }

    private fun ncnnResolve(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        val s = NativeEngine.ncnnScale().coerceIn(2, 4)
        val rgba = ByteBuffer.allocateDirect(w * h * 4)
        src.copyPixelsToBuffer(rgba); rgba.rewind()
        val outW = w * s
        val outH = h * s
        val out = ByteBuffer.allocateDirect(outW * outH * 4)
        val rc = NativeEngine.ncnnProcessRGBA(rgba, out, w, h, ncnnVulkanAvailable)
        if (rc < 0) return null
        out.rewind()
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(out)
        }
    }

    private fun ortSuperResolve(src: Bitmap): Bitmap? {
        val session = superSession ?: return null
        val wantedW = if (superInputW > 0) superInputW else src.width
        val wantedH = if (superInputH > 0) superInputH else src.height
        val inputBitmap = if (wantedW != src.width || wantedH != src.height) {
            Bitmap.createScaledBitmap(src, wantedW, wantedH, true)
        } else src
        return try {
            val plane = wantedW * wantedH
            val pixels = IntArray(plane)
            inputBitmap.getPixels(pixels, 0, wantedW, 0, 0, wantedW, wantedH)
            val input = FloatArray(3 * plane)
            for (i in pixels.indices) {
                val c = pixels[i]
                input[i] = android.graphics.Color.red(c) / 255f
                input[plane + i] = android.graphics.Color.green(c) / 255f
                input[2 * plane + i] = android.graphics.Color.blue(c) / 255f
            }
            val raw = infer(session, input, wantedH, wantedW) ?: return null
            val estimatedScale = if (superOutputW > 0 && superInputW > 0) {
                (superOutputW.toFloat() / superInputW).coerceIn(1f, 4f)
            } else 2f
            val outW = max(1, (wantedW * estimatedScale).toInt())
            val outH = max(1, (wantedH * estimatedScale).toInt())
            val expected = 3 * outW * outH
            if (raw.size != expected) return null
            bitmapFromPlanar(raw, outW, outH)
        } finally {
            if (inputBitmap !== src) inputBitmap.recycle()
        }
    }

    fun inferSuper(input: FloatArray, height: Int, width: Int): FloatArray? = infer(superSession, input, height, width)
    fun inferDeblur(input: FloatArray, height: Int, width: Int): FloatArray? = infer(deblurSession, input, height, width)

    private fun infer(session: OrtSession?, input: FloatArray, height: Int, width: Int): FloatArray? {
        val s = session ?: return null
        return try {
            val shape = longArrayOf(1L, 3L, height.toLong(), width.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    flatten(result[0].value)
                }
            }
        } catch (_: Throwable) { null }
    }

    private fun bitmapFromPlanar(v: FloatArray, width: Int, height: Int): Bitmap {
        val plane = width * height
        val pixels = IntArray(plane)
        var maxValue = 0f
        for (x in v) if (x > maxValue) maxValue = x
        val scaleIn = if (maxValue > 2f) 1f / 255f else 1f
        for (i in pixels.indices) {
            val r = (v[i] * scaleIn).coerceIn(0f, 1f)
            val g = (v[plane + i] * scaleIn).coerceIn(0f, 1f)
            val b = (v[2 * plane + i] * scaleIn).coerceIn(0f, 1f)
            pixels[i] = android.graphics.Color.rgb((r * 255f).toInt(), (g * 255f).toInt(), (b * 255f).toInt())
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun flatten(v: Any?): FloatArray? {
        val out = ArrayList<Float>()
        fun walk(x: Any?) {
            when (x) {
                is FloatArray -> x.forEach(out::add)
                is Array<*> -> x.forEach(::walk)
            }
        }
        walk(v)
        return if (out.isEmpty()) null else FloatArray(out.size) { out[it] }
    }

    override fun close() {
        try { NativeEngine.ncnnRelease() } catch (_: Throwable) {}
        superSession?.close(); deblurSession?.close()
        superSession = null; deblurSession = null
        ncnnAvailable = false; superResolutionAvailable = false; deblurAvailable = false
        scale = 1
    }
}
