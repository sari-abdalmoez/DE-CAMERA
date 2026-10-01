package com.sari.camera

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.graphics.RectF

/** Detection-only face protection. It never edits facial geometry or synthesizes face detail. */
object FaceProtection {
    fun detect(bitmap: Bitmap): List<RectF> {
        return try {
            val w=320; val h=(bitmap.height.toFloat()/bitmap.width*w).toInt().coerceAtLeast(64)
            val scaled=bitmap.copy(Bitmap.Config.RGB_565,false); val small=Bitmap.createScaledBitmap(scaled,w,h,true);scaled.recycle()
            val faces=arrayOfNulls<FaceDetector.Face>(16); val n=FaceDetector(w,h,faces.size).findFaces(small,faces); val out=mutableListOf<RectF>(); val p=PointF()
            for(i in 0 until n){val f=faces[i]?:continue;f.getMidPoint(p);val d=f.eyesDistance();out.add(RectF((p.x-d*1.7f)*bitmap.width/w,(p.y-d*2.0f)*bitmap.height/h,(p.x+d*1.7f)*bitmap.width/w,(p.y+d*2.0f)*bitmap.height/h))};small.recycle();out
        } catch(_:Throwable){emptyList()}
    }
}
