package com.localai.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Automatic updates for the permanently signed app.
 * Checks and downloads on unmetered networks, never installs silently.
 */
object AutoUpdateScheduler {
    private const val PREFS = "localai_signed_updates"
    private const val PERIODIC_ID = 4701
    private const val SOON_ID = 4702
    private const val PERIOD_MS = 12L * 60 * 60 * 1000
    private const val CHECK_GAP_MS = 3L * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun canUpdate(context: Context) = !context.packageName.contains(".test")
    fun enabled(context: Context) =
        canUpdate(context) && prefs(context).getBoolean("auto_update", true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_update", value).apply()
        sync(context)
    }

    fun status(context: Context): String {
        if (!canUpdate(context)) return "รุ่น TEST ไม่รองรับอัปเดตทับ"
        val pending = prefs(context).getString("ready_version", "").orEmpty()
        if (pending.isNotEmpty() && readyFile(context) != null) {
            return "ดาวน์โหลดเวอร์ชัน " + pending + " แล้ว · พร้อมติดตั้ง"
        }
        return prefs(context).getString("status", "ตรวจหาอัปเดตบน Wi-Fi อัตโนมัติ").orEmpty()
    }

    fun readyFile(context: Context): File? {
        val file = VerifiedApkUpdate.updateFile(context)
        return file.takeIf {
            prefs(context).getString("ready_version", "").orEmpty().isNotBlank() &&
                it.isFile && it.length() in 1..150_000_000L
        }
    }

    fun pendingVersion(context: Context): String =
        prefs(context).getString("ready_version", "").orEmpty()

    fun rememberCheck(context: Context, result: String) {
        prefs(context).edit()
            .putLong("last_check", System.currentTimeMillis())
            .putString("status", result.take(180))
            .apply()
    }

    fun rememberReady(context: Context, version: String) {
        prefs(context).edit()
            .putString("ready_version", version.take(50))
            .putString("status", "ดาวน์โหลดอัปเดตเสร็จแล้ว")
            .putLong("last_check", System.currentTimeMillis())
            .apply()
    }

    fun clearReady(context: Context) {
        prefs(context).edit().remove("ready_version").apply()
    }

    fun sync(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (!enabled(context)) {
            scheduler.cancel(PERIODIC_ID)
            scheduler.cancel(SOON_ID)
            return
        }
        val component = ComponentName(context, AutoUpdateJobService::class.java)
        if (scheduler.getPendingJob(PERIODIC_ID) == null) {
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .build()
            )
        }
        val elapsed = System.currentTimeMillis() - prefs(context).getLong("last_check", 0L)
        if (elapsed >= CHECK_GAP_MS && scheduler.getPendingJob(SOON_ID) == null &&
            readyFile(context) == null) {
            scheduler.schedule(
                JobInfo.Builder(SOON_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setMinimumLatency(3_000L)
                    .build()
            )
        }
    }
}

class AutoUpdateJobService : JobService() {
    companion object {
        private const val CHANNEL = "local_ai_signed_update_ready"
        private const val NOTIFICATION_ID = 4712
    }
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!AutoUpdateScheduler.enabled(this)) return false
        if (VerifiedApkUpdate.isBusy() || AppUpdateDownloadService.downloading) {
            return false
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "อัปเดต Local AI", NotificationManager.IMPORTANCE_DEFAULT))
        worker = Thread({
            try {
                if (!AutoUpdateScheduler.enabled(this)) return@Thread
                val release = AppUpdateManager(this).checkLatest()
                if (!release.newer) {
                    AutoUpdateScheduler.rememberCheck(this, "เป็นเวอร์ชันล่าสุดแล้ว")
                    return@Thread
                }
                if (AutoUpdateScheduler.pendingVersion(this) == release.version &&
                    AutoUpdateScheduler.readyFile(this) != null) {
                    return@Thread
                }
                AutoUpdateScheduler.rememberCheck(this, "พบเวอร์ชัน " + release.version + " · กำลังดาวน์โหลด")
                var lastUpdate = 0L
                val apk = VerifiedApkUpdate.download(this, release.downloadUrl,
                    release.sha256, release.sizeBytes) { transferred, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 2_000L) {
                        lastUpdate = now
                        val percent = (transferred * 100 / total).toInt().coerceIn(0, 100)
                        manager.notify(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle("กำลังอัปเดต Local AI")
                            .setContentText("ดาวน์โหลด " + release.version + " · " + percent + "%")
                            .setProgress(100, percent, false)
                            .setOnlyAlertOnce(true)
                            .setOngoing(true)
                            .build())
                    }
                }
                val installer = VerifiedApkUpdate.installIntent(this, apk)
                AutoUpdateScheduler.rememberReady(this, release.version)
                val action = PendingIntent.getActivity(
                    this, NOTIFICATION_ID, installer,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val ready = Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Local AI อัปเดตพร้อมติดตั้ง")
                    .setContentText("โหลดเวอร์ชัน " + release.version + " แล้ว · แตะเพื่อยืนยันติดตั้ง")
                    .setContentIntent(action)
                    .setAutoCancel(true)
                    .build()
                manager.notify(NOTIFICATION_ID, ready)
            } catch (e: Exception) {
                AutoUpdateScheduler.rememberCheck(this,
                    e.message?.take(150) ?: "การตรวจอัปเดตไม่สำเร็จ")
                manager.cancel(NOTIFICATION_ID)
            } finally {
                jobFinished(params, false)
            }
        }, "signed-background-updates").apply { isDaemon = true; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        return true
    }
}
