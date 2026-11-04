package com.localai.controller

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Optional LAN/private-VPN remote viewer; NEVER expose plaintext HTTP to the public internet. */
class RemoteHttpServer(private val context: Context) {
    private val workers = Executors.newFixedThreadPool(4)
    private var server: ServerSocket? = null
    @Volatile private var alive = false
    fun start() {
        val socket = ServerSocket(ScreenShareService.PORT, 12, InetAddress.getByName("0.0.0.0"))
        server = socket
        alive = true
        Thread({
            while (alive) try {
                val client = socket.accept()
                if (client.inetAddress.isLoopbackAddress || client.inetAddress is Inet4Address && RemoteSession.trustedPeer(client.inetAddress as Inet4Address)) {
                    workers.execute { handle(client) }
                } else client.close()
            } catch (e: SocketException) {
                if (alive) ScreenShareService.lastError = "Remote socket: ${e.message}"
            } catch (e: Exception) {
                if (alive) ScreenShareService.lastError = "Remote accept: ${e.message}"
            }
        }, "native-remote-listener").apply { isDaemon = true; start() }
    }
    fun close() {
        alive = false
        server?.close()
        server = null
        workers.shutdownNow()
    }
    private data class Req(val verb: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun cookie(name: String): String? = headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
    private fun readLine(input: InputStream, cap: Int = 8192): String {
        val buf = ByteArrayOutputStream()
        while (buf.size() < cap) {
            val b = input.read()
            if (b < 0) error("Socket closed")
            if (b == 10) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
        error("Line too long")
    }
    private fun read(input: InputStream): Req {
        val line = readLine(input).split(' ')
        require(line.size == 3 && line[2].startsWith("HTTP/1.")) { "Bad HTTP request" }
        require(line[0] in listOf("GET", "POST")) { "Unsupported method" }
        val headers = mutableMapOf<String,String>()
        var complete = false
        var total = 0
        for (i in 0 until 60) {
            val h = readLine(input)
            if (h.isBlank()) { complete = true; break }
            total += h.length
            require(total < 16_384) { "Headers too large" }
            val pos = h.indexOf(':')
            if (pos > 0) headers[h.substring(0, pos).trim().lowercase()] = h.substring(pos + 1).trim()
        }
        require(complete) { "Too many headers" }
        require(!headers.containsKey("transfer-encoding")) { "Chunked not supported" }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..8192) { "Body too large" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            require(count > 0) { "Short request" }
            offset += count
        }
        return Req(line[0], line[1].substringBefore('?'), headers, String(bytes, StandardCharsets.UTF_8))
    }
    private fun respond(s: Socket, status: Int, mime: String, bytes: ByteArray, more: Map<String,String> = emptyMap()) {
        val title = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable"; else -> "Error" }
        val headers = linkedMapOf(
            "Content-Type" to mime,
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
            "Connection" to "close",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "X-Frame-Options" to "DENY",
            "Content-Security-Policy" to "default-src 'none'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'"
        )
        headers.putAll(more)
        val o = BufferedOutputStream(s.getOutputStream())
        o.write("HTTP/1.1 $status $title\r\n".toByteArray(StandardCharsets.US_ASCII))
        for ((k,v) in headers) o.write("$k: $v\r\n".toByteArray(StandardCharsets.US_ASCII))
        o.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        o.write(bytes)
        o.flush()
    }
    private fun json(s: Socket, status: Int, obj: JSONObject, extra: Map<String,String> = emptyMap()) =
        respond(s, status, "application/json; charset=utf-8", obj.toString().toByteArray(StandardCharsets.UTF_8), extra)
    private fun fail(s: Socket, status: Int, code: String) = json(s, status, JSONObject().put("ok", false).put("error", code))
    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 9000
                val r = read(sock.getInputStream())
                val host = r.headers["host"] ?: ""
                // Reject forged Host headers; only actual local IPv4 addresses are allowed.
                val hosts = RemoteSession.localUrls(ScreenShareService.PORT).map { it.removePrefix("http://") } + "127.0.0.1:${ScreenShareService.PORT}"
                if (host !in hosts) { fail(sock, 403, "INVALID_HOST"); return }
                if (r.verb == "POST") {
                    if (r.headers["origin"] != "http://$host" || r.headers["content-type"]?.startsWith("application/json") != true) {
                        fail(sock, 403, "ORIGIN_JSON_REQUIRED"); return
                    }
                }
                if (r.verb == "GET" && r.path in listOf("/", "/remote.js", "/remote.css")) {
                    val name = when (r.path) { "/remote.js" -> "remote.js"; "/remote.css" -> "remote.css"; else -> "remote.html" }
                    val mime = if (name.endsWith(".js")) "application/javascript; charset=utf-8" else if (name.endsWith(".css")) "text/css; charset=utf-8" else "text/html; charset=utf-8"
                    respond(sock, 200, mime, context.assets.open(name).use { it.readBytes() })
                    return
                }
                if (r.verb == "POST" && r.path == "/pair") {
                    val pin = JSONObject(r.body).optString("pin")
                    if (!RemoteSession.pair(pin)) { fail(sock, 401, "INVALID_PAIRING_PIN_OR_RATE_LIMIT"); return }
                    json(sock, 200, JSONObject().put("ok", true), mapOf("Set-Cookie" to "remote_ai_session=${RemoteSession.token}; Path=/; HttpOnly; SameSite=Strict"))
                    return
                }
                if (!RemoteSession.authenticated(r.cookie("remote_ai_session"))) {
                    fail(sock, 401, "PAIRING_REQUIRED"); return
                }
                if (r.verb == "POST" && r.headers["x-csrf-token"] != RemoteSession.csrf) {
                    fail(sock, 403, "BAD_CSRF"); return
                }
                when (r.verb to r.path) {
                    "GET" to "/status" -> json(sock, 200, JSONObject()
                        .put("ok", true).put("running", ScreenShareService.running)
                        .put("control", RemoteSession.allowInput && AppStore(context).allowUiControl() && AutomationAccessibilityService.current != null)
                        .put("width", ScreenShareService.displayWidth).put("height", ScreenShareService.displayHeight)
                        .put("csrf", RemoteSession.csrf))
                    "GET" to "/frame.jpg" -> {
                        val bytes = ScreenShareService.latestJpeg
                        if (!ScreenShareService.running || bytes == null) fail(sock, 503, "FRAME_NOT_READY")
                        else respond(sock, 200, "image/jpeg", bytes)
                    }
                    "POST" to "/action" -> {
                        if (!RemoteSession.allowInput || !AppStore(context).allowUiControl()) { fail(sock, 403, "REMOTE_CONTROL_DISABLED_ON_PHONE"); return }
                        val svc = AutomationAccessibilityService.current
                        if (svc == null) { fail(sock, 503, "ENABLE_ACCESSIBILITY_ON_PHONE"); return }
                        val a = JSONObject(r.body)
                        val ok = when (a.optString("action")) {
                            "tap" -> {
                                val x = a.optDouble("x", -1.0)
                                val y = a.optDouble("y", -1.0)
                                x in 0.0..1.0 && y in 0.0..1.0 && ScreenShareService.displayWidth > 0 && ScreenShareService.displayHeight > 0 &&
                                    svc.tap((x * (ScreenShareService.displayWidth - 1)).toInt(), (y * (ScreenShareService.displayHeight - 1)).toInt())
                            }
                            "type" -> { val value = a.optString("text"); value.isNotEmpty() && value.length <= 500 && svc.typeIntoFocused(value) }
                            "back" -> svc.goBack()
                            "home" -> svc.goHome()
                            else -> false
                        }
                        json(sock, if (ok) 200 else 403, JSONObject().put("ok", ok).put("action", a.optString("action")))
                    }
                    else -> fail(sock, 404, "NOT_FOUND")
                }
            } catch (e: Exception) {
                try { fail(sock, 400, e.message?.take(90) ?: "BAD_REQUEST") } catch (_: Exception) {}
            }
        }
    }
}