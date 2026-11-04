#!/usr/bin/env python3
"""Signing/migration safety contract (static checks; device tests still required)."""
from pathlib import Path

root = Path("app/src/main/java/com/localai/controller")
gradle = Path("app/build.gradle.kts").read_text()
workflow = Path(".github/workflows/build-apk.yml").read_text()
signer = Path("tools/setup-release-signing.sh").read_text()
restore = (root / "RestoreService.kt").read_text()
backup = (root / "BackupService.kt").read_text()
activity = (root / "MainActivity.kt").read_text()
manifest = Path("app/src/main/AndroidManifest.xml").read_text()

assert 'applicationId = "com.localai.linuxagent.desktop"' in gradle
assert 'applicationIdSuffix = ".test.v014"' in gradle
assert 'signingConfigs.findByName("persistent")' in gradle
for secret in ("RELEASE_KEYSTORE_BASE64", "RELEASE_KEYSTORE_PASSWORD",
               "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"):
    assert secret in workflow and secret in signer
assert "gh secret set" in signer
assert "gh workflow run build-apk.yml" in signer
assert '[[ ! -f "$KEYSTORE" ]]' in signer
assert "keytool -genkeypair" in signer
assert 'chmod 600 "$KEYSTORE"' in signer
assert "if: success() && env.AI_APK_KIND == 'release'" in workflow, "Do not upload debug APK as an update"

assert "RestoreService" in manifest
assert 'android:exported="false"' in manifest
assert "RestoreService.running" in activity
assert 'Intent.ACTION_OPEN_DOCUMENT' in activity
assert 'requestCode == 45' in activity
assert '⇄  ย้ายข้อมูลจากแอปเก่า' in activity
assert '⬇  สำรองข้อมูลก่อนย้าย' in activity, "TEST builds must show one-tap backup"
assert 'private fun startBackupPicker()' in activity, "Backup picker must be shared by quick and advanced actions"
assert 'LinuxInstallService.running || AiForegroundService.running' in activity, "Do not back up during Alpine install"
assert activity.count("startBackupPicker()") >= 3, "Backup action must be reachable from TEST and advanced menu"
assert 'when (settingsCategory)' in activity, "Navigation must remain available"
assert "EXTRA_SOURCE" in restore
assert 'manifest!!.optString("format")' in restore
assert '"localai-linux-backup"' in restore
assert "getParcelableExtra<Uri>" in restore
assert "TarArchiveInputStream" in restore and "GZIPInputStream" in restore
assert "entry.name.removePrefix" in restore
assert "canonicalPath.startsWith" in restore
assert "Files.walkFileTree" in restore, "Do not follow backup symlinks during cleanup"
assert "if (!existingLinux.exists()" in restore, "Never overwrite existing Linux rootfs"
assert "if (!dest.exists()" in restore, "Never overwrite existing GGUF model"
assert "db.importLegacy(dbStage)" in restore, "Safely merge old chat history"
assert "filesDir.usableSpace" in restore, "Bound extraction by available space"
assert '"ai_name"' in backup and '"model_file"' in backup
assert '"pass_hash"' not in backup and '"session"' not in backup
print("PASS: fixed production ID, private one-time signing, safe old-app backup migration")
