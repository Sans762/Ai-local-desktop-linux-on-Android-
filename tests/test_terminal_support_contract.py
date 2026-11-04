#!/usr/bin/env python3
"""Regression contract for private in-app Linux shell and explicit ChatGPT sharing.
No automatic remote access or sensitive support uploads are allowed.
"""
from pathlib import Path

root = Path("app/src/main/java/com/localai/controller")
main = (root / "MainActivity.kt").read_text()
runtime = (root / "LinuxRuntime.kt").read_text()
report = (root / "LinuxSupportReport.kt").read_text()
http = (root / "LocalHttpServer.kt").read_text()

assert '"terminal" -> showTerminal()' in main
assert 'settingsCategory = "linux"' in main
assert 'showTab("terminal")' in main
assert 'private fun showTerminal()' in main
assert 'private fun startTerminalCommand(command: String)' in main
assert 'LinuxRuntime(this).command(command, timeoutSeconds = 90)' in main
assert 'terminalCommandThread?.interrupt()' in main
assert 'private fun shareLinuxSupportReport()' in main
assert 'Intent(Intent.ACTION_SEND)' in main
assert 'Intent.createChooser' in main
assert 'LinuxSupportReport.snapshot(this, withLinuxCheck = true)' in main

assert '"/bin/sh", "-lc", command' in runtime
assert 'Thread.currentThread().interrupt()' in runtime
assert 'process.destroyForcibly()' in runtime
assert 'fun diagnostics()' in runtime

assert "context.packageName" in report
assert "LinuxDesktopService.error(context)" in report
assert '"[redacted-secret]"' in report
assert '"[private-ip]"' in report
assert 'chat content' in report
assert 'No shell logs' not in report  # last error only, not command history

assert '"/api/terminal"' not in http
assert '"/api/support/remote"' not in http
assert "ChatGPT is not connected to this phone" in report
print("PASS: explicit support share, private local Linux Terminal, safe cancel, no remote shell endpoint")
