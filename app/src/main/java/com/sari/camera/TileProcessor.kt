package com.sari.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Bounded-memory tile processor. Each tile has overlap context, but only its non-overlapping
 * core region is committed to the output. This avoids full-resolution accumulation buffers.
 */
class TileProcessor(
    private val tileSize: Int,
    private val overlap: Int = 48,
    private val ai: AiEngine? = null,
    private val faceBoxes: List<RectF> = emptyList()
) {
    fun process(src: Bitmap, strength: Float): Bitmap {
        val scale = ai?.scale?.coerceIn(1, 2) ?: 1
        val outW = src.width * scale
        val outH = src.height * scale
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val coreStep = (tileSize - overlap * 2).coerceAtLeast(128)

        var y = 0
        while (y < src.height) {
            val coreBottom = min(src.height, y + coreStep)
            var x = 0
            while (x < src.width) {
                val coreRight = min(src.width, x + coreStep)
                val l = maxOf(0, x - overlap)
                val t = maxOf(0, y - overlap)
                val r = min(src.width, coreRight + overlap)
                val b = min(src.height, coreBottom + overlap)
                val tile = Bitmap.createBitmap(src, l, t, r - l, b - t)
                val protected = faceBoxes.any {
                    it.intersects(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
                }
                val localStrength = (if (protected) strength * 0.22f else strength).coerceIn(0f, 1f)
                val tileOut = runTile(tile, localStrength, scale, protected)

                val srcCoreLeft = (x - l) * scale
                val srcCoreTop = (y - t) * scale
                val srcCoreRight = (coreRight - l) * scale
                val srcCoreBottom = (coreBottom - t) * scale
                val source = Rect(srcCoreLeft, srcCoreTop, srcCoreRight, srcCoreBottom)
                val dest = Rect(x * scale, y * scale, coreRight * scale, coreBottom * scale)
                canvas.drawBitmap(tileOut, source, dest, paint)

                tileOut.recycle()
                tile.recycle()
                x += coreStep
            }
            y += coreStep
        }
        return out
    }

    private fun runTile(tile: Bitmap, strength: Float, scale: Int, faceProtected: Boolean): Bitmap {
        val w = tile.width
        val h = tile.height
        val ints = IntArray(w * h)
        tile.getPixels(ints, 0, w, 0, 0, w, h)
        val plane = w * h
        val input = FloatArray(3 * plane)
        for (i in ints.indices) {
            val c = ints[i]
            input[i] = Color.red(c) / 255f
            input[plane + i] = Color.green(c) / 255f
            input[2 * plane + i] = Color.blue(c) / 255f
        }

        if (ai?.deblurAvailable == true && w >= 384 && h >= 384 && !faceProtected) {
            val restored = ai.inferDeblur(input, h, w)
            if (restored != null && restored.size == 3 * plane) {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val op = IntArray(plane)
                val blend = (0.20f + 0.28f * strength).coerceIn(0.20f, 0.48f)
                for (i in 0 until plane) {
                    val r = input[i] * (1f - blend) + restored[i].coerceIn(0f, 1f) * blend
                    val g = input[plane + i] * (1f - blend) + restored[plane + i].coerceIn(0f, 1f) * blend
                    val b = input[2 * plane + i] * (1f - blend) + restored[2 * plane + i].coerceIn(0f, 1f) * blend
                    op[i] = Color.rgb((r * 255f).coerceIn(0f, 255f).toInt(), (g * 255f).coerceIn(0f, 255f).toInt(), (b * 255f).coerceIn(0f, 255f).toInt())
                }
                out.setPixels(op, 0, w, 0, 0, w, h)
                return if (scale == 1 || ai.superResolutionAvailable.not()) out else superResolve(out, scale, strength)
            }
        }

        if (ai?.superResolutionAvailable == true) {
            val result = ai.inferSuper(input, h, w)
            val ow = w * scale
            val oh = h * scale
            if (result != null && result.size == 3 * ow * oh) {
                val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                val op = IntArray(ow * oh)
                for (i in op.indices) {
                    op[i] = Color.rgb(
                        (result[i].coerceIn(0f, 1f) * 255f).toInt(),
                        (result[ow * oh + i].coerceIn(0f, 1f) * 255f).toInt(),
                        (result[2 * ow * oh + i].coerceIn(0f, 1f) * 255f).toInt()
                    )
                }
                out.setPixels(op, 0, ow, 0, 0, ow, oh)
                val bicubic = Bitmap.createScaledBitmap(tile, ow, oh, true)
                val canvas = Canvas(bicubic)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = (46 + 64 * strength).toInt().coerceIn(46, 110)
                }
                canvas.drawBitmap(out, 0f, 0f, paint)
                out.recycle()
                return bicubic
            }
        }

        val inputBuffer = ByteBuffer.allocateDirect(w * h * 4)
        tile.copyPixelsToBuffer(inputBuffer)
        inputBuffer.rewind()
        val processed = ByteBuffer.allocateDirect(w * h * 4)
        NativeEngine.processTileRGBA(inputBuffer, processed, w, h, strength)
        processed.rewind()
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        base.copyPixelsFromBuffer(processed)
        return if (scale == 1) base else Bitmap.createScaledBitmap(base, w * scale, h * scale, true).also { base.recycle() }
    }

    private fun superResolve(input: Bitmap, scale: Int, strength: Float): Bitmap {
        if (scale == 1 || ai?.superResolutionAvailable != true) return input
        val w = input.width
        val h = input.height
        val plane = w * h
        val ints = IntArray(plane)
        input.getPixels(ints, 0, w, 0, 0, w, h)
        val f = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = ints[i]
            f[i] = Color.red(c) / 255f
            f[plane + i] = Color.green(c) / 255f
            f[2 * plane + i] = Color.blue(c) / 255f
        }
        val result = ai.inferSuper(f, h, w) ?: return input
        val ow = w * scale
        val oh = h * scale
        if (result.size != 3 * ow * oh) return input
        val neural = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val op = IntArray(ow * oh)
        for (i in op.indices) {
            op[i] = Color.rgb(
                (result[i].coerceIn(0f, 1f) * 255f).toInt(),
                (result[ow * oh + i].coerceIn(0f, 1f) * 255f).toInt(),
                (result[2 * ow * oh + i].coerceIn(0f, 1f) * 255f).toInt()
            )
        }
        neural.setPixels(op, 0, ow, 0, 0, ow, oh)
        val bicubic = Bitmap.createScaledBitmap(input, ow, oh, true)
        val canvas = Canvas(bicubic)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (50 + 60 * strength).toInt().coerceIn(50, 105)
        }
        canvas.drawBitmap(neural, 0f, 0f, paint)
        neural.recycle()
        input.recycle()
        return bicubic
    }
}
