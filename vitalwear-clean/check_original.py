#!/usr/bin/env python3
"""Verify assembled upstream clean project. No installed watch test is implied."""
from pathlib import Path
import sys
r=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
checks={
"app/src/main/java/com/github/cfogrady/vitalwear/main/MainScreen.kt":
    ["VerticalPager(","controller.toggleSleep()","sleeping = !sleeping"],
"app/src/main/java/com/github/cfogrady/vitalwear/character/PartnerScreen.kt":
    ["lastBpm","HR "],
"app/src/main/java/com/github/cfogrady/vitalwear/background/BackgroundManager.kt":
    ["setFirmwareBackground(","setCardBackground("],
"app/src/main/java/com/github/cfogrady/vitalwear/heartrate/SingleHeartRateSensorListener.kt":
    ["withTimeoutOrNull(10000L)","sensorManager.unregisterListener(this)","TYPE_HEART_RATE"],
"app/src/main/java/com/github/cfogrady/vitalwear/steps/StepSensorService.kt":
    ["TYPE_STEP_COUNTER","SENSOR_DELAY_NORMAL","ACTIVITY_RECOGNITION not yet granted"],
"app/src/main/java/com/github/cfogrady/vitalwear/main/MainActivity.kt":
    ["getMissingPermissionsNeeded","Manifest.permission.ACTIVITY_RECOGNITION","stepService.startup()"],
"companion/src/main/java/com/github/cfogrady/vitalwear/card/ImportCardActivity.kt":
    ["check(nodes.isNotEmpty())","ImportState.ImportCard","Invalid DIM/BEM file"],
"app/src/main/java/com/github/cfogrady/vitalwear/character/VBUpdater.kt":
    ["setInexactRepeating(","AlarmManager.ELAPSED_REALTIME,"]
}
for path,needles in checks.items():
    s=(r/path).read_text(encoding="utf-8")
    for needle in needles:
        assert needle in s, path+": "+needle
    print("OK",path)
s=(r/"companion/src/main/java/com/github/cfogrady/vitalwear/card/ImportCardActivity.kt").read_text()
assert "ImportState.UnlockCard" not in s
assert "isValidatedCard(" not in s
assert "cardVerificationLauncher" not in s
assert "VitalWearHostApduService" not in s
for module in ("app","companion"):
    g=(r/module/"build.gradle.kts").read_text()
    assert 'versionName = "0.8.0-Upstream"' in g
    assert 'versionCode = 80' in g
    assert 'applicationId = "dev.mikeki.vitalwear.gw8"' in g
    assert (r/module/"libs/VB-DIM-Reader.jar").is_file()
print("PASS clean upstream baseline, physical-bracelet verification removed, original game menus preserved")
