package com.sari.camera

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/** Mobile AI runtime. Models are verified by CI before they are bundled. */
class AiEngine(private val context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private var superSession: OrtSession? = null
    private var deblurSession: OrtSession? = null
    var superResolutionAvailable = false; private set
    var deblurAvailable = false; private set
    var scale = 1; private set

    fun loadModels(superResolution: File?, deblur: File?): Boolean {
        superResolutionAvailable = loadSuper(superResolution)
        deblurAvailable = loadDeblur(deblur)
        return superResolutionAvailable || deblurAvailable
    }

    private fun loadSuper(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() < 1024) return false
        return try {
            superSession?.close()
            val s = newSession(file)
            val input = tensorInfo(s.inputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            val output = tensorInfo(s.outputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            if (input.shape.size != 4 || input.shape[1] != 3L || output.shape.size != 4 || output.shape[1] != 3L) return false.also { s.close() }
            val ih = input.shape[2]; val oh = output.shape[2]
            scale = if (ih > 0 && oh > 0) (oh / ih).toInt().coerceIn(1, 4) else 1
            superSession = s; true
        } catch (_: Throwable) { false }
    }

    private fun loadDeblur(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() < 1024) return false
        return try {
            deblurSession?.close()
            val s = newSession(file)
            val input = tensorInfo(s.inputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            val output = tensorInfo(s.outputInfo.values.firstOrNull()?.info) ?: return false.also { s.close() }
            if (input.shape.size != 4 || input.shape[1] != 3L || output.shape.size != 4 || output.shape[1] != 3L) return false.also { s.close() }
            deblurSession = s; true
        } catch (_: Throwable) { false }
    }

    private fun newSession(file: File): OrtSession = OrtEnvironment.getEnvironment().createSession(file.absolutePath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2); setInterOpNumThreads(1); setMemoryPatternOptimization(true)
    })

    private fun tensorInfo(info: ai.onnxruntime.ValueInfo?): ai.onnxruntime.TensorInfo? = info as? ai.onnxruntime.TensorInfo

    fun inferSuper(input: FloatArray, height: Int, width: Int): FloatArray? = infer(superSession, input, height, width)
    fun inferDeblur(input: FloatArray, height: Int, width: Int): FloatArray? = infer(deblurSession, input, height, width)

    private fun infer(session: OrtSession?, input: FloatArray, height: Int, width: Int): FloatArray? {
        val s = session ?: return null
        return try {
            val shape = longArrayOf(1L, 3L, height.toLong(), width.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result -> flatten(result[0].value) }
            }
        } catch (_: Throwable) { null }
    }

    private fun flatten(v: Any?): FloatArray? {
        val out = ArrayList<Float>()
        fun walk(x: Any?) { when (x) { is FloatArray -> x.forEach(out::add); is Array<*> -> x.forEach(::walk) } }
        walk(v); return if (out.isEmpty()) null else FloatArray(out.size) { out[it] }
    }

    override fun close() {
        superSession?.close(); deblurSession?.close(); superSession = null; deblurSession = null
        superResolutionAvailable = false; deblurAvailable = false; scale = 1
    }
}
