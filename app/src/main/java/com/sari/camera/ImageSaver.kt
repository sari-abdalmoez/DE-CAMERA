package com.sari.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object ImageSaver {
    fun saveJpeg(context: Context, bitmap: android.graphics.Bitmap, astro: Boolean = false): Uri? {
        val name = "SARI_${System.currentTimeMillis()}.jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val path = if (astro) "Pictures/SARI Camera/Astro" else "Pictures/SARI Camera"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 96, it)
                }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                uri
            } catch (_: Throwable) {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                if (astro) "SARI Camera/Astro" else "SARI Camera"
            ).apply { mkdirs() }
            val file = File(dir, name)
            return try {
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 96, it) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
                Uri.fromFile(file)
            } catch (_: Throwable) {
                file.delete()
                null
            }
        }
    }
}
