#!/usr/bin/env python3
"""Regression guard for the common desktop download-stuck and settings clutter report.
These static checks do not replace on-device runtime testing.
"""
from pathlib import Path

src = Path("app/src/main/java/com/localai/controller")
service = (src / "LinuxDesktopService.kt").read_text()
runtime = (src / "LinuxRuntime.kt").read_text()
ui = (src / "MainActivity.kt").read_text()
assert 'fun ensureDesktopRepositories()' in runtime
assert 'listOf("main", "community")' in runtime
assert 'fun refreshPackageDns()' in runtime
assert "getLinkProperties(network)?.dnsServers" in runtime
assert 'linux.ensureDesktopRepositories()' in service
assert 'linux.command(setup, timeoutSeconds = 480)' in service
assert 'apk --wait 60 add --no-cache $packages' in service
assert 'apkWriterLock.lockInterruptibly()' in runtime
assert 'apkWriterLock.unlock()' in runtime
assert 'กรุณารอให้การติดตั้งก่อนหน้าจบ' in service
assert 'apk update && apk add' not in service
assert 'NetworkCapabilities.NET_CAPABILITY_VALIDATED' in service
assert 'fun status(context: Context)' in service
assert 'fun error(context: Context)' in service
assert 'completionNotification(false)' in service
assert 'completionNotification(true)' in service
assert 'if (actionInstalling) installing = true' in service
assert 'val ip = if (password.length >= 8) privateIp() else null' in service, 'Short VNC PINs must not expose LAN access'
assert 'if (ip != null)' in service
assert "ACTION_INSTALL_FULL" in service
assert "ACTION_INSTALL_VISION" in service
assert "scrot tesseract-ocr tesseract-ocr-data-eng tesseract-ocr-data-tha" in service
assert "fun visionInstalled(context: Context)" in service
assert "เพิ่มการอ่านตัวอักษรบนจอให้ AI (OCR)" in ui
assert 'xfce4 xfce4-terminal dbus adwaita-icon-theme' in service
assert 'fun fullInstalled(context: Context)' in service
assert '"dbus-run-session"' in service
assert '"xfce4-session"' in service
assert 'if (!xfceStarted)' in service
assert '"/usr/bin/dbus-run-session"' in runtime
assert 'ติดตั้ง XFCE Desktop แบบเต็ม' in ui
assert 'private fun startDesktop(linux: LinuxRuntime, password: String, ip: String?)' in service
# Xvnc on Android PRoot must have an app-private writable /tmp.
assert "fun prepareDesktopTemp()" in runtime
assert 'File(rootfs, "tmp/.X11-unix")' in runtime
assert 'Os.chmod(folder.absolutePath, 1023)' in runtime
assert 'Files.isSymbolicLink(folder.toPath())' in runtime
assert 'linux.prepareDesktopTemp()' in service
assert 'listOf(":1", "-nolock", "-geometry"' in service
assert '"900x1180" else "1280x800"' in service
assert 'if (password.length >= 8) privateIp() else null' in service
assert 'password.matches(Regex("[A-Za-z0-9]{6,8}"))' in service
assert 'key.matches(Regex("[A-Za-z0-9]{6,8}"))' in ui
assert 'LOCAL_VNC_PORT), 300' in service, "Do not replace an active local desktop"
assert 'ดูสาเหตุที่เปิด Linux ไม่ได้' in ui
assert 'setNeutralButton("ข้อมูลเทคนิค")' in ui
assert 'desktopAdvancedVisible' in ui
assert '＋  ตัวเลือกเพิ่มเติม' in ui
assert 'ui.postDelayed(ticker, 650)' in ui
assert 'previousBusy != nowBusy' in ui
assert 'LinuxDesktopService.status(this@MainActivity)' in ui
assert 'LinuxDesktopService.error(this@MainActivity)' in ui
assert 'ดูสาเหตุที่เปิด Linux ไม่ได้' in ui
assert 'ตรวจในแถบแจ้งเตือน' not in ui, "Removed stale manual status placeholder"
assert 'ดาวน์โหลดหน้าจอ Linux (ครั้งแรก)' not in ui, "Old overwhelming desktop panel"
assert 'apk update && apk add' not in service

# The mobile installer must not pause simply because the screen turned off.
manifest = Path("app/src/main/AndroidManifest.xml").read_text()
setup = (src / "LinuxInstallService.kt").read_text()
assert 'android.permission.WAKE_LOCK' in manifest
assert 'PowerManager.PARTIAL_WAKE_LOCK' in setup
assert 'PowerManager.PARTIAL_WAKE_LOCK' in service
assert 'wakeLock.release()' in setup
assert 'setupWakeLock.release()' in service
# Never report a dead Xvnc server as healthy; leave the log visible.
assert 'private var healthThread: Thread?' in service
assert 'while (!closing && xvnc.isAlive)' in service
assert 'linux.desktopLog("xvnc").takeLast(600)' in service
assert 'healthThread?.interrupt()' in service

print("PASS: live installer state, Alpine repositories/DNS, bounded retries, compact desktop UI")
