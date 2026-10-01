package com.sari.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream

object ImageSaver {
    fun saveJpeg(context: Context, bitmap: Bitmap, astro: Boolean = false): Uri? {
        val path = if (astro) "Pictures/SARI Camera/Astro" else "Pictures/SARI Camera"
        val v = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "SARI_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, path); else put(MediaStore.Images.Media.DATA, java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "SARI_${System.currentTimeMillis()}.jpg").absolutePath); if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1) }
        val resolver = context.contentResolver; val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        return try { resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }; if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null); uri } catch (e: Exception) { resolver.delete(uri,null,null); null }
    }
}
