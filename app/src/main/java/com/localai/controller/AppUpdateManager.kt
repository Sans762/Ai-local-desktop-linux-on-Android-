package com.localai.controller

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases only. No auto-install, no remote code execution.
 * The user always decides whether to download and install the update.
 */
class AppUpdateManager(private val context: Context) {
    data class Release(
        val tag: String,
        val version: String,
        val downloadUrl: String,
        val pageUrl: String,
        val newer: Boolean
    )

    private val repo = "Sans762/Ai-local-desktop-linux-on-Android-"
    private val officialBase = "https://github.com/$repo/releases/download/"
    private val versionPattern = Regex("^v(\\d+)\\.(\\d+)(?:\\.(\\d+))?-linux-desktop$")
    private val currentPattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")

    private fun version(text: String, pattern: Regex): List<Int>? {
        val match = pattern.find(text) ?: return null
        return (1..3).map { match.groups[it]?.value?.toIntOrNull() ?: 0 }
    }

    fun currentVersion(): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }

    private fun isNewer(candidate: List<Int>, current: List<Int>): Boolean {
        for (i in 0..2) {
            if (candidate[i] > current[i]) return true
            if (candidate[i] < current[i]) return false
        }
        return false
    }

    fun checkLatest(): Release {
        val connection = (URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LocalAI-LinuxDesktop-Android")
            instanceFollowRedirects = false
        }
        try {
            require(connection.responseCode == 200) {
                "GitHub Releases returned HTTP ${connection.responseCode}"
            }
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(131073)
                var size = 0
                while (size < buffer.size) {
                    val n = stream.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    size += n
                }
                require(size <= 131072) { "Release metadata too large" }
                buffer.copyOf(size)
            }
            val release = JSONObject(String(bytes, Charsets.UTF_8))
            val tag = release.optString("tag_name", "")
            val latest = version(tag, versionPattern)
                ?: error("Latest release is not a compatible Linux Desktop update")
            val title = latest.joinToString(".")
            val expectedName = "LocalAI-LinuxDesktop-v${latest[0]}.${latest[1]}-ARM64.apk"
            val assets = release.optJSONArray("assets") ?: error("No APK assets in release")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val item = assets.getJSONObject(i)
                if (item.optString("name") != expectedName) continue
                val value = item.optString("browser_download_url")
                if (value.startsWith(officialBase + tag + "/") &&
                    value.endsWith("/" + expectedName) && URL(value).protocol == "https") {
                    url = value
                    break
                }
            }
            val verifiedUrl = url ?: error("Could not verify an official APK asset")
            val page = release.optString("html_url")
            require(page == "https://github.com/$repo/releases/tag/$tag") {
                "Unexpected release page URL"
            }
            val local = version(currentVersion(), currentPattern) ?: listOf(0, 0, 0)
            return Release(tag, title, verifiedUrl, page, isNewer(latest, local))
        } finally {
            connection.disconnect()
        }
    }
}
