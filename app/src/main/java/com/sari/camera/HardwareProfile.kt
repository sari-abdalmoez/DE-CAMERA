package com.sari.camera

import android.app.ActivityManager
import android.content.Context

/** Runtime profile. It never claims hardware features; it only limits workload. */
enum class HardwareProfile(val maxBurst: Int, val maxPixels: Int, val workerThreads: Int) {
    LOW_END(6, 1_500_000, 2),
    MID_RANGE(10, 3_000_000, 3),
    FLAGSHIP(20, 6_000_000, 4);

    companion object {
        fun detect(context: Context): HardwareProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val ramMb = am.memoryClass
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                ramMb <= 2048 || cores <= 4 -> LOW_END
                ramMb <= 4096 || cores <= 6 -> MID_RANGE
                else -> FLAGSHIP
            }
        }
    }
}
