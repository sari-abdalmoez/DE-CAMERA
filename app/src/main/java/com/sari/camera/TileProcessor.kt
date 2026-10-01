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
 * Memory-bounded tiled image pipeline.
 *
 * 0-6x   : native denoise/detail path.
 * 6-13x  : conservative AI super-resolution on overlapping tiles.
 * 13-20x : stronger reconstruction around the tapped subject, lighter elsewhere.
 *
 * The destination remains the original photo dimensions; the AI's extra pixels are
 * converted back into the tile's native size after inference. This keeps RAM bounded.
 */
class TileProcessor(
    private val ai: AiEngine? = null,
    private val controller: MemoryThermalController
) {
    fun process(src: Bitmap, strength: Float): Bitmap = processZoom(
        src = src,
        zoom = 1f,
        focusRoi = null,
        faceBoxes = FaceProtection.detect(src),
        onProgress = {}
    )

    fun processZoom(
        src: Bitmap,
        zoom: Float,
        focusRoi: RectF?,
        faceBoxes: List<RectF>,
        onProgress: (Int) -> Unit
    ): Bitmap {
        val budget = controller.budget()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val tileSize = if (zoom >= 13f) min(budget.tile, 320) else budget.tile
        val overlap = min(48, tileSize / 8)
        val coreStep = (tileSize - overlap * 2).coerceAtLeast(160)
        val totalX = ((src.width + coreStep - 1) / coreStep)
        val totalY = ((src.height + coreStep - 1) / coreStep)
        val total = totalX * totalY
        var done = 0

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

                val tileRect = RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
                val focusHit = focusRoi?.let { intersects(tileRect, it) } == true
                val faceHit = faceBoxes.any { intersects(tileRect, it) }
                val level = when {
                    zoom >= 13f -> 2
                    zoom >= 6f -> 1
                    else -> 0
                }
                val aiBlend = when (level) {
                    2 -> if (focusHit) budget.aiStrength13to20 else budget.aiStrength13to20 * 0.46f
                    1 -> if (focusHit) budget.aiStrength6to13 + 0.08f else budget.aiStrength6to13
                    else -> 0f
                }

                val tileOut = runTile(tile, level, aiBlend, faceHit, focusHit)

                val coreSrc = Rect(
                    (x - l).coerceAtLeast(0),
                    (y - t).coerceAtLeast(0),
                    (coreRight - l).coerceAtLeast(1),
                    (coreBottom - t).coerceAtLeast(1)
                )
                val coreDst = Rect(x, y, coreRight, coreBottom)
                canvas.drawBitmap(tileOut, coreSrc, coreDst, paint)

                tile.recycle()
                tileOut.recycle()
                x += coreStep
                done++
                onProgress(((done * 100f) / total).toInt().coerceIn(0, 100))
            }
            y += coreStep
        }
        return out
    }

    private fun runTile(tile: Bitmap, level: Int, aiBlend: Float, faceHit: Boolean, focusHit: Boolean): Bitmap {
        var base = tile
        try {
            val allowAi = level == 1 || (level >= 2 && (focusHit || faceHit))
            if (level > 0 && allowAi && ai != null && (ai.superResolutionAvailable || ai.ncnnAvailable)) {
                val aiResult = ai.superResolveTile(tile, strong = level >= 2)
                if (aiResult != null) {
                    val reduced = Bitmap.createScaledBitmap(aiResult, tile.width, tile.height, true)
                    aiResult.recycle()

                    // Faces are enhanced, but the original pixels remain dominant so identity,
                    // pore texture and geometry are not replaced by a synthetic face.
                    val evidence = detailEvidence(tile)
                    var safeBlend = if (faceHit) aiBlend * 0.74f else aiBlend
                    safeBlend *= when {
                        evidence < 0.08f -> 0.40f
                        evidence < 0.18f -> 0.66f
                        else -> 1f
                    }

                    // If the network changes the tile too aggressively, reduce the blend.
                    // This is a reconstruction guard: low-evidence or wildly different AI
                    // output must fall back toward the actual captured pixels.
                    val deviation = meanAbsoluteDeviation(tile, reduced)
                    if (deviation > 0.16f) {
                        safeBlend *= (0.16f / deviation).coerceIn(0.18f, 1f)
                    }
                    val mixed = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(mixed)
                    c.drawBitmap(tile, 0f, 0f, null)
                    val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                        alpha = (safeBlend.coerceIn(0f, 0.65f) * 255f).toInt()
                    }
                    c.drawBitmap(reduced, 0f, 0f, p)
                    reduced.recycle()
                    base = mixed
                }
            }

            // Always apply the native path after AI. It is intentionally mild and preserves
            // real edge/texture information from the camera instead of hallucinating detail.
            val rgbaIn = ByteBuffer.allocateDirect(base.width * base.height * 4)
            base.copyPixelsToBuffer(rgbaIn); rgbaIn.rewind()
            val rgbaOut = ByteBuffer.allocateDirect(base.width * base.height * 4)
            val nativeStrength = when (level) {
                2 -> 0.22f
                1 -> 0.18f
                else -> 0.30f
            }
            val nativeRc = NativeEngine.processTileRGBA(
                rgbaIn,
                rgbaOut,
                base.width,
                base.height,
                nativeStrength
            )

            if (nativeRc < 0) {
                if (base !== tile) base.recycle()
                return tile.copy(Bitmap.Config.ARGB_8888, true)
            }

            rgbaOut.rewind()
            val native = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            native.copyPixelsFromBuffer(rgbaOut)
            if (base !== tile) base.recycle()
            return native
        } catch (_: Throwable) {
            if (base !== tile) base.recycle()
            return tile.copy(Bitmap.Config.ARGB_8888, true)
        }
    }


    private fun detailEvidence(bitmap: Bitmap): Float {
        val step = 6
        var k = 0
        var sum = 0.0
        var sq = 0.0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val l = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
                k++
                sum += l
                sq += l * l
                x += step
            }
            y += step
        }
        if (k == 0) return 0f
        val mean = sum / k
        val variance = (sq / k - mean * mean).coerceAtLeast(0.0)
        return kotlin.math.sqrt(variance).toFloat()
    }

    private fun meanAbsoluteDeviation(a: Bitmap, b: Bitmap): Float {
        val step = 8
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val ca = a.getPixel(x, y)
                val cb = b.getPixel(x, y)
                val dr = kotlin.math.abs(Color.red(ca) - Color.red(cb)) / 255.0
                val dg = kotlin.math.abs(Color.green(ca) - Color.green(cb)) / 255.0
                val db = kotlin.math.abs(Color.blue(ca) - Color.blue(cb)) / 255.0
                sum += (dr + dg + db) / 3.0
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun intersects(a: RectF, b: RectF): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}
