package com.localai.controller

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Explicit, user-initiated first-run download. Inference itself never uses an AI API. */
class ModelDownloadService : Service() {
    companion object {
        const val ACTION_CANCEL = "com.localai.controller.CANCEL_MODEL_DOWNLOAD"
        const val EXTRA_MODEL = "model"
        private const val CHANNEL = "local_ai_model_install"
        @Volatile var running = false
        @Volatile var progress = "Not started"
        @Volatile var lastError = ""
        private data class Model(val file: String, val url: String, val sha256: String)
        private val models = mapOf(
            "small" to Model(
                "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
            ),
            "balanced" to Model(
                "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
            )
        )
    }
    @Volatile private var cancel = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancel = true
            progress = "Cancelling download…"
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val model = models[intent?.getStringExtra(EXTRA_MODEL)] ?: run { stopSelf(); return START_NOT_STICKY }
        running = true; cancel = false; lastError = ""; progress = "Preparing…"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI model setup", NotificationManager.IMPORTANCE_LOW))
        updateNotification(0)
        Thread({
            try { download(model) }
            catch (e: Exception) {
                lastError = e.message ?: "Model download failed"
                progress = if (cancel) "Cancelled — partial download saved" else "Error: $lastError"
                updateNotification(0)
            } finally { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }, "ai-model-download").start()
        return START_NOT_STICKY
    }

    private fun updateNotification(pct: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val cancelIntent = PendingIntent.getService(this, 155, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Local AI · Downloading offline model")
            .setContentText(progress)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, pct == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(155, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(155, note)
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(model: Model) {
        val imports = ImportRepository(this, AppStore(this), ChatDatabase(this))
        val directory = imports.modelDir()
        val dest = File(directory, model.file)
        if (dest.isFile && digest(dest).equals(model.sha256, true)) {
            AppStore(this).setSelectedModel(dest.name)
            progress = "Ready · ${dest.name}"
            return
        }
        val part = File(directory, model.file + ".part")
        require(filesDir.usableSpace > 700L * 1024 * 1024) { "Not enough free storage for model" }
        val existing = part.length()
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "LocalAiNative/0.4")
        if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
        try {
            val status = connection.responseCode
            require(status == 200 || status == 206) { "Model server HTTP $status" }
            val append = status == 206 && existing > 0
            val start = if (append) existing else 0L
            val contentLength = connection.contentLengthLong
            val total = if (contentLength > 0) start + contentLength else -1L
            require(total == -1L || total <= 4L * 1024 * 1024 * 1024) { "Model too large" }
            require(filesDir.usableSpace > (if (contentLength > 0) contentLength else 700_000_000L) + 150_000_000L) { "Insufficient storage" }
            RandomAccessFile(part, "rw").use { output ->
                output.setLength(start); output.seek(start)
                var done = start
                var lastUpdate = 0L
                connection.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (cancel) throw InterruptedException("Download stopped by user")
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1500L) {
                            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0
                            progress = if (total > 0) "$percent% · ${done / 1_048_576} / ${total / 1_048_576} MiB" else "${done / 1_048_576} MiB"
                            updateNotification(percent)
                            lastUpdate = now
                        }
                    }
                }
                output.fd.sync()
            }
            require(part.length() > 10_000_000) { "Incomplete GGUF model" }
            progress = "Verifying SHA-256…"
            updateNotification(99)
            require(digest(part).equals(model.sha256, true)) { "SHA-256 mismatch: discard corrupt partial download" }
            require(part.inputStream().use { String(it.readNBytes(4), Charsets.US_ASCII) } == "GGUF") { "Invalid GGUF header" }
            require(!dest.exists() || dest.delete()) { "Cannot replace damaged model" }
            require(part.renameTo(dest)) { "Could not finish model install" }
            AppStore(this).setSelectedModel(dest.name)
            progress = "Ready · ${dest.name}"
            lastError = ""
        } finally { connection.disconnect() }
    }
}