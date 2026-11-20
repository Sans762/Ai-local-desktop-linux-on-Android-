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
print("PASS one-time missing-firmware launch; no hardware VB required")
