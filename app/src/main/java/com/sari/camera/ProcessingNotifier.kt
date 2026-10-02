package com.sari.camera

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import kotlin.math.max

/**
 * Processing notification with live percentage, measured elapsed time and a
 * continuously refreshed ETA based on the measured progress rate.
 */
class ProcessingNotifier(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "sari_processing"
        private const val NOTIFICATION_ID = 7401
    }

    private val manager = NotificationManagerCompat.from(context)
    private val tickerHandler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var lastProgress = 0
    private var currentTitle = "Processing"
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            publish(currentTitle, lastProgress)
            tickerHandler.postDelayed(this, 1000L)
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SARI Camera processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live photo processing progress and measured time"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun start(title: String) {
        startedAt = android.os.SystemClock.elapsedRealtime()
        lastProgress = 0
        currentTitle = title
        running = true
        tickerHandler.removeCallbacks(ticker)
        publish(title, 0)
        tickerHandler.postDelayed(ticker, 1000L)
    }

    fun update(title: String, progress: Int) {
        if (!running) start(title)
        currentTitle = title
        lastProgress = progress.coerceIn(0, 100)
        publish(currentTitle, lastProgress)
    }

    fun finish(title: String, success: Boolean) {
        val elapsedMs = if (startedAt == 0L) 0L else android.os.SystemClock.elapsedRealtime() - startedAt
        running = false
        tickerHandler.removeCallbacks(ticker)
        val text = if (success) {
            "Completed • ${formatDuration(elapsedMs)}"
        } else {
            "Failed • ${formatDuration(elapsedMs)}"
        }
        if (!notificationsAllowed()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("SARI Camera • $title")
            .setContentText(text)
            .setProgress(100, if (success) 100 else lastProgress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
        manager.notify(NOTIFICATION_ID, builder.build())
        startedAt = 0L
        lastProgress = 0
    }

    private fun publish(title: String, progress: Int) {
        if (!notificationsAllowed()) return
        val elapsedMs = (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        val elapsed = formatDuration(elapsedMs)
        val eta = if (progress >= 3 && progress < 100) {
            val remaining = elapsedMs.toDouble() * (100 - progress).toDouble() / progress.toDouble()
            formatDuration(remaining.toLong())
        } else if (progress >= 100) "00:00" else "—"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("SARI Camera • $title")
            .setContentText("$progress% • Elapsed $elapsed • ETA $eta")
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun formatDuration(ms: Long): String {
        val totalSeconds = max(0L, ms / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
