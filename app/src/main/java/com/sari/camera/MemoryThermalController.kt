package com.sari.camera

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

class MemoryThermalController(private val context: Context) {
    enum class Profile { LOW_MEMORY, BALANCED, HIGH_PERFORMANCE }

    data class Budget(
        val profile: Profile,
        val tile: Int,
        val workers: Int,
        val maxFrames: Int,
        val imageMaxDimension: Int,
        val aiStrength6to13: Float,
        val aiStrength13to20: Float
    )

    fun budget(): Budget {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val total = mi.totalMem / (1024L * 1024L)
        val avail = mi.availMem / (1024L * 1024L)
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } else PowerManager.THERMAL_STATUS_NONE

        return when {
            mi.lowMemory || total <= 3072 || avail < 700 || thermal >= PowerManager.THERMAL_STATUS_SEVERE ->
                Budget(
                    Profile.LOW_MEMORY,
                    tile = 384,
                    workers = 1,
                    maxFrames = 4,
                    imageMaxDimension = 1792,
                    aiStrength6to13 = 0.22f,
                    aiStrength13to20 = 0.30f
                )

            total >= 6144 && avail >= 2200 && thermal <= PowerManager.THERMAL_STATUS_MODERATE ->
                Budget(
                    Profile.HIGH_PERFORMANCE,
                    tile = 640,
                    workers = 2,
                    maxFrames = 10,
                    imageMaxDimension = 2816,
                    aiStrength6to13 = 0.34f,
                    aiStrength13to20 = 0.44f
                )

            else ->
                Budget(
                    Profile.BALANCED,
                    tile = 448,
                    workers = 1,
                    maxFrames = 6,
                    imageMaxDimension = 2304,
                    aiStrength6to13 = 0.28f,
                    aiStrength13to20 = 0.38f
                )
        }
    }
}
