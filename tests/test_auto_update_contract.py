#!/usr/bin/env python3
"""Static protections for owner-signed and automatic APK updates."""
from pathlib import Path

root = Path("app/src/main")
base = root / "java/com/localai/controller"
main = (base / "MainActivity.kt").read_text()
manager = (base / "AppUpdateManager.kt").read_text()
verifier = (base / "VerifiedApkUpdate.kt").read_text()
schedule = (base / "AutoUpdateScheduler.kt").read_text()
service = (base / "AppUpdateDownloadService.kt").read_text()
manifest = (root / "AndroidManifest.xml").read_text()
gradle = Path("app/build.gradle.kts").read_text()
workflow = Path(".github/workflows/build-apk.yml").read_text()

assert 'applicationId = "com.localai.linuxagent.desktop"' in gradle
assert 'signingConfigs' in gradle and 'persistent' in gradle
assert "RELEASE_KEYSTORE_BASE64" in workflow
assert 'AutoUpdateJobService' in manifest and 'android.permission.BIND_JOB_SERVICE' in manifest
assert 'RECEIVE_BOOT_COMPLETED' in manifest
assert 'setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)' in schedule
assert 'setPeriodic(PERIOD_MS)' in schedule and 'setPersisted(true)' in schedule
assert 'VerifiedApkUpdate.download(this, release.downloadUrl' in schedule
assert "getBoolean(\"auto_update\", true)" in schedule
assert 'AutoUpdateScheduler.sync(this)' in main
assert "อัปเดตอัตโนมัติเมื่อใช้ Wi-Fi" in main
assert 'VerifiedApkUpdate.installIntent' in main
assert "packageName.contains(\".test\")" in verifier
assert "assertCompatible" in verifier
assert "candidate.packageName == installed.packageName" in verifier
assert "candidate.longVersionCode > installed.longVersionCode" in verifier
assert "intersect(signatures(candidate)).isNotEmpty()" in verifier
assert "actualHash.equals(expectedSha, ignoreCase = true)" in verifier
assert 'contentResolver.delete' not in service and 'uninstall' not in service.lower()
assert 'Intent.ACTION_VIEW' in verifier and 'FLAG_GRANT_READ_URI_PERMISSION' in verifier
assert 'JobScheduler' in schedule and 'jobFinished(params, false)' in schedule
assert "releases/latest" in manager
print("PASS: scheduled signed updates, install compatibility, SHA-256, user confirmation, no silent uninstall")
