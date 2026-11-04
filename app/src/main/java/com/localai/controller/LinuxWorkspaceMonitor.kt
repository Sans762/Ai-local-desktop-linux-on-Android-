package com.localai.controller

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/** Best-effort, explicitly labelled telemetry. Never fabricates unavailable GPU metrics. */
class LinuxWorkspaceMonitor(private val context: Context) {
    private var prevTicks: LongArray? = null
    private var prevAppCpuMs = -1L
    private var prevTimeMs = -1L
    private var prevRx = -1L
    private var prevTx = -1L

    private fun cpuTicks(): LongArray? = try {
        val line = File("/proc/stat").useLines { it.firstOrNull() }
            ?: return null
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.firstOrNull() != "cpu" || fields.size < 5) null
        else fields.drop(1).map { it.toLong() }.toLongArray()
    } catch (_: Exception) { null }

    @Synchronized fun snapshot(): JSONObject {
        val now = SystemClock.elapsedRealtime()
        val ticks = cpuTicks()
        var systemCpu = -1.0
        val previous = prevTicks
        if (previous != null && ticks != null && ticks.size >= 5 && previous.size == ticks.size) {
            val totalDelta = ticks.sum() - previous.sum()
            val idleDelta = (ticks[3] + ticks.getOrElse(4) { 0L }) -
                (previous[3] + previous.getOrElse(4) { 0L })
            if (totalDelta > 0) {
                systemCpu = ((totalDelta - idleDelta).toDouble() * 100.0 / totalDelta)
                    .coerceIn(0.0, 100.0)
            }
        }
        prevTicks = ticks
        val appCpuMs = Process.getElapsedCpuTime()
        var appCpu = -1.0
        if (prevTimeMs > 0 && now > prevTimeMs && prevAppCpuMs >= 0 && appCpuMs >= prevAppCpuMs) {
            val cpuDelta = appCpuMs - prevAppCpuMs
            val elapsed = now - prevTimeMs
            appCpu = (cpuDelta.toDouble() * 100.0 / elapsed)
                .coerceIn(0.0, 100.0 * Runtime.getRuntime().availableProcessors())
        }
        prevAppCpuMs = appCpuMs

        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val totalMb = memory.totalMem / (1024 * 1024)
        val freeMb = memory.availMem / (1024 * 1024)

        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        var rxKbs = -1.0
        var txKbs = -1.0
        if (rx >= 0 && tx >= 0 && prevRx >= 0 && prevTx >= 0 && prevTimeMs > 0 && now > prevTimeMs) {
            val seconds = (now - prevTimeMs).toDouble() / 1000.0
            rxKbs = ((rx - prevRx).coerceAtLeast(0).toDouble() / 1024.0 / seconds)
            txKbs = ((tx - prevTx).coerceAtLeast(0).toDouble() / 1024.0 / seconds)
        }
        prevRx = rx
        prevTx = tx
        prevTimeMs = now

        val networkType = try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            when {
                capabilities == null -> "ไม่เชื่อมต่อ"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "มือถือ"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "เครือข่าย"
            }
        } catch (_: Exception) { "ไม่ทราบชนิด" }
        return JSONObject()
            .put("ok", true)
            .put("systemCpuPercent", systemCpu)
            .put("appCpuPercent", appCpu)
            .put("cpuSource", if (systemCpu >= 0) "system" else if (appCpu >= 0) "app" else "unavailable")
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
            .put("freeRamMb", freeMb)
            .put("totalRamMb", totalMb)
            .put("gpuPercent", JSONObject.NULL) // No portable Android GPU utilization API.
            .put("gpuStatus", "ไม่มีค่าที่ระบบอนุญาตให้อ่าน")
            .put("networkType", networkType)
            .put("appRxKbs", rxKbs)
            .put("appTxKbs", txKbs)
            .put("networkScope", "traffic of this app UID, not whole Wi-Fi")
            .put("linuxDesktopRunning", LinuxDesktopService.running)
            .put("linuxInstalled", LinuxRuntime(context).installed())
            .put("timestampMs", System.currentTimeMillis())
    }
}
