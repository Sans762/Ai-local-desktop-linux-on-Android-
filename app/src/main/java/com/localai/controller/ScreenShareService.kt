package com.localai.controller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** User-initiated screen capture: Android prompts for consent on every new session. */
class ScreenShareService : Service() {
    companion object {
        const val PORT = 18789
        const val STOP = "com.localai.controller.SCREEN_STOP"
        const val EXTRA_RESULT = "capture_result"
        const val EXTRA_INTENT = "capture_data"
        @Volatile var running = false
        @Volatile var lastError = ""
        @Volatile var latestJpeg: ByteArray? = null
        @Volatile var displayWidth = 0
        @Volatile var displayHeight = 0
    }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var listenerThread: HandlerThread? = null
    private var remote: RemoteHttpServer? = null
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        try {
            val notification = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notification.createNotificationChannel(NotificationChannel("native_screen_share", "Remote screen sharing", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 12, Intent(this, ScreenShareService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val note = Notification.Builder(this, "native_screen_share")
                .setContentTitle("Local AI • sharing screen")
                .setContentText("Your screen is being shared on your private network")
                .setOngoing(true)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause, "Stop sharing", stop)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(203, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(203, note)
            val resultCode = intent?.getIntExtra(EXTRA_RESULT, 0) ?: 0
            @Suppress("DEPRECATION")
            val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_INTENT, Intent::class.java)
                else intent?.getParcelableExtra(EXTRA_INTENT)
            require(resultCode == android.app.Activity.RESULT_OK && data != null) { "Screen capture consent not granted" }
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val capture = requireNotNull(mgr.getMediaProjection(resultCode, data)) { "Unable to start MediaProjection" }
            projection = capture
            listenerThread = HandlerThread("native-screen-frames").apply { start() }
            val handler = Handler(listenerThread!!.looper)
            capture.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it) }
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            val img = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2)
            reader = img
            img.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameAt < 250) return@setOnImageAvailableListener
                    lastFrameAt = now
                    val plane = image.planes[0]
                    val width = image.width
                    val height = image.height
                    val stridePixels = plane.rowStride / plane.pixelStride
                    if (stridePixels <= 0 || width <= 0 || height <= 0) return@setOnImageAvailableListener
                    plane.buffer.rewind()
                    val padded = Bitmap.createBitmap(stridePixels, height, Bitmap.Config.ARGB_8888)
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val clipped = Bitmap.createBitmap(padded, 0, 0, width, height)
                        try {
                            val targetWidth = minOf(960, width)
                            val targetHeight = maxOf(1, (targetWidth.toLong() * height / width).toInt())
                            val scaled = if (targetWidth < width) Bitmap.createScaledBitmap(clipped, targetWidth, targetHeight, true) else clipped
                            try {
                                val bytes = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 65, bytes)
                                latestJpeg = bytes.toByteArray()
                            } finally { if (scaled !== clipped) scaled.recycle() }
                        } finally { clipped.recycle() }
                    } finally { padded.recycle() }
                } catch (e: Exception) { lastError = "Capture error: ${e.message}" }
                finally { image.close() }
            }, handler)
            display = capture.createVirtualDisplay("local-ai-remote", displayWidth, displayHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, img.surface, null, handler)
            RemoteSession.begin()
            remote = RemoteHttpServer(this).also { it.start() }
            running = true
            lastError = ""
        } catch (e: Exception) {
            lastError = "Remote start failed: ${e.message}"
            stopSelf()
        }
        return START_NOT_STICKY // No background restart without fresh screen-capture consent.
    }
    override fun onDestroy() {
        running = false
        RemoteSession.end()
        remote?.close()
        remote = null
        latestJpeg = null
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        listenerThread?.quitSafely(); listenerThread = null
        super.onDestroy()
    }
}