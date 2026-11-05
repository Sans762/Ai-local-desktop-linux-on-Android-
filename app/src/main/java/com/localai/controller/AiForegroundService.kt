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

/** Explicit user-started persistent service; Android may still stop it under resource pressure. */
class AiForegroundService : Service() {
    companion object {
        const val ACTION_STOP = "com.localai.controller.STOP"
        const val PORT = 18788
        @Volatile var running = false
        @Volatile var working = false
        @Volatile var lastError = ""
    }
    private var http: LocalHttpServer? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("native_ai_running", "Local AI service", NotificationManager.IMPORTANCE_LOW))
        val main = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AiForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this, "native_ai_running")
            .setContentTitle("Local AI Native")
            .setContentText("Offline AI dashboard is active on 127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setContentIntent(main)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(101, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(101, note)
            if (http == null) {
                val store = AppStore(this)
                if (!store.hasPassword()) {
                    lastError = "Set an access password in the app before starting"
                    stopSelf()
                    return START_NOT_STICKY
                }
                val db = ChatDatabase(this)
                val imports = ImportRepository(this, store, db)
                http = LocalHttpServer(this, store, db, NativeAgent(this, store, db, imports))
                http!!.start()
                running = true
                lastError = ""
            }
        } catch (e: Exception) {
            running = false
            lastError = "Startup failed: ${e.message}"
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AgentWork.cancelAll()
        running = false
        http?.close()
        http = null
        super.onDestroy()
    }
}
