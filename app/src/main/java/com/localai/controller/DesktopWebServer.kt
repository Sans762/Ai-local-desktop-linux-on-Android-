package com.localai.controller

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Localhost-only embedded noVNC HTTP/WebSocket bridge to Alpine's Xvnc. */
class DesktopWebServer(private val context: Context) {
    companion object { const val PORT = 6080 }
    private var server: ServerSocket? = null
    private val clients = Executors.newFixedThreadPool(6)
    @Volatile private var alive = false

    fun start() {
        val local = ServerSocket(PORT, 6, InetAddress.getByName("127.0.0.1"))
        server = local
        alive = true
        thread(isDaemon = true, name = "linux-desktop-web") {
            while (alive) {
                try {
                    val client = local.accept()
                    clients.execute { handle(client) }
                } catch (_: Exception) { if (!alive) break }
            }
        }
    }

    private fun readRequest(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var marker = 0
        while (bytes.size() < 16384) {
            val ch = input.read()
            if (ch < 0) throw IllegalStateException("Connection closed")
            bytes.write(ch)
            marker = (marker shl 8) or ch
            if (marker == 0x0d0a0d0a) return bytes.toString("ISO-8859-1")
        }
        throw IllegalArgumentException("Headers too large")
    }

    private fun respond(out: OutputStream, status: String, mime: String, bytes: ByteArray) {
        val headers = "HTTP/1.1 " + status + "\r\nContent-Type: " + mime + "\r\n" +
            "Content-Length: " + bytes.size + "\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 8000
                val req = readRequest(socket.getInputStream())
                val lines = req.split("\r\n")
                val first = lines.firstOrNull().orEmpty().split(" ")
                if (first.size < 2 || first[0] != "GET") return
                val headers = lines.drop(1).mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i > 0) line.substring(0, i).lowercase().trim() to line.substring(i + 1).trim() else null
                }.toMap()
                if (headers["host"] != "127.0.0.1:$PORT") {
                    respond(socket.getOutputStream(), "403 Forbidden", "text/plain", "Invalid host".toByteArray())
                    return
                }
                val path = first[1].substringBefore('?')
                if (path == "/ws") {
                    if (headers["origin"] != "http://127.0.0.1:$PORT" ||
                        !headers["upgrade"].equals("websocket", ignoreCase = true) ||
                        headers["sec-websocket-version"] != "13") return
                    upgradeAndBridge(socket, headers)
                    return
                }
                val asset = when {
                    path == "/" || path == "/desktop.html" -> "desktop.html"
                    path.startsWith("/novnc/") && !path.contains("..") &&
                        path.matches(Regex("/[A-Za-z0-9_./-]{1,180}")) -> path.removePrefix("/")
                    else -> ""
                }
                if (asset.isBlank()) {
                    respond(socket.getOutputStream(), "404 Not Found", "text/plain", "Not found".toByteArray())
                    return
                }
                val data = try { context.assets.open(asset).use { it.readBytes() } }
                catch (_: Exception) {
                    respond(socket.getOutputStream(), "404 Not Found", "text/plain", "Asset missing".toByteArray())
                    return
                }
                val mime = when {
                    asset.endsWith(".js") || asset.endsWith(".mjs") -> "application/javascript; charset=utf-8"
                    asset.endsWith(".css") -> "text/css; charset=utf-8"
                    asset.endsWith(".html") -> "text/html; charset=utf-8"
                    asset.endsWith(".svg") -> "image/svg+xml"
                    else -> "application/octet-stream"
                }
                respond(socket.getOutputStream(), "200 OK", mime, data)
            } catch (_: Exception) {}
        }
    }

    private fun upgradeAndBridge(browser: Socket, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return
        if (key.length !in 16..64) return
        Socket("127.0.0.1", LinuxDesktopService.LOCAL_VNC_PORT).use { vnc ->
            val digest = MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
            val accept = Base64.getEncoder().encodeToString(digest)
            val out = browser.getOutputStream()
            out.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            browser.soTimeout = 0
            val upstream = thread(isDaemon = true, name = "vnc-to-browser") {
                try {
                    val input = vnc.getInputStream()
                    val data = ByteArray(8192)
                    while (true) {
                        val count = input.read(data)
                        if (count <= 0) break
                        synchronized(out) { sendFrame(out, 2, data, count) }
                    }
                } catch (_: Exception) {}
                finally { try { browser.shutdownOutput() } catch (_: Exception) {} }
            }
            try {
                val input = browser.getInputStream()
                val target = vnc.getOutputStream()
                while (true) {
                    val first = input.read()
                    val second = input.read()
                    if (first < 0 || second < 0) break
                    val opcode = first and 15
                    if ((second and 128) == 0) break // client-to-server must be masked
                    var length = (second and 127).toLong()
                    if (length == 126L) length = ((readByte(input) shl 8) or readByte(input)).toLong()
                    else if (length == 127L) {
                        length = 0L
                        repeat(8) { length = (length shl 8) or readByte(input).toLong() }
                    }
                    if (length < 0 || length > 1024 * 1024) break
                    val mask = ByteArray(4)
                    readFully(input, mask)
                    val payload = ByteArray(length.toInt())
                    readFully(input, payload)
                    for (i in payload.indices) payload[i] =
                        (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    when (opcode) {
                        0, 2 -> { target.write(payload); target.flush() }
                        8 -> break
                        9 -> synchronized(out) { sendFrame(out, 10, payload, payload.size) }
                    }
                }
            } catch (_: Exception) {}
            finally {
                try { vnc.close() } catch (_: Exception) {}
                upstream.join(1000)
            }
        }
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw IllegalStateException("WebSocket ended")
        return b
    }

    private fun readFully(input: InputStream, data: ByteArray) {
        var pos = 0
        while (pos < data.size) {
            val count = input.read(data, pos, data.size - pos)
            if (count <= 0) throw IllegalStateException("Truncated WebSocket")
            pos += count
        }
    }

    private fun sendFrame(out: OutputStream, opcode: Int, data: ByteArray, n: Int) {
        out.write(0x80 or opcode)
        when {
            n < 126 -> out.write(n)
            n <= 65535 -> {
                out.write(126)
                out.write(n ushr 8 and 255)
                out.write(n and 255)
            }
            else -> {
                out.write(127)
                repeat(4) { out.write(0) }
                out.write(n ushr 24 and 255)
                out.write(n ushr 16 and 255)
                out.write(n ushr 8 and 255)
                out.write(n and 255)
            }
        }
        out.write(data, 0, n)
        out.flush()
    }

    fun close() {
        alive = false
        try { server?.close() } catch (_: Exception) {}
        clients.shutdownNow()
    }
}
