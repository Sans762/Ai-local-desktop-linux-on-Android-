# Android runtime smoke tests

Use a disposable TEST installation. The runner refuses the production package,
writes diagnostic files in the test app, and presses real stop buttons. It does
not clear app data, change a login password, or import an AI model.

Build the usual ARM64 app and instrumentation APK:
```text
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Install both APKs, then run on native ARM64 hardware:
```text
adb shell am instrument -w -e desktop true com.localai.linuxagent.desktop.test.v014.test/com.localai.controller.RuntimeSmokeInstrumentation
adb shell am force-stop com.localai.linuxagent.desktop.test.v014
adb shell am instrument -w -e phase recover com.localai.linuxagent.desktop.test.v014.test/com.localai.controller.RuntimeSmokeInstrumentation
```

Read files/runtime-smoke/report.json with adb run-as after EACH invocation;
the next invocation replaces it. Inspect every pass field. The shell exit code
of am instrument does not establish success. Desktop packages need internet
only for the initial installation. The six-character VNC test PIN keeps the
desktop phone-local.

The full run checks actual X11 windows, nonuniform noVNC canvas pixels, pointer
coordinates and click focus, keyboard-to-Linux file output with a unique token,
AI bubble visibility, rotation without reconnecting, and desktop stop/restart.
Recovery runs in a new Android PID and must require fresh approval.
It does not exercise GGUF inference or native model abortion.

## PC diagnostic environment

On 2026-11-05 the shipped v0.24 ARM64 PRoot crashed under the SDK 37 emulator's
ndk_translation runner, including on --version. A separate x86_64 diagnostic
worktree was used to test shared v0.25 Kotlin, manifest and viewer code:

- Package: com.localai.linuxagent.desktop.emulator
- Alpine 3.23.7 x86_64; rootfs SHA-256:
  7f483b63722c9da91206a648175fdbbbf1cf0f5dc1868364a66007204618c961
- Official Termux PRoot 5.1.107.102 x86_64; deb SHA-256:
  2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758
- libtalloc 2.5.0 and libandroid-shmem 0.7 from the same repository.
- For APK packaging, PRoot's DT_NEEDED name libtalloc.so.2 was changed to
  libtalloc.so without changing instruction bytes. LD_LIBRARY_PATH points to
  nativeLibraryDir in this diagnostic build only.

Invoke the same runner with the emulator package followed by .test.
All 21 full-run checks and the separate process-recovery check passed.
The ARM64 v0.25 APK also installed over the previous v0.24 TEST APK with the
same signing certificate and retained the existing Linux sentinel file.
These results do not certify native ARM64 execution, production-v0.22 upgrades,
or physical-device battery/background behavior.
