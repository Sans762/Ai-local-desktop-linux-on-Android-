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
assert 'apk add --no-cache $packages' in service
assert 'apk update && apk add' not in service
assert 'NetworkCapabilities.NET_CAPABILITY_VALIDATED' in service
assert 'fun status(context: Context)' in service
assert 'fun error(context: Context)' in service
assert 'completionNotification(false)' in service
assert 'completionNotification(true)' in service
assert 'if (actionInstalling) installing = true' in service
assert 'val ip = privateIp()' in service
assert 'if (ip != null)' in service
assert 'private fun startDesktop(linux: LinuxRuntime, password: String, ip: String?)' in service
assert 'desktopAdvancedVisible' in ui
assert '＋  ตัวเลือกเพิ่มเติม' in ui
assert 'ui.postDelayed(ticker, 650)' in ui
assert 'previousBusy != nowBusy' in ui
assert 'LinuxDesktopService.status(this@MainActivity)' in ui
assert 'LinuxDesktopService.error(this@MainActivity)' in ui
assert 'ดูสาเหตุที่ติดตั้งไม่ได้' in ui
assert 'ตรวจในแถบแจ้งเตือน' not in ui, "Removed stale manual status placeholder"
assert 'ดาวน์โหลดหน้าจอ Linux (ครั้งแรก)' not in ui, "Old overwhelming desktop panel"
assert 'apk update && apk add' not in service
print("PASS: live installer state, Alpine repositories/DNS, bounded retries, compact desktop UI")
