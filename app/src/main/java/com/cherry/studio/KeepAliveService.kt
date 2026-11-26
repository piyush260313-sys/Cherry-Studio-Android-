package com.cherry.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicLong

/** Keeps the process alive while downloads run in the background and shows live progress. */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "dl"
        private const val NOTIF_ID = 1

        @Volatile private var isForeground = false
        @Volatile private var pendingStop = false
        private val lastUpdateMs = AtomicLong(0L)

        fun start(ctx: Context) {
            pendingStop = false
            val intent = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun updateProgress(ctx: Context, title: String, percent: Int, activeCount: Int) {
            if (!isForeground) return
            val now = System.currentTimeMillis()
            val prev = lastUpdateMs.get()
            if (now - prev < 450L && percent in 1..99) return
            if (!lastUpdateMs.compareAndSet(prev, now)) return

            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val subtitle = if (activeCount > 1) "$title (+${activeCount - 1} more)" else title
            nm.notify(NOTIF_ID, buildNotification(ctx, subtitle, percent))
        }

        fun stop(ctx: Context) {
            if (isForeground) {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } else {
                pendingStop = true
            }
        }

        private fun buildNotification(ctx: Context, text: String, percent: Int): Notification {
            val openIntent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                ctx, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Cherry Studio")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(true)

            if (percent in 0..100) {
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(100, 0, true)
            }
            return builder.build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (pendingStop) {
            pendingStop = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isForeground = false
        super.onDestroy()
    }

    private fun promoteToForeground() {
        if (isForeground) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(this, "Preparing download…", -1)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        isForeground = true
    }
}
