package com.localai.controller

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only report explicitly exported by the user via Android Share.
 * Never includes chats, passwords, API tokens, model bytes, shell history or IPs.
 * Does NOT expose a remote shell, public endpoint or an unattended access token.
 */
object LinuxSupportReport {
    private fun scrub(input: String): String = input
        .replace(Regex("""(?i)(?:password|passwd|token|secret|authorization|apikey)\s*[:=]\s*\S+"""),
            "[redacted-secret]")
        .replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""), "[private-ip]")
        .replace(Regex("""(?:/data/(?:data|user)/\d*/|/storage/emulated/\d+/)[^\s]+"""),
            "[private-path]")
        .take(700)

    fun snapshot(context: Context, withLinuxCheck: Boolean = false): String {
        val runtime = LinuxRuntime(context)
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val desktopError = LinuxDesktopService.error(context)
        val linuxCheck = if (withLinuxCheck && runtime.available()) {
            try {
                scrub(runtime.diagnostics().takeLast(1800))
            } catch (e: Exception) {
                "Diagnostics unavailable: " + scrub(e.message.orEmpty())
            }
        } else if (!runtime.installed()) {
            "Linux has not been installed"
        } else {
            "Linux diagnostics not requested"
        }
        return buildString {
            appendLine("Local AI • Support report")
            appendLine("Generated: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("App version: " + packageInfo.versionName.orEmpty())
            appendLine("App package: " + context.packageName)
            appendLine("Android SDK: " + Build.VERSION.SDK_INT)
            appendLine("CPU architecture: " + Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            appendLine("AI service: " + if (AiForegroundService.running) "running" else "stopped")
            appendLine("Alpine rootfs: " + if (runtime.installed()) "installed" else "missing")
            appendLine("PRoot binaries: " + if (runtime.binariesPresent()) "available" else "missing")
            appendLine("Desktop packages: " + if (LinuxDesktopService.installed(context)) "installed" else "missing")
            appendLine("Linux Desktop running: " + LinuxDesktopService.running)
            appendLine("Desktop installer running: " + LinuxDesktopService.installing)
            appendLine("Desktop status: " + scrub(LinuxDesktopService.status(context)))
            appendLine("Last desktop error: " + if (desktopError.isBlank()) "none" else scrub(desktopError))
            appendLine("Linux self-check: " + linuxCheck)
            appendLine()
            appendLine("Privacy: no passwords, chat content, shell history or model files included.")
            appendLine("Access: local-only. ChatGPT is not connected to this phone.")
        }
    }
}
