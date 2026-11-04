package com.localai.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Exports Linux, models, and chat DB only to the destination explicitly chosen by the user. */
class BackupService : Service() {
    companion object {
        const val EXTRA_DESTINATION = "backup_destination_uri"
        private const val CHANNEL = "local_ai_backup"
        @Volatile var running = false
        @Volatile var progress = "Not started"
        @Volatile var lastError = ""
    }
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val uri = intent?.getParcelableExtra<Uri>(EXTRA_DESTINATION)
            ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Linux backup and export", NotificationManager.IMPORTANCE_LOW))
        running = true
        lastError = ""
        progress = "Preparing backup…"
        notifyStatus(progress, true)
        worker = Thread({
            try {
                require(!LinuxDesktopService.running && !LinuxDesktopService.installing &&
                    !AiForegroundService.running && !ModelDownloadService.running) {
                    "Stop Linux Desktop, AI and downloads before backing up"
                }
                export(uri)
                progress = "Backup exported successfully"
                notifyStatus(progress, false)
            } catch (e: Exception) {
                lastError = e.message?.take(320) ?: "Backup failed"
                progress = "Backup failed: " + lastError
                notifyStatus(progress, false)
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }, "linux-backup").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun export(destination: Uri) {
        val paths = listOf(
            File(filesDir, "embedded_linux/alpine-rootfs") to "linux",
            File(filesDir, "models") to "models",
            getDatabasePath("native-memory.db").parentFile!! to "databases"
        )
        require(paths.any { it.first.exists() }) { "No Linux, model or chat data to export" }
        val output = contentResolver.openOutputStream(destination, "w")
            ?: error("Cannot write to selected backup file")
        output.use { stream ->
            GZIPOutputStream(stream.buffered(), 128 * 1024).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    val manifest = JSONObject()
                        .put("format", "localai-linux-backup")
                        .put("version", 2)
                        .put("ai_name", AppStore(this).aiName())
                        .put("model_file", AppStore(this).selectedModel())
                        .put("createdAt", System.currentTimeMillis())
                        .put("appVersion", packageManager.getPackageInfo(packageName, 0).versionName)
                        .put("note", "Unencrypted; may contain sensitive user data")
                        .toString().toByteArray(Charsets.UTF_8)
                    val header = TarArchiveEntry("backup-info.json")
                    header.size = manifest.size.toLong()
                    tar.putArchiveEntry(header)
                    tar.write(manifest)
                    tar.closeArchiveEntry()
                    var count = 0L
                    var bytes = 0L
                    var previousNotice = 0L

                    fun add(file: File, name: String, depth: Int) {
                        require(depth < 65) { "Nested backup path too deep" }
                        if (Thread.currentThread().isInterrupted) error("Backup interrupted")
                        val path = name.replace('\\', '/').removePrefix("/")
                        require(!path.contains("../") && !path.contains('\u0000')) {
                            "Invalid backup entry path"
                        }
                        if (Files.isSymbolicLink(file.toPath())) {
                            val entry = TarArchiveEntry(path, TarConstants.LF_SYMLINK)
                            entry.linkName = Files.readSymbolicLink(file.toPath()).toString()
                            entry.mode = 511
                            tar.putArchiveEntry(entry)
                            tar.closeArchiveEntry()
                        } else if (file.isDirectory) {
                            val entry = TarArchiveEntry(path + "/")
                            entry.mode = 493
                            tar.putArchiveEntry(entry)
                            tar.closeArchiveEntry()
                            for (child in file.listFiles().orEmpty()) {
                                add(child, path + "/" + child.name, depth + 1)
                            }
                        } else if (file.isFile) {
                            val entry = TarArchiveEntry(path)
                            entry.size = file.length()
                            entry.mode = if (file.canExecute()) 493 else 420
                            tar.putArchiveEntry(entry)
                            file.inputStream().buffered().use { input ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) error("Backup interrupted")
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    tar.write(buffer, 0, n)
                                    bytes += n
                                    val now = System.currentTimeMillis()
                                    if (now - previousNotice > 2500L) {
                                        previousNotice = now
                                        progress = "Saving " + (bytes / 1048576) + " MiB · " + count + " files"
                                        notifyStatus(progress, true)
                                    }
                                }
                            }
                            tar.closeArchiveEntry()
                            count++
                        }
                    }
                    for ((file, name) in paths) if (file.exists()) add(file, name, 0)
                    tar.finish()
                    progress = "Saved " + (bytes / 1048576) + " MiB from " + count + " files"
                }
            }
        }
    }

    private fun notifyStatus(text: String, busy: Boolean) {
        val launch = PendingIntent.getActivity(
            this, 197, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle(if (busy) "Backing up Linux + AI" else "Local AI backup")
            .setContentText(text.take(140))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(launch)
            .setOnlyAlertOnce(true)
            .setOngoing(busy)
            .build()
        if (busy) {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(197, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(197, note)
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(197, note)
        }
    }
}
