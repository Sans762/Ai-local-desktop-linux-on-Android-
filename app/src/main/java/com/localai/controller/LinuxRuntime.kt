package com.localai.controller

import android.content.Context
import android.system.Os
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

/** Embedded Alpine userland on Android's Linux kernel, isolated inside app storage with PRoot. */
class LinuxRuntime(private val context: Context) {
    private val dir = File(context.filesDir, "embedded_linux")
    private val rootfs = File(dir, "alpine-rootfs")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val loader = File(nativeDir, "libproot-loader.so")

    fun available(): Boolean = rootfs.resolve("bin/busybox").isFile && proot.isFile && loader.isFile
    fun installed(): Boolean = rootfs.resolve("bin/busybox").isFile
    fun binariesPresent(): Boolean = proot.isFile && loader.isFile

    /**
     * The Alpine minirootfs may expose only main, while TigerVNC requires
     * community. Append the matching official HTTPS mirror only in app storage.
     */
    fun ensureDesktopRepositories(): String {
        require(installed()) { "ติดตั้ง Alpine Linux ก่อน" }
        val version = File(rootfs, "etc/alpine-release").readText().trim()
        val branch = Regex("""^([0-9]+)\.([0-9]+)\.""").find(version)
            ?: error("อ่านเวอร์ชัน Alpine ไม่ได้: $version")
        val base = "https://dl-cdn.alpinelinux.org/alpine/v${branch.groupValues[1]}.${branch.groupValues[2]}"
        val repositories = File(rootfs, "etc/apk/repositories")
        repositories.parentFile?.mkdirs()
        val original = if (repositories.isFile) repositories.readText() else ""
        val entries = original.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val missing = listOf("main", "community").map { "$base/$it" }.filter { official ->
            entries.none { entry ->
                entry.removeSuffix("/") == official ||
                    (entry.contains("/alpine/v${branch.groupValues[1]}.${branch.groupValues[2]}/") &&
                        entry.endsWith("/" + official.substringAfterLast('/')))
            }
        }
        if (missing.isNotEmpty()) {
            repositories.appendText(
                (if (original.isNotEmpty() && !original.endsWith("\n")) "\n" else "") +
                    missing.joinToString("\n", postfix = "\n")
            )
        }
        val dns = File(rootfs, "etc/resolv.conf")
        if (!dns.isFile || dns.length() == 0L) {
            dns.parentFile?.mkdirs()
            dns.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        refreshPackageDns()
        return "Alpine $version repositories: main + community"
    }

    /** Use Android's active network DNS instead of hard-coded public resolvers
     * (which may be blocked by phone networks, VPNs and captive Wi-Fi).
     */
    private fun refreshPackageDns() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return
        val addresses = manager.getLinkProperties(network)?.dnsServers.orEmpty()
            .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
        if (addresses.isEmpty()) return
        val file = File(rootfs, "etc/resolv.conf")
        file.parentFile?.mkdirs()
        file.writeText(addresses.joinToString("\n") { "nameserver $it" } + "\n")
    }


