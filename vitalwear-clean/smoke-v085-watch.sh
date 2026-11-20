#!/usr/bin/env bash
set -euo pipefail
apk="$GITHUB_WORKSPACE/outputs/VitalWear-Watch-Clean-v0.8.5.apk"
test -s "$apk"
ls -lh "$apk"
adb install -r "$apk"
adb logcat -c
adb shell am start -W -n dev.mikeki.vitalwear.gw8.clean/com.github.cfogrady.vitalwear.main.MainActivity
sleep 10
adb logcat -d -v time > /tmp/vitalwear-Watch.log
if ! adb shell pidof dev.mikeki.vitalwear.gw8.clean; then
  grep -A 50 -B 2 -E 'FATAL EXCEPTION|AndroidRuntime' /tmp/vitalwear-Watch.log | tail -120 || true
  echo "FAIL: Watch app process exited during startup"
  exit 1
fi
if grep -A 35 'FATAL EXCEPTION' /tmp/vitalwear-Watch.log | grep -q dev.mikeki.vitalwear.gw8.clean; then
  grep -A 50 'FATAL EXCEPTION' /tmp/vitalwear-Watch.log | tail -120 || true
  echo "FAIL: Watch app crashed during startup"
  exit 1
fi
echo "PASS: Watch app launch, process alive after 10 seconds"
