package com.localai.controller

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** Volatile, single-session pairing. Restarting the share invalidates every remote login. */
object RemoteSession {
    private val random = SecureRandom()
    @Volatile var pin: String = ""
        private set
    @Volatile var token: String = ""
        private set
    @Volatile var csrf: String = ""
        private set
    @Volatile var allowInput = false
    @Volatile var running = false
    private var failures = 0
    private var windowStart = 0L

    @Synchronized fun begin() {
        pin = (10000000 + random.nextInt(90000000)).toString()
        token = randomToken()
        csrf = randomToken()
        failures = 0
        windowStart = System.currentTimeMillis()
        allowInput = false
        running = true
    }
    @Synchronized fun end() {
        running = false
        pin = ""
        token = ""
        csrf = ""
        allowInput = false
    }
    private fun randomToken(): String = Base64.encodeToString(ByteArray(32).also { random.nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    fun authenticated(session: String?): Boolean {
        val correct = token
        return running && session != null && correct.isNotEmpty() && session.length == correct.length &&
            MessageDigest.isEqual(session.toByteArray(), correct.toByteArray())
    }
    @Synchronized fun pair(candidate: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - windowStart > 60000) { failures = 0; windowStart = now }
        if (failures >= 6 || !running) return false
        val correct = pin
        val ok = candidate.length == correct.length && MessageDigest.isEqual(candidate.toByteArray(), correct.toByteArray())
        if (!ok) failures++ else failures = 0
        return ok
    }
    /** Network reachability only. Not a replacement for encrypted transport. */
    fun trustedPeer(address: Inet4Address): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        return b[0] == 10 || b[0] == 192 && b[1] == 168 ||
            b[0] == 172 && b[1] in 16..31 || b[0] == 100 && b[1] in 64..127
    }
    fun localUrls(port: Int): List<String> {
        val urls = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return urls
        while (interfaces.hasMoreElements()) {
            val nic = interfaces.nextElement()
            if (!nic.isUp || nic.isLoopback) continue
            val ips = nic.inetAddresses
            while (ips.hasMoreElements()) {
                val ip = ips.nextElement()
                if (ip is Inet4Address && trustedPeer(ip)) urls.add("http://${ip.hostAddress}:$port")
            }
        }
        return urls.distinct()
    }
}