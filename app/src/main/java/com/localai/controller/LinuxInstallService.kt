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
import android.os.PowerManager

/** Long-running initial Linux extraction with visible progress; never overwrites existing rootfs. */
class LinuxInstallService : Service() {
    companion object {
        const val ACTION_STOP = "com.localai.controller.LINUX_INSTALL_STOP"
        private const val CHANNEL = "local_ai_linux_setup"
        @Volatile var running = false
        @Volatile var progress = "Linux has not been installed"
        @Volatile var lastError = ""
    }
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Alpine Linux installation", NotificationManager.IMPORTANCE_LOW))
        running = true
        progress = "Preparing built-in Alpine Linux…"
        lastError = ""
        update(true)
        worker = Thread({
            var wakeLock: PowerManager.WakeLock? = null
            try {
                val power = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "LocalAI:AlpineInstall"
                ).apply {
                    setReferenceCounted(false)
                    acquire(5L * 60_000L)
                }
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
                progress = if (Thread.currentThread().isInterrupted) "ยกเลิกการติดตั้ง Linux แล้ว"
                    else "Linux install failed: " + lastError
                update(false)
            } finally {
                try { if (wakeLock?.isHeld == true) wakeLock.release() }
                catch (_: Exception) { }
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
            .addAction(android.R.drawable.ic_media_pause, "หยุดติดตั้ง",
                PendingIntent.getService(this, 199,
                    Intent(this, LinuxInstallService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        if (active) {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(198, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(198, notification)
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(198, notification)
        }
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }
}
