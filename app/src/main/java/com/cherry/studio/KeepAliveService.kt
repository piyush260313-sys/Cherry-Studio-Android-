package com.cherry.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the process alive while downloads run in the background. */
class KeepAliveService : Service() {

    companion object {
        @Volatile private var isForeground = false
        @Volatile private var pendingStop = false

        fun start(ctx: Context) {
            pendingStop = false
            val intent = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            if (isForeground) {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } else {
                // Service hasn't reached startForeground() yet; let it stop itself safely
                pendingStop = true
            }
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
            NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "dl")
            .setContentTitle("Cherry Studio")
            .setContentText("Downloading…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        isForeground = true
    }
}
