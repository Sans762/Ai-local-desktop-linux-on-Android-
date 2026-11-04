package com.localai.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Long-running initial Linux extraction with visible progress; never overwrites existing rootfs. */
class LinuxInstallService : Service() {
    companion object {
        private const val CHANNEL = "local_ai_linux_setup"
        @Volatile var running = false
        @Volatile var progress = "Linux has not been installed"
        @Volatile var lastError = ""
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Alpine Linux installation", NotificationManager.IMPORTANCE_LOW))
        running = true
        progress = "Preparing built-in Alpine Linux…"
        lastError = ""
        update(true)
        Thread({
            try {
                require(!LinuxDesktopService.running && !LinuxDesktopService.installing &&
                    !AiForegroundService.running && !BackupService.running) {
                    "Stop other Linux tasks before installing Alpine"
                }
                LinuxRuntime(this).install { item ->
                    progress = item
                    update(true)
                }
                progress = "Alpine installed successfully"
                update(false)
            } catch (e: Exception) {
                lastError = e.message?.take(250) ?: "Unknown error"
                progress = "Linux install failed: " + lastError
                update(false)
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }, "alpine-linux-setup").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun update(active: Boolean) {
        val open = PendingIntent.getActivity(this, 198, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active) "Installing Alpine Linux" else "Alpine Linux setup")
            .setContentText(progress.take(130))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .build()
        if (active) {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(198, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(198, notification)
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(198, notification)
        }
    }
}
