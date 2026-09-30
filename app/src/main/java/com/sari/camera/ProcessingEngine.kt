package com.sari.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.sqrt

/** Bounded, cancellable CPU pipeline. JPEG is decoded before native processing. */
object ProcessingEngine {
    data class GrayFrame(val pixels: ByteArray, val width: Int, val height: Int)

    fun decodeGray(jpeg: ByteArray, maxPixels: Int): GrayFrame? = try {
        if (jpeg.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth / sample) * (bounds.outHeight / sample) > maxPixels && sample < 16) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        try {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            val g = ByteArray(w * h)
            for (i in px.indices) { val c=px[i]; g[i]=(0.2126f*((c ushr 16) and 255)+0.7152f*((c ushr 8) and 255)+0.0722f*(c and 255)).toInt().coerceIn(0,255).toByte() }
            GrayFrame(g,w,h)
        } finally { bmp.recycle() }
    } catch (_: OutOfMemoryError) { null } catch (_: Throwable) { null }

    fun stackJpegs(jpegs: List<ByteArray>, maxPixels: Int, sigma: Float = 2.5f): ByteArray? {
        if (jpegs.isEmpty()) return null
        val decoded = ArrayList<GrayFrame>(jpegs.size)
        for (jpeg in jpegs) decodeGray(jpeg, maxPixels)?.let { decoded.add(it) }
        if (decoded.size < 2) return null
        val w = decoded.first().width
        val h = decoded.first().height
        if (decoded.any { it.width != w || it.height != h }) return null
        val result = NativeBridge.alignAndStack(decoded.map { it.pixels }.toTypedArray(), w, h, sigma)
        val enhanced = NativeBridge.enhance(result, w, h, 1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val colors = IntArray(w * h) { val v = enhanced[it].toInt() and 255; -0x1000000 or (v shl 16) or (v shl 8) or v }
        bmp.setPixels(colors, 0, w, 0, 0, w, h)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)) null else out.toByteArray()
            }
        } finally { bmp.recycle() }
    }
}
