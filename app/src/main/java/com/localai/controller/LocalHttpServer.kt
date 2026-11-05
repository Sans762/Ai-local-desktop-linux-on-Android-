package com.localai.controller

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Loopback-only web server. No remote bind, no external HTTP endpoints or AI API. */
class LocalHttpServer(private val context: Context, private val store: AppStore,
                      private val db: ChatDatabase, private val ai: NativeAgent,
                      private val bindIp: String = "127.0.0.1",
                      private val listenPort: Int = AiForegroundService.PORT) {
    private val pool = Executors.newFixedThreadPool(4)
    private val heavyOperation = Semaphore(1)
    private val linuxAgent = LinuxAgent(context, ImportRepository(context, store, db), db)
    private val monitor = LinuxWorkspaceMonitor(context)
    @Volatile private var alive = false
    private var server: ServerSocket? = null
    private var acceptor: Thread? = null
    private val origin = "http://$bindIp:$listenPort"
    private var failures = 0
    private var firstFailure = 0L

    fun start() {
        server = ServerSocket(listenPort, 16, InetAddress.getByName(bindIp))
        alive = true
        acceptor = Thread({
            while (alive) {
                try { val socket = server!!.accept(); pool.submit { handleSocket(socket) } }
                catch (e: SocketException) { if (alive) AiForegroundService.lastError = e.message ?: "socket error" }
                catch (e: Exception) { if (alive) AiForegroundService.lastError = e.message ?: "accept error" }
            }
        }, "native-ai-http").apply { isDaemon = true; start() }
    }

    fun close() {
        alive = false
        AgentWork.cancelAll()
        server?.close()
        pool.shutdownNow()
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
        fun cookie(name: String): String? = headers["cookie"]?.split(';')
            ?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    private fun readLine(input: InputStream, limit: Int = 8192): String {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < limit) {
            val b = input.read()
            if (b == -1) error("Connection ended")
            if (b == 10) return buffer.toString("UTF-8").trimEnd('\r')
            buffer.write(b)
        }
        error("Header line too long")
    }

    private fun readRequest(input: InputStream): Request {
        val line = readLine(input)
        val parts = line.split(' ')
        require(parts.size == 3 && parts[2].startsWith("HTTP/1.")) { "Bad HTTP request" }
        require(parts[0] in setOf("GET", "POST")) { "Method not allowed" }
        val headers = mutableMapOf<String, String>()
        var total = 0
        var endedHeaders = false
        for (i in 0 until 70) {
            val h = readLine(input)
            if (h.isEmpty()) { endedHeaders = true; break }
            total += h.length
            require(total <= 16_384) { "Headers too large" }
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        require(endedHeaders) { "Too many HTTP headers" }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..32_768) { "Body too large" }
        require("transfer-encoding" !in headers) { "Chunked requests not supported" }
        val bodyBytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bodyBytes, read, length - read)
            if (n < 0) error("Incomplete request body")
            read += n
        }
        return Request(parts[0], parts[1].substringBefore('?'), headers, String(bodyBytes, StandardCharsets.UTF_8))
    }

    private fun respond(socket: Socket, status: Int, type: String, data: ByteArray, extra: Map<String, String> = emptyMap()) {
        val statusText = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"
            429 -> "Too Many Requests"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        val headers = linkedMapOf("Content-Type" to type, "Content-Length" to data.size.toString(),
            "Cache-Control" to "no-store", "Connection" to "close", "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer", "X-Frame-Options" to "DENY",
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-src http://127.0.0.1:6080; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
        headers.putAll(extra)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HTTP/1.1 $status $statusText\r\n".toByteArray(StandardCharsets.US_ASCII))
        headers.forEach { (k,v) -> out.write("$k: $v\r\n".toByteArray(StandardCharsets.US_ASCII)) }
        out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(data)
        out.flush()
    }

    private fun json(socket: Socket, status: Int, body: JSONObject, extra: Map<String,String> = emptyMap()) =
        respond(socket, status, "application/json; charset=utf-8", body.toString().toByteArray(StandardCharsets.UTF_8), extra)

    private fun error(socket: Socket, status: Int, code: String) =
        json(socket, status, JSONObject().put("ok", false).put("error", code))

    private fun content(name: String): ByteArray = context.assets.open(name).use { it.readBytes() }

    // Keep a worker available for status and stop instead of queueing many JNI calls.
    private fun perform(socket: Socket, timeoutMs: Long = 150_000L,
                        operation: suspend () -> JSONObject) {
        if (!heavyOperation.tryAcquire()) { error(socket, 409, "AI_BUSY"); return }
        try {
            val reply = runBlocking { withTimeout(timeoutMs) { operation() } }
            json(socket, if (reply.optBoolean("ok")) 200 else 400, reply)
        } finally { heavyOperation.release() }
    }

    private fun handleSocket(socket: Socket) {
        socket.use { sock ->
            try {
                sock.soTimeout = 12_000
                val req = readRequest(sock.getInputStream())
                // Reject DNS rebinding and wrong-host traffic, including on LAN.
                if (req.headers["host"] != "$bindIp:$listenPort") {
                    error(sock, 403, "INVALID_HOST"); return
                }
                val authenticated = store.hasSession(req.cookie("native_ai_session"))
                if (req.method == "POST" && (req.headers["origin"] != origin || req.headers["content-type"]?.startsWith("application/json") != true)) {
                    error(sock, 403, "SAME_ORIGIN_JSON_REQUIRED"); return
                }
                if (req.method == "GET" && req.path in listOf("/", "/index.html", "/app.js", "/app.css")) {
                    val path = when (req.path) { "/app.js" -> "app.js"; "/app.css" -> "app.css"; else -> "index.html" }
                    val type = if (path.endsWith(".js")) "application/javascript; charset=utf-8" else if (path.endsWith(".css")) "text/css; charset=utf-8" else "text/html; charset=utf-8"
                    respond(sock, 200, type, content(path)); return
                }
                if (req.method == "POST" && req.path == "/login") {
                    val now = System.currentTimeMillis()
                    synchronized(this) {
                        if (now - firstFailure > 60_000) { failures = 0; firstFailure = now }
                        if (failures >= 6) { error(sock, 429, "LOGIN_RATE_LIMIT"); return }
                    }
                    val pw = JSONObject(req.body).optString("password")
                    if (!store.verifyPassword(pw)) {
                        synchronized(this) { failures++ }
                        error(sock, 401, "INVALID_PASSWORD"); return
                    }
                    synchronized(this) { failures = 0 }
                    json(sock, 200, JSONObject().put("ok", true), mapOf(
                        "Set-Cookie" to "native_ai_session=${store.sessionToken()}; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000"))
                    return
                }
                if (!authenticated) { error(sock, 401, "LOGIN_REQUIRED"); return }
                // A browser on an unencrypted private LAN can access the AI chat, but
                // must NEVER gain shell execution in the Android app's Linux rootfs.
                // Local user approval via the device's loopback UI is required.
                if (bindIp != "127.0.0.1" && req.path in setOf(
                        "/api/linux/execute", "/api/linux/command", "/api/linux/plan",
                        "/api/linux/continue", "/api/linux/discard", "/api/ai/stop")) {
                    error(sock, 403, "LINUX_COMMANDS_REQUIRE_ON_DEVICE_APPROVAL"); return
                }
                if (req.method == "POST" && req.headers["x-csrf-token"] != store.csrfToken()) {
                    error(sock, 403, "INVALID_CSRF_TOKEN"); return
                }
                when (req.method to req.path) {
                    "GET" to "/api/status" -> json(sock, 200, ai.status().put("csrf", store.csrfToken()))
                    "GET" to "/api/monitor" -> json(sock, 200, monitor.snapshot())
                    "GET" to "/api/history" -> json(sock, 200, JSONObject().put("ok", true).put("messages", db.recentMessages(35)))
                    "GET" to "/api/linux/status" ->
                        json(sock, 200, linuxAgent.status())
                    "POST" to "/api/ai/stop" -> {
                        AgentWork.cancelAll()
                        json(sock, 200, JSONObject().put("ok", true).put("stopping", AgentWork.stopping))
                    }
                    "POST" to "/api/linux/continue" -> {
                        val id = JSONObject(req.body).optString("taskId")
                        perform(sock) { linuxAgent.continueTask(id) }
                    }
                    "POST" to "/api/linux/discard" -> {
                        val id = JSONObject(req.body).optString("planId")
                        json(sock, 200, runBlocking { linuxAgent.discard(id) })
                    }
                    "POST" to "/api/linux/plan" -> {
                        val task = JSONObject(req.body).optString("task", "")
                        perform(sock) { linuxAgent.propose(task) }
                    }
                    "POST" to "/api/linux/execute" -> {
                        val id = JSONObject(req.body).optString("planId", "")
                        perform(sock) { linuxAgent.execute(id) }
                    }
                    "POST" to "/api/linux/command" -> {
                        val value = JSONObject(req.body).optString("command", "")
                        perform(sock, 50_000L) { linuxAgent.command(value) }
                    }
                    "POST" to "/api/chat" -> {
                        val input = JSONObject(req.body).optString("message", "")
                        perform(sock) { ai.chat(input) }
                    }
                    "POST" to "/logout" -> json(sock, 200, JSONObject().put("ok", true), mapOf(
                        "Set-Cookie" to "native_ai_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"))
                    else -> error(sock, 404, "NOT_FOUND")
                }
            } catch (e: Exception) {
                try { error(sock, 400, e.message?.take(150) ?: "BAD_REQUEST") }
                catch (_: Exception) {}
            }
        }
    }
}
