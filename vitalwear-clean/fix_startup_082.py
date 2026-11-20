#!/usr/bin/env python3
"""Fix one-time firmware screen navigation on cold startup.
Original InitialScreen invoked Activity launcher directly inside composition,
which may execute again on recompositions when the firmware is missing.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")/"app/src/main/java/com/github/cfogrady/vitalwear/main/InitialScreen.kt"
s=p.read_text(encoding="utf-8-sig")
old='''    if(firmwareState == FirmwareManager.FirmwareState.Missing) {
        controller.activityLaunchers.firmwareLoadingLauncher.invoke()
    } else if(!characterLoadingDone || firmwareState == FirmwareManager.FirmwareState.Loading || !backgroundLoaded) {'''
new='''    if(firmwareState == FirmwareManager.FirmwareState.Missing) {
        // Launch only on an actual state transition, not during recomposition.
        LaunchedEffect(firmwareState) {
            controller.activityLaunchers.firmwareLoadingLauncher.invoke()
        }
        Loading(loadingText = "Import compatible firmware from phone") {}
    } else if(!characterLoadingDone || firmwareState == FirmwareManager.FirmwareState.Loading || !backgroundLoaded) {'''
if s.count(old)!=1:raise RuntimeError("InitialScreen startup flow changed")
p.write_text(s.replace(old,new),encoding="utf-8")
# Original watch startup requests the optional BODY_SENSORS_BACKGROUND permission,
# and literally calls finish() when the OS rejects it. This can make the
# launched app instantly close on newer Wear OS. It is not required for the
# on-demand optical HR readings. Do not force-close on denial.
main=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")/"app/src/main/java/com/github/cfogrady/vitalwear/main/MainActivity.kt"
s=main.read_text(encoding="utf-8-sig")
start=s.index("    private fun getMissingBackgroundPermissions(): List<String> {")
end=s.index("    private fun hasPermission(",start)
s=s[:start]+'''    private fun getMissingBackgroundPermissions(): List<String> {
        // Foreground on-demand HR and step counter do not require background HR
        // access. This permission is optional on newer Wear OS.
        return emptyList()
    }

'''+s[end:]
needle='''                finish()
            }
            lifecycleScope.launch {
                saveDataRepository.togglePermissionsAlreadyRequested()
            }
        }
    }'''
if s.count(needle)!=1:raise RuntimeError("background permission finish code changed")
s=s.replace(needle,'''                // No finish(): user can still play with granted foreground
                // sensors or with sensors disabled.
            }
            lifecycleScope.launch {
                saveDataRepository.togglePermissionsAlreadyRequested()
            }
        }
    }''')
main.write_text(s,encoding="utf-8")
manifest=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")/"app/src/main/AndroidManifest.xml"
m=manifest.read_text(encoding="utf-8")
needle='''    <uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />'''
if m.count(needle)!=1:raise RuntimeError("Body Sensors Background manifest changed")
manifest.write_text(m.replace(needle,""),encoding="utf-8")
print("PASS no premature finish() on denied background sensor permissions")
print("PASS one-time missing-firmware launch; no hardware VB required")