    /** Run on a worker thread. Never overwrite an existing Linux rootfs or user's Linux files. */
    fun install(progress: (String) -> Unit) {
        if (installed()) { progress("Alpine Linux is already installed"); return }
        require(binariesPresent()) { "Missing PRoot executables in APK" }
        dir.mkdirs()
        dir.listFiles()?.filter { it.isDirectory &&
            it.name.matches(Regex("alpine-extract-[0-9]{8,20}")) }
            ?.forEach { it.deleteRecursively() } // abandoned staging only, never rootfs
        val staging = File(dir, "alpine-extract-${System.currentTimeMillis()}")
        require(staging.mkdirs()) { "Cannot create Linux filesystem" }
        val links = mutableListOf<Pair<File, String>>()
        var count = 0
        var extracted = 0L
        try {
            context.assets.open("alpine-rootfs.bundle").use { raw ->
                TarArchiveInputStream(GZIPInputStream(raw, 64 * 1024)).use { tar ->
                    while (true) {
                        if (Thread.currentThread().isInterrupted) error("Linux installation interrupted")
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        count++
                        require(count < 60_000) { "Too many Linux archive entries" }
                        val normalized = entry.name.removePrefix("./").trimStart('/')
                        if (normalized.isBlank()) continue
                        val target = File(staging, normalized)
                        require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Invalid archive path" }
                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isSymbolicLink -> links.add(target to entry.linkName)
                            entry.isFile -> {
                                require(entry.size in 0..45_000_000) { "Oversized Linux archive entry" }
                                require(extracted + entry.size <= 400_000_000) { "Linux archive expanded beyond limit" }
                                target.parentFile?.mkdirs()
                                target.outputStream().buffered().use { out ->
                                    val buffer = ByteArray(32768)
                                    var remaining = entry.size
                                    while (remaining > 0) {
                                        if (Thread.currentThread().isInterrupted) error("Linux installation interrupted")
                                        val n = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        require(n > 0) { "Truncated rootfs archive" }
                                        out.write(buffer, 0, n); remaining -= n
                                    }
                                }
                                target.setExecutable(entry.mode and 0b001_001_001 != 0, false)
                                extracted += entry.size
                            }
                        }
                        if (count % 250 == 0) progress("Unpacking Linux · $count files")
                    }
                }
            }
            // Create symlinks only after regular-file extraction to block symlink traversal.
            for ((target, link) in links) {
                if (target.exists()) continue
                require(link.isNotBlank() && !link.contains('\u0000')) { "Bad symlink target" }
                target.parentFile?.mkdirs()
                Files.createSymbolicLink(target.toPath(), Paths.get(link))
            }
            require(File(staging, "bin/busybox").isFile) { "Invalid Alpine Linux rootfs" }
            File(staging, "root").mkdirs()
            File(staging, "tmp").mkdirs()
            val resolv = File(staging, "etc/resolv.conf")
            if (!resolv.exists()) resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            if (rootfs.exists()) error("Existing Linux path detected; refusing to overwrite")
            require(staging.renameTo(rootfs)) { "Cannot activate Linux filesystem" }
            progress("Linux installed · Alpine 3.23 ARM64")
        } catch (e: Exception) {
            // Only remove staging files created by this attempt; never touch an existing rootfs.
            staging.deleteRecursively()
            throw e
        }
    }

    /**
     * Xvnc creates Unix sockets in /tmp/.X11-unix and (unless disabled) an X
     * server lock in /tmp. Old Alpine installs may have a non-writable /tmp
     * because tar extraction did not retain directory modes. Repair ONLY
     * app-private temp directory permissions; never erase Linux user files or
     * a potentially active X session's sockets.
     */
    fun prepareDesktopTemp() {
        require(available()) { "ติดตั้ง Linux ก่อนเปิดหน้าจอ" }
        val dirs = listOf(File(rootfs, "tmp"), File(rootfs, "tmp/.X11-unix"))
        for (folder in dirs) {
            require(!Files.isSymbolicLink(folder.toPath())) {
                "โฟลเดอร์ชั่วคราว Linux ไม่ปลอดภัย"
            }
            require(folder.isDirectory || folder.mkdirs()) {
                "เตรียมโฟลเดอร์ชั่วคราว Linux ไม่สำเร็จ"
            }
            // 01777: world-writable sticky directory, the normal Linux /tmp mode.
            // The Android application still owns and isolates these paths.
            Os.chmod(folder.absolutePath, 1023)
            require(folder.canWrite()) { "ไม่มีสิทธิ์เขียนไฟล์ชั่วคราว Linux" }
        }
    }

    /** Local-only, one-shot Linux console command, not exposed on the remote HTTP API. */
    fun command(command: String, timeoutSeconds: Long = 35, onOutput: ((String) -> Unit)? = null): String {
        require(installed() && binariesPresent()) { "Install Linux from Control Center first" }
        require(command.length in 1..1000) { "Command must have 1–1000 characters" }
        val cmd = listOf(proot.absolutePath, "-0", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-w", "/root", "/bin/sh", "-lc", command)
        val builder = ProcessBuilder(cmd).redirectErrorStream(true)
        builder.environment().apply {
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TERM", "xterm-256color")
        }
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        synchronized(output) {
                            if (output.length < 128_000) output.append(line.take(2_000)).append('\n')
                            try { onOutput?.invoke(line.take(160)) } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            reader.interrupt()
            Thread.currentThread().interrupt()
            throw IllegalStateException("Linux command cancelled", e)
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        reader.join(2000)
        val result = synchronized(output) { output.toString() }
        return result.take(128_000) + if (finished) "\n[exit ${process.exitValue()}]" else "\n[timeout: process stopped]"
    }

    /**
     * Start a long-lived Linux GUI process inside the private Alpine PRoot.
     * The Android foreground service owns and tears down the Process objects.
     */
    fun startDesktopProcess(
        executable: String,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        logName: String
    ): Process {
        require(available()) { "Install Alpine Linux first" }
        require(executable in setOf("/usr/bin/Xvnc", "/usr/bin/openbox", "/usr/bin/xterm", "/usr/bin/pcmanfm", "/usr/bin/lxpanel", "/usr/bin/tint2", "/usr/bin/dbus-run-session")) {
            "Unexpected desktop executable"
        }
        require(args.size < 40 && args.all { it.length < 256 }) { "Invalid argument" }
        require(logName.matches(Regex("[a-z0-9-]{1,36}"))) { "Bad log file name" }
        val argv = listOf(
            proot.absolutePath, "-0", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-w", "/root",
            executable
        ) + args
        val builder = ProcessBuilder(argv)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(File(dir, "$logName.log"))
        builder.environment().apply {
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put("PROOT_TMP_DIR", context.cacheDir.absolutePath)
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TERM", "xterm-256color")
            putAll(extraEnv)
        }
        return builder.start()
    }

    /** Read only the final 8 KiB of a process log. */
    fun desktopLog(logName: String): String {
        require(logName.matches(Regex("[a-z0-9-]{1,36}"))) { "Bad desktop log name" }
        val file = File(dir, "$logName.log")
        if (!file.isFile) return ""
        return RandomAccessFile(file, "r").use { input ->
            val count = minOf(input.length(), 8192L).toInt()
            input.seek(input.length() - count)
            val data = ByteArray(count)
            input.readFully(data)
            String(data, Charsets.UTF_8).takeLast(5000)
        }
    }

    /** Safe, non-mutating Linux self-test for end-user diagnostics. */
    fun diagnostics(): String = command(
        "printf 'LINUX_OK\\n'; uname -m; cat /etc/alpine-release; apk --version; " +
            "test -w /root && echo HOME_WRITABLE; " +
            "test -e /usr/bin/Xvnc && echo XVNC_INSTALLED || echo XVNC_MISSING",
        timeoutSeconds = 25
    )

}