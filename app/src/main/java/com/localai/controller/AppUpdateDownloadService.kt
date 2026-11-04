package com.localai.controller

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.net.URL

/** Download only owner-signed APK updates. Never remove data or install silently. */
class AppUpdateDownloadService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "com.localai.controller.UPDATE_DOWNLOAD"
        const val EXTRA_URL = "release_url"
        const val EXTRA_SHA = "release_sha256"
        const val EXTRA_SIZE = "release_size"
        const val EXTRA_VERSION = "release_version"
        private const val CHANNEL = "local_ai_app_updates"
        private const val ID = 203
        @Volatile var downloading = false
        @Volatile var progress = "ยังไม่ได้ดาวน์โหลด"
        @Volatile var lastError = ""
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (downloading) return START_NOT_STICKY
        if (intent?.action != ACTION_DOWNLOAD) { stopSelf(); return START_NOT_STICKY }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val sha = intent.getStringExtra(EXTRA_SHA).orEmpty().lowercase()
        val size = intent.getLongExtra(EXTRA_SIZE, 0)
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty().take(30)
        val validUrl = try {
            val parsed = URL(url)
            parsed.protocol == "https" && parsed.host == "github.com" &&
                parsed.path.matches(Regex(
                    "/Sans762/Ai-local-desktop-linux-on-Android-/releases/download/" +
                    "v[0-9]+[.][0-9]+-linux-desktop/" +
                    "LocalAI-LinuxDesktop-v[0-9]+[.][0-9]+-signed-ARM64[.]apk"
                ))
        } catch (_: Exception) { false }
        if (!validUrl || !sha.matches(Regex("[a-f0-9]{64}")) ||
            size !in 1..150_000_000L || version.isBlank()) {
            lastError = "ข้อมูลอัปเดตไม่ถูกต้อง"
            stopSelf()
            return START_NOT_STICKY
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW))
        downloading = true
        lastError = ""
        progress = "กำลังเริ่มดาวน์โหลดอัปเดต " + version
        notifyUpdate(progress, true, null)
        Thread({
            try {
                var lastNoticeAt = 0L
                val apk = VerifiedApkUpdate.download(this, url, sha, size) { amount, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastNoticeAt > 1500L) {
                        lastNoticeAt = now
                        progress = "กำลังดาวน์โหลดอัปเดต " + (amount * 100 / total) + "%"
                        notifyUpdate(progress, true, null)
                    }
                }
                val installer = VerifiedApkUpdate.installIntent(this, apk)
                AutoUpdateScheduler.rememberReady(this, version)
                progress = "ดาวน์โหลดและตรวจสอบผ่าน · แตะเพื่อติดตั้ง " + version
                notifyUpdate(progress, false, installer)
            } catch (e: Exception) {
                lastError = e.message?.take(250) ?: "ไม่สามารถอัปเดตได้"
                progress = "อัปเดตไม่สำเร็จ: " + lastError
                notifyUpdate(progress, false, null)
            } finally {
                downloading = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }, "local-ai-update").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun notifyUpdate(message: String, working: Boolean, installer: Intent?) {
        val intent = installer ?: Intent(this, MainActivity::class.java)
        val action = PendingIntent.getActivity(this, ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (installer != null) "อัปเดตพร้อมติดตั้ง" else "อัปเดต Local AI")
            .setContentText(message.take(140))
            .setOngoing(working)
            .setAutoCancel(!working)
            .setOnlyAlertOnce(true)
            .setContentIntent(action)
            .build()
        if (working) {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification)
        }
    }
}
