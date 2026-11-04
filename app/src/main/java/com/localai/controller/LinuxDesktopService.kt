package com.localai.controller

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.io.File
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** User-started Alpine Xvnc desktop. Never captures or controls the Android UI. */
class LinuxDesktopService : Service() {
    companion object {
        const val ACTION_INSTALL = "com.localai.controller.DESKTOP_INSTALL"
        const val ACTION_INSTALL_BROWSER = "com.localai.controller.BROWSER_INSTALL"
        const val ACTION_START = "com.localai.controller.DESKTOP_START"
        const val ACTION_STOP = "com.localai.controller.DESKTOP_STOP"
        const val EXTRA_PASSWORD = "desktop_password"
        const val VNC_PORT = 5902
        const val WEB_PORT = 18789
        const val LOCAL_VNC_PORT = 5901
        private const val CHANNEL = "local_ai_linux_desktop"
        @Volatile var running = false
        @Volatile var installing = false
        @Volatile var message = "Linux desktop not started"
        @Volatile var lastError = ""
        @Volatile var ipAddress = ""
        private const val STATUS_PREFS = "linux_desktop_status"

        fun status(context: Context): String =
            if (installing || running) message else
                context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                    .getString("message", "ยังไม่ได้ติดตั้งหน้าจอ Linux").orEmpty()

        fun error(context: Context): String =
            if (lastError.isNotBlank()) lastError else
                context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
                    .getString("error", "").orEmpty()

        fun browserInstalled(context: Context): Boolean =
            File(context.filesDir, "embedded_linux/alpine-rootfs/usr/bin/chromium").isFile

        fun installed(context: Context): Boolean =
            File(context.filesDir, "embedded_linux/alpine-rootfs/usr/bin/Xvnc").isFile &&
            File(context.filesDir, "embedded_linux/alpine-rootfs/usr/bin/openbox").isFile

        fun privateIp(): String? {
            val options = mutableListOf<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val nic = interfaces.nextElement()
                if (!nic.isUp || nic.isLoopback) continue
                val ips = nic.inetAddresses
                while (ips.hasMoreElements()) {
                    val address = ips.nextElement()
                    if (address !is Inet4Address) continue
                    val a = address.address.map { it.toInt() and 255 }
                    val local = a[0] == 10 ||
                        (a[0] == 172 && a[1] in 16..31) ||
                        (a[0] == 192 && a[1] == 168) ||
                        (a[0] == 100 && a[1] in 64..127)
                    val allowedInterface = nic.name.startsWith("wlan") ||
                        nic.name.startsWith("ap") || nic.name.startsWith("eth") ||
                        nic.name.startsWith("tun") || nic.name.startsWith("wg") ||
                        nic.name.startsWith("tailscale")
                    if (!local || !allowedInterface) continue
                    val weight = when {
                        nic.name.startsWith("wlan") -> 0
                        nic.name.startsWith("ap") -> 1
                        nic.name.startsWith("tun") -> 2
                        else -> 3
                    }
                    options.add(weight to address.hostAddress.orEmpty())
                }
            }
            return options.minByOrNull { it.first }?.second
        }
    }

    private val owned = mutableListOf<Process>()
    private var relay: DesktopTcpRelay? = null
    private var lanWeb: LocalHttpServer? = null
    private var desktopViewer: DesktopWebServer? = null
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(value: String, failure: String = "") {
        message = value.take(180)
        if (failure.isNotEmpty()) lastError = failure.take(1600)
        getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
            .edit().putString("message", message).putString("error", lastError).apply()
        notifyStatus(message)
    }

    private fun networkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return active.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            active.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (worker?.isAlive == true) return START_NOT_STICKY
        if (running && intent?.action == ACTION_START) {
            notifyStatus(message)
            return START_NOT_STICKY
        }
        val action = intent?.action ?: run { stopSelf(); return START_NOT_STICKY }
        if (action !in setOf(ACTION_START, ACTION_INSTALL, ACTION_INSTALL_BROWSER)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux Desktop and VNC", NotificationManager.IMPORTANCE_LOW))
        val actionInstalling = action == ACTION_INSTALL || action == ACTION_INSTALL_BROWSER
        if (actionInstalling) installing = true
        lastError = ""
        updateStatus(if (actionInstalling) "กำลังเตรียมติดตั้ง…" else "กำลังเปิด Linux Desktop…")
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        worker = Thread({
            try {
                val linux = LinuxRuntime(this)
                require(linux.available()) { "ติดตั้ง Linux พื้นฐานก่อนเปิดหน้าจอ" }
                if (actionInstalling) {
                    require(!running) { "Stop the desktop before installing packages" }
                    require(filesDir.usableSpace > if (action == ACTION_INSTALL_BROWSER) 1_600_000_000L else 600_000_000L) {
                        "Not enough free storage for Linux packages"
                    }
                    val isBrowser = action == ACTION_INSTALL_BROWSER
                    require(networkAvailable()) {
                        "ไม่มีอินเทอร์เน็ตที่ใช้งานได้ กรุณาเชื่อมต่อ Wi-Fi หรืออินเทอร์เน็ตมือถือแล้วลองใหม่"
                    }
                    updateStatus("กำลังเตรียมคลังโปรแกรม Linux…")
                    linux.ensureDesktopRepositories()
                    val packages = if (isBrowser) "chromium xdotool" else
                        "tigervnc openbox xterm pcmanfm tint2 font-dejavu xdotool"
                    updateStatus(if (isBrowser) "กำลังดาวน์โหลด Chromium…" else
                        "กำลังดาวน์โหลดหน้าจอ Linux…")
                    // No separate apk update: --no-cache resolves package metadata.
                    val setup = "apk add --no-cache $packages"
                    var lastNoticeAt = 0L
                    val result = linux.command(setup, timeoutSeconds = 480) { line ->
                        val now = System.currentTimeMillis()
                        if (now - lastNoticeAt > 2000L && line.isNotBlank()) {
                            lastNoticeAt = now
                            updateStatus("Linux: " + line.replace(Regex("[\r\n]"), " ").take(80))
                        }
                    }
                    require(result.trimEnd().endsWith("[exit 0]")) {
                        when {
                            "[timeout:" in result -> "ดาวน์โหลด Linux ใช้เวลานานเกินไป กรุณาตรวจ Wi-Fi แล้วลองใหม่"
                            "UNTRUSTED" in result -> "ตรวจไฟล์แพ็กเกจไม่ผ่าน: " + result.takeLast(450)
                            else -> "ติดตั้งไม่สำเร็จ: " + result.takeLast(700)
                        }
                    }
                    if (isBrowser) require(browserInstalled(this)) { "ติดตั้ง Chromium ไม่ครบ" }
                    else require(installed(this)) { "ติดตั้ง Xvnc/Openbox ไม่ครบ" }
                    updateStatus(if (isBrowser) "✓ ติดตั้ง Chromium สำเร็จ" else
                        "✓ ติดตั้งหน้าจอ Linux สำเร็จ · พร้อมเปิด")
                } else {
                    require(installed(this)) { "Install desktop packages first" }
                    require(password.matches(Regex("[A-Za-z0-9]{8,16}"))) {
                        "VNC password must have 8–16 English letters or digits"
                    }
                    // The desktop must work locally on the phone even without Wi-Fi.
                    // LAN relay is optional and exists only on private networks.
                    val ip = privateIp()
                    startDesktop(linux, password, ip)
                }
            } catch (e: Exception) {
                val reason = (e.message ?: "เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ").take(1200)
                updateStatus("ติดตั้งหรือเปิด Linux ไม่สำเร็จ", reason)
                if (!running) cleanup()
            } finally {
                installing = false
                if (!running) stopSelf()
            }
        }, "linux-desktop-worker").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun startDesktop(linux: LinuxRuntime, password: String, ip: String?) {
        // VncAuth only uses first 8 ASCII chars; never expose the password in logs.
        val auth = "mkdir -p /root/.vnc && umask 077 && printf '%s\\n' '" +
            password.take(8) + "' | /usr/bin/vncpasswd -f > /root/.vnc/passwd && chmod 600 /root/.vnc/passwd"
        val response = linux.command(auth, timeoutSeconds = 25)
        require(response.trimEnd().endsWith("[exit 0]")) { "Could not create VNC auth file" }
        notifyStatus("Starting Linux graphics server…")
        val xvnc = linux.startDesktopProcess(
            "/usr/bin/Xvnc",
            listOf(":1", "-geometry", "1280x720", "-depth", "24",
                "-localhost", "yes", "-rfbport", LOCAL_VNC_PORT.toString(),
                "-SecurityTypes", "VncAuth", "-rfbauth", "/root/.vnc/passwd",
                "-AlwaysShared", "-nolisten", "tcp"),
            logName = "xvnc"
        )
        synchronized(owned) { owned.add(xvnc) }
        var socketReady = false
        for (attempt in 0 until 20) {
            if (!xvnc.isAlive) throw IllegalStateException("Xvnc exited: " + linux.desktopLog("xvnc").takeLast(500))
            try { Socket("127.0.0.1", LOCAL_VNC_PORT).use { socketReady = true } }
            catch (_: Exception) { Thread.sleep(300) }
            if (socketReady) break
        }
        require(socketReady) { "VNC could not start: " + linux.desktopLog("xvnc").takeLast(600) }
        notifyStatus("Opening Linux desktop windows…")
        val display = mapOf("DISPLAY" to ":1")
        val apps = listOf(
            Triple("/usr/bin/openbox", emptyList<String>(), "openbox"),
            Triple("/usr/bin/pcmanfm", listOf("--desktop"), "pcmanfm"),
            Triple("/usr/bin/tint2", emptyList<String>(), "tint2"),
            Triple("/usr/bin/xterm", listOf("-geometry", "110x30+45+55"), "xterm")
        )
        for ((exe, args, log) in apps) {
            try {
                val process = linux.startDesktopProcess(exe, args, display, log)
                synchronized(owned) { owned.add(process) }
            } catch (e: Exception) {
                if (exe.endsWith("openbox")) throw e
            }
        }
        desktopViewer = DesktopWebServer(this).also { it.start() }
        if (ip != null) {
            relay = DesktopTcpRelay(ip, VNC_PORT, LOCAL_VNC_PORT).also { it.start() }
        }
        if (ip != null && AppStore(this).hasPassword()) {
            val store = AppStore(this)
            val db = ChatDatabase(this)
            val imports = ImportRepository(this, store, db)
            lanWeb = LocalHttpServer(this, store, db, NativeAgent(this, store, db, imports),
                bindIp = ip, listenPort = WEB_PORT).also { it.start() }
        }
        ipAddress = ip.orEmpty()
        running = true
        updateStatus(if (ip == null) "✓ หน้าจอ Linux พร้อมใช้บนมือถือ" else
            "✓ หน้าจอ Linux พร้อมใช้ · $ip:$VNC_PORT")
    }

    private fun notifyStatus(info: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Linux Desktop and VNC", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 391,
            Intent(this, LinuxDesktopService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val main = PendingIntent.getActivity(this, 392, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Local AI · Linux Desktop")
            .setContentText(info.take(110))
            .setContentIntent(main)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop desktop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(393, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(393, notification)
    }

    private fun cleanup() {
        try { desktopViewer?.close() } catch (_: Exception) {}
        desktopViewer = null
        try { lanWeb?.close() } catch (_: Exception) {}
        lanWeb = null
        try { relay?.close() } catch (_: Exception) {}
        relay = null
        synchronized(owned) {
            for (p in owned.asReversed()) {
                try {
                    p.destroy()
                    if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroyForcibly()
                } catch (_: Exception) {}
            }
            owned.clear()
        }
        running = false
        ipAddress = ""
    }

    override fun onDestroy() {
        worker?.interrupt()
        cleanup()
        super.onDestroy()
    }
}

private class DesktopTcpRelay(private val ip: String, private val port: Int, private val targetPort: Int) {
    private var server: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(6)
    @Volatile private var alive = false
    fun start() {
        val socket = ServerSocket(port, 6, InetAddress.getByName(ip))
        server = socket
        alive = true
        Thread({
            while (alive) {
                try {
                    val client = socket.accept()
                    workers.submit { bridge(client) }
                } catch (_: Exception) { if (!alive) break }
            }
        }, "linux-vnc-relay").apply { isDaemon = true; start() }
    }
    private fun bridge(client: Socket) {
        client.use { remote ->
            try {
                Socket("127.0.0.1", targetPort).use { local ->
                    val upstream = Thread({
                        try { remote.getInputStream().copyTo(local.getOutputStream()) }
                        catch (_: Exception) {}
                        finally { try { local.shutdownOutput() } catch (_: Exception) {} }
                    }, "linux-vnc-upstream").apply { isDaemon = true; start() }
                    try { local.getInputStream().copyTo(remote.getOutputStream()) }
                    catch (_: Exception) {}
                    finally {
                        try { remote.shutdownOutput() } catch (_: Exception) {}
                        upstream.join(700)
                    }
                }
            } catch (_: Exception) {}
        }
    }
    fun close() {
        alive = false
        try { server?.close() } catch (_: Exception) {}
        workers.shutdownNow()
    }
}
