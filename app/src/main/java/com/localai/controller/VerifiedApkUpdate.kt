package com.localai.controller

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared verifier for foreground and scheduled updates.
 * Downloaded APKs are NEVER installed without the Android system's confirmation.
 */
object VerifiedApkUpdate {
    private val inFlight = AtomicBoolean(false)
    private const val MAX_APK_SIZE = 150_000_000L
    private val repoPath = "/Sans762/Ai-local-desktop-linux-on-Android-/releases/download/"
    private val assetPath = Regex("^v[0-9]+\\.[0-9]+-linux-desktop/LocalAI-LinuxDesktop-v[0-9]+\\.[0-9]+-signed-ARM64\\.apk$")

    fun updateFile(context: Context): File = File(File(context.cacheDir, "updates"), "update.apk")

    fun isBusy(): Boolean = inFlight.get()

    private fun certHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun signatures(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signer = info.signingInfo ?: error("ไม่พบลายเซ็นของแอป")
            if (signer.hasMultipleSigners()) signer.apkContentsSigners
            else signer.signingCertificateHistory
        } else info.signatures
        require(!certs.isNullOrEmpty()) { "ไม่พบใบรับรอง APK" }
        return certs.map { certHash(it.toByteArray()) }.toSet()
    }

    fun assertCompatible(context: Context, file: File) {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("ไฟล์ APK เสียหรือ Android อ่านไม่ได้")
        require(candidate.packageName == installed.packageName) {
            "อัปเดตเป็นคนละแอป จึงไม่ติดตั้งทับให้ข้อมูลเสียหาย"
        }
        require(candidate.longVersionCode > installed.longVersionCode) {
            "เวอร์ชันนี้ไม่ใหม่กว่าแอปที่ติดตั้งอยู่"
        }
        require(signatures(installed).intersect(signatures(candidate)).isNotEmpty()) {
            "ลายเซ็นแอปไม่ตรงกัน จึงไม่ติดตั้งทับและไม่ลบข้อมูลเดิม"
        }
    }

    fun installIntent(context: Context, apk: File): Intent {
        assertCompatible(context, apk)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun download(
        context: Context,
        source: String,
        expectedSha: String,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        require(inFlight.compareAndSet(false, true)) { "กำลังดาวน์โหลดอัปเดตอยู่แล้ว" }
        try {
            return downloadLocked(context, source, expectedSha, expectedBytes, onProgress)
        } finally {
            inFlight.set(false)
        }
    }

    private fun downloadLocked(
        context: Context,
        source: String,
        expectedSha: String,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): File {
        require(!context.packageName.contains(".test")) {
            "รุ่น TEST ไม่รองรับการอัปเดตทับ ต้องใช้แอป Release ที่เซ็นด้วยคีย์ถาวร"
        }
        val link = URL(source)
        require(link.protocol == "https" && link.host == "github.com" &&
            link.path.startsWith(repoPath) &&
            assetPath.matches(link.path.removePrefix(repoPath))) {
            "ลิงก์อัปเดตไม่ตรงกับ GitHub Release ที่อนุญาต"
        }
        require(expectedSha.matches(Regex("[0-9a-fA-F]{64}")) &&
            expectedBytes in 1..MAX_APK_SIZE) {
            "ค่า SHA-256 หรือขนาดไฟล์อัปเดตไม่ถูกต้อง"
        }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "update.apk")
        val stage = File(directory, "update.part")
        stage.delete()
        target.delete()
        try {
            val request = (link.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LocalAI-LinuxDesktop-Update")
            }
            try {
                require(request.responseCode == 200 && request.url.protocol == "https") {
                    "ดาวน์โหลด APK ผ่าน HTTPS ไม่สำเร็จ"
                }
                require(request.contentLengthLong < 0 ||
                    request.contentLengthLong == expectedBytes) {
                    "ขนาดไฟล์ไม่ตรงกับที่ GitHub ระบุ"
                }
                var total = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                request.inputStream.use { input ->
                    stage.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) error("ดาวน์โหลดถูกยกเลิก")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= expectedBytes) { "ข้อมูลเกินขนาดที่ตรวจสอบ" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(total, expectedBytes)
                        }
                    }
                }
                require(total == expectedBytes) { "ดาวน์โหลด APK ไม่ครบ" }
                val actualHash = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
                require(actualHash.equals(expectedSha, ignoreCase = true)) {
                    "ตรวจ SHA-256 ไม่ผ่าน จึงไม่ติดตั้ง"
                }
                require(stage.renameTo(target)) { "บันทึกไฟล์ APK ไม่สำเร็จ" }
                assertCompatible(context, target)
                return target
            } finally {
                request.disconnect()
            }
        } catch (e: Exception) {
            stage.delete()
            target.delete()
            throw e
        }
    }
}
