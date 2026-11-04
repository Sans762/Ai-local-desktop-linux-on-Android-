package com.localai.controller

import android.app.*
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
                val apk = fetchAndVerify(url, sha, size)
                val fileUri = FileProvider.getUriForFile(this, packageName + ".updates", apk)
                val installer = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                require(installer.resolveActivity(packageManager) != null) {
                    "ไม่พบโปรแกรมติดตั้ง APK ของ Android"
                }
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

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun certHashes(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: error("ไม่มีข้อมูลลายเซ็น APK")
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else info.signatures
        require(!signatures.isNullOrEmpty()) { "ไม่พบใบรับรองลายเซ็น" }
        return signatures.map { digest(it.toByteArray()) }.toSet()
    }

    private fun verifyInstallCompatibility(apk: File) {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = packageManager.getPackageInfo(packageName, flags)
        val candidate = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Android อ่าน APK ที่ดาวน์โหลดไม่ได้")
        require(candidate.packageName == packageName) {
            "ไฟล์อัปเดตเป็นคนละแอป จึงไม่สามารถรักษาข้อมูลเดิมได้"
        }
        require(candidate.longVersionCode > installed.longVersionCode) {
            "ไฟล์อัปเดตไม่ใช่เวอร์ชันใหม่กว่า"
        }
        require(certHashes(installed).intersect(certHashes(candidate)).isNotEmpty()) {
            "ลายเซ็น APK ไม่ตรงกับแอปเก่า ห้ามถอนแอปเพื่ออัปเดต เพราะข้อมูลจะหาย"
        }
    }

    private fun fetchAndVerify(link: String, expectedHash: String, expectedSize: Long): File {
        val root = File(cacheDir, "updates").apply { mkdirs() }
        val stage = File(root, "update.part")
        val target = File(root, "update.apk")
        stage.delete()
        target.delete()
        try {
            val connection = (URL(link).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LocalAI-Android-Update")
            }
            try {
                require(connection.responseCode == 200 && connection.url.protocol == "https") {
                    "การดาวน์โหลด HTTPS ไม่สำเร็จ: " + connection.responseCode
                }
                require(connection.contentLengthLong < 0 || connection.contentLengthLong == expectedSize) {
                    "ขนาดไฟล์ไม่ตรงกับ GitHub Releases"
                }
                val md = MessageDigest.getInstance("SHA-256")
                var amount = 0L
                var lastNotify = 0L
                connection.inputStream.use { input ->
                    stage.outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) error("การดาวน์โหลดถูกยกเลิก")
                            val n = input.read(buffer)
                            if (n < 0) break
                            amount += n
                            require(amount <= expectedSize) { "ไฟล์ดาวน์โหลดเกินขนาดที่ยืนยัน" }
                            md.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 1800) {
                                lastNotify = now
                                progress = "กำลังโหลดอัปเดต " + (amount * 100 / expectedSize) + "%"
                                notifyUpdate(progress, true, null)
                            }
                        }
                    }
                }
                require(amount == expectedSize) { "ดาวน์โหลดไฟล์ไม่ครบ" }
                val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                require(actual == expectedHash) { "SHA-256 ไม่ตรง จึงไม่ติดตั้ง" }
                require(stage.renameTo(target)) { "ไม่สามารถบันทึก APK" }
                verifyInstallCompatibility(target)
                return target
            } finally { connection.disconnect() }
        } catch (e: Exception) {
            stage.delete()
            target.delete()
            throw e
        }
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
