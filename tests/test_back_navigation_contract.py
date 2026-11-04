#!/usr/bin/env python3
"""Prevents regression to Android's default exit on a predictive back swipe."""
from pathlib import Path

s = Path("app/src/main/java/com/localai/controller/MainActivity.kt").read_text()
manifest = Path("app/src/main/AndroidManifest.xml").read_text()
assert 'android:enableOnBackInvokedCallback="true"' in manifest
assert "installGestureBackHandler()" in s
assert "onBackInvokedDispatcher.registerOnBackInvokedCallback(" in s
assert "onBackInvokedDispatcher.unregisterOnBackInvokedCallback(" in s
assert "android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT" in s
assert "override fun onBackPressed()" in s and "navigateBackWithinApp()" in s
assert 'currentTab == "control" && settingsCategory != "start"' in s
assert 'currentTab == "workspace"' in s and 'currentTab == "desktop"' in s
assert 'showTab("control")' in s
assert 'moveTaskToBack(true)' in s
assert "lastBackAtMs" in s
assert "super.onBackPressed()" not in s, "Legacy default Back can immediately exit the app"
assert "stopService(" not in s[s.index("private fun navigateBackWithinApp()"):s.index("override fun onBackPressed()", s.index("private fun navigateBackWithinApp()"))], "Back should not kill AI/Linux service"
print("PASS: swipe, hardware Back, settings categories and safe app-home behavior")
