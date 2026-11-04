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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream

/**
 * Imports a user-selected Local AI backup into a different Android package.
 * Never overwrites an existing Linux rootfs or model. Chat rows are merged.
 * This is a one-time migration tool, NOT a way around Android APK signing checks.
 */
class RestoreService : Service() {
    companion object {
        const val EXTRA_SOURCE = "backup_source_uri"
        private const val CHANNEL = "local_ai_restore"
        private const val NOTICE_ID = 198
        @Volatile var running = false
        @Volatile var progress = "ยังไม่เริ่มกู้คืน"
        @Volatile var lastError = ""
    }

    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val source = intent?.getParcelableExtra<Uri>(EXTRA_SOURCE)
            ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "กู้คืนข้อมูล Local AI", NotificationManager.IMPORTANCE_LOW))
        running = true
        lastError = ""
        progress = "กำลังตรวจไฟล์สำรอง…"
        notifyStatus(true)
        worker = Thread({
            try {
                require(!LinuxDesktopService.running && !LinuxDesktopService.installing &&
                    !LinuxInstallService.running && !AiForegroundService.running &&
                    !ModelDownloadService.running && !BackupService.running) {
                    "ปิด AI, หน้าจอ Linux และงานดาวน์โหลดก่อนกู้คืน"
                }
                restore(source)
                progress = "✓ กู้คืนเสร็จแล้ว เปิดแอปเพื่อตรวจข้อมูล"
                notifyStatus(false)
            } catch (e: Exception) {
                lastError = (e.message ?: "ไม่สามารถกู้คืนได้").take(500)
                progress = "กู้คืนไม่สำเร็จ: " + lastError
                notifyStatus(false)
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }, "local-ai-restore").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun restore(source: Uri) {
        val existingLinux = File(filesDir, "embedded_linux/alpine-rootfs")
        val staged = File(filesDir, "restore-staging-" + System.nanoTime())
        require(staged.mkdirs()) { "สร้างโฟลเดอร์กู้คืนไม่สำเร็จ" }
        try {
            val stream = contentResolver.openInputStream(source)
                ?: error("เปิดไฟล์สำรองที่เลือกไม่ได้")
            var manifest: JSONObject? = null
            var count = 0
            var total = 0L
            val links = mutableListOf<Pair<File, String>>()
            GZIPInputStream(stream.buffered(), 128 * 1024).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    while (true) {
                        if (Thread.currentThread().isInterrupted) error("ยกเลิกการกู้คืน")
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        count++
                        require(count <= 120_000) { "ไฟล์สำรองมีรายการมากเกินไป" }
                        val path = entry.name.removePrefix("./")
                        require(path.isNotBlank() && !path.startsWith("/") &&
                            !path.contains('\\') && !path.contains('\u0000') &&
                            path.split('/').none { it == ".." || it == "." }) {
                            "พบชื่อไฟล์ที่ไม่ปลอดภัยในไฟล์สำรอง"
                        }
                        if (path == "backup-info.json") {
                            require(entry.isFile && entry.size in 1..16384) {
                                "ข้อมูลหัวไฟล์สำรองไม่ถูกต้อง"
                            }
                            val headerBytes = ByteArray(entry.size.toInt())
                            var read = 0
                            while (read < headerBytes.size) {
                                val n = tar.read(headerBytes, read, headerBytes.size - read)
                                require(n > 0) { "หัวไฟล์สำรองเสียหรือถูกตัด" }
                                read += n
                            }
                            manifest = JSONObject(String(headerBytes, Charsets.UTF_8))
                            require(manifest!!.optString("format") == "localai-linux-backup" &&
                                manifest!!.optInt("version") in 1..2) {
                                "ไม่ใช่ไฟล์สำรอง Local AI ที่รองรับ"
                            }
                            continue
                        }
                        require(manifest != null) { "ไม่พบข้อมูลระบุชนิดไฟล์สำรอง" }
                        val group = path.substringBefore('/')
                        require(group in setOf("linux", "models", "databases") && path.contains('/')) {
                            "ไม่รองรับรายการในไฟล์สำรอง"
                        }
                        val component = path.substringAfter('/')
                        require(component.isNotBlank() || entry.isDirectory) { "โครงสร้างไฟล์ไม่ถูกต้อง" }
                        if (group == "databases" &&
                            component !in setOf("native-memory.db", "native-memory.db-wal",
                                "native-memory.db-shm")) {
                            // Ignore unrelated app DBs: never copy them into the new app.
                            continue
                        }
                        if (group == "linux" && existingLinux.exists()) continue
                        if (group == "models" &&
                            (entry.isSymbolicLink || (entry.isFile &&
                                !component.endsWith(".gguf", ignoreCase = true)))) {
                            error("ไฟล์สำรองมีโมเดลที่ไม่ปลอดภัย")
                        }
                        if (group == "databases" && entry.isSymbolicLink) {
                            error("ฐานข้อมูลสำรองต้องเป็นไฟล์ปกติ")
                        }
                        val target = File(staged, path.trimEnd('/'))
                        require(target.canonicalPath.startsWith(staged.canonicalPath +
                            File.separator)) { "พบไฟล์ที่พยายามออกนอกโฟลเดอร์กู้คืน" }
                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isSymbolicLink -> {
                                require(group == "linux" && entry.linkName.isNotBlank() &&
                                    !entry.linkName.contains('\u0000') &&
                                    entry.linkName.length <= 500) {
                                    "Symlink ในไฟล์สำรองไม่ถูกต้อง"
                                }
                                links.add(target to entry.linkName)
                            }
                            entry.isFile -> {
                                require(entry.size in 0..12_000_000_000L) {
                                    "ขนาดไฟล์สำรองผิดปกติ"
                                }
                                total += entry.size
                                require(total <= 16_000_000_000L) {
                                    "ข้อมูลสำรองใหญ่เกินขนาดที่รองรับ"
                                }
                                require(filesDir.usableSpace > entry.size + 128L * 1024 * 1024) {
                                    "พื้นที่มือถือไม่พอสำหรับกู้คืน"
                                }
                                target.parentFile?.mkdirs()
                                target.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var remain = entry.size
                                    while (remain > 0) {
                                        if (Thread.currentThread().isInterrupted) error("ยกเลิกการกู้คืน")
                                        val n = tar.read(buffer, 0, minOf(remain, buffer.size.toLong()).toInt())
                                        require(n > 0) { "ไฟล์สำรองไม่ครบหรือเสีย" }
                                        output.write(buffer, 0, n)
                                        remain -= n
                                    }
                                }
                                target.setExecutable(entry.mode and 0b001_001_001 != 0, false)
                            }
                            else -> error("ประเภทไฟล์สำรองที่ไม่รองรับ")
                        }
                        if (count % 80 == 0) {
                            progress = "กำลังกู้คืน " + count + " รายการ · " +
                                (total / 1048576) + " MB"
                            notifyStatus(true)
                        }
                    }
                }
            }
            require(manifest != null) { "ไม่พบข้อมูลไฟล์สำรอง" }
            for ((target, link) in links) {
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(link))
                }
            }
            var linuxRestored = false
            val rootfsStage = File(staged, "linux")
            if (!existingLinux.exists() && rootfsStage.isDirectory) {
                require(File(rootfsStage, "bin/busybox").isFile) {
                    "ไฟล์ Linux ในข้อมูลสำรองไม่สมบูรณ์"
                }
                existingLinux.parentFile?.mkdirs()
                require(rootfsStage.renameTo(existingLinux)) {
                    "ย้าย Linux จากไฟล์สำรองไม่สำเร็จ"
                }
                linuxRestored = true
            }
            val modelsStage = File(staged, "models")
            val modelsTarget = File(filesDir, "models").apply { mkdirs() }
            var modelsAdded = 0
            modelsStage.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf", true) }
                ?.forEach { file ->
                    val dest = File(modelsTarget, file.name)
                    if (!dest.exists() && file.length() > 1024) {
                        require(file.inputStream().use {
                            val magic = ByteArray(4)
                            it.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
                        }) { "ไฟล์โมเดลไม่ใช่ GGUF ที่ถูกต้อง" }
                        require(file.renameTo(dest)) { "ย้ายโมเดล " + file.name + " ไม่สำเร็จ" }
                        modelsAdded++
                    }
                }
            val dbStage = File(staged, "databases/native-memory.db")
            var chatsImported = false
            if (dbStage.isFile && dbStage.length() > 256) {
                val db = ChatDatabase(this)
                try {
                    db.importLegacy(dbStage)
                    chatsImported = true
                } finally {
                    db.close()
                }
            }
            val settings = AppStore(this)
            val backupModel = manifest!!.optString("model_file")
            if (settings.selectedModel().isBlank()) {
                val selected = File(modelsTarget, backupModel).takeIf {
                    backupModel.matches(Regex("[A-Za-z0-9._-]{1,100}[.]gguf")) &&
                        it.isFile
                } ?: modelsTarget.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".gguf", true) }
                if (selected != null) settings.setSelectedModel(selected.name)
            }
            val backupName = manifest!!.optString("ai_name").take(60)
            if (settings.aiName() == "Local AI" && backupName.isNotBlank()) {
                settings.setAiName(backupName)
            }
            progress = "✓ Linux " + (if (linuxRestored) "กู้คืนแล้ว" else "คงข้อมูลเดิม") +
                " · โมเดลใหม่ " + modelsAdded +
                " · แชท " + (if (chatsImported) "นำเข้าแล้ว" else "ไม่มีข้อมูล")
        } finally {
            // Never follow archive symlinks when removing temporary files.
            if (staged.exists()) {
                Files.walkFileTree(staged.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }
                    override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
            }
        }
    }

    private fun notifyStatus(working: Boolean) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = CHANNEL
        val intent = PendingIntent.getActivity(this, NOTICE_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (working) "กำลังกู้คืน Linux และ AI" else "ผลการกู้คืน Local AI")
            .setContentText(progress.take(140))
            .setContentIntent(intent)
            .setOnlyAlertOnce(true)
            .setOngoing(working)
            .build()
        if (working) {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTICE_ID, notice)
        } else manager.notify(NOTICE_ID, notice)
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }
}
