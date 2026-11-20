#!/usr/bin/env python3
"""Checks that v0.6.7 actually removed dead UI and restored VB game behavior.
Assertions run against the *assembled* Kotlin source after every script.
"""
from pathlib import Path
import sys, math
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base=root/"app/src/main/java/com/github/cfogrady/vitalwear"
files={}
for rel in [
"main/VbMenuScreen.kt","main/VbHubActivity.kt","main/MainScreen.kt",
"composable/pixel/VbPixelArt.kt","character/PixelHome.kt",
"background/BackgroundSelectionActivity.kt",
"main/MainScreenControllerImpl.kt","transfer/TransferScreen.kt",
]:
    files[rel]=(base/rel).read_text(encoding="utf-8")
m=files["main/VbMenuScreen.kt"]
assert 'VbAction("ITEMS"' not in m
assert 'VbAction("DIGIMON"' not in m
assert 'VbAction("STATUS"' in m
assert 'VbAction("MISSION"' in m
assert 'isSleeping=!isSleeping' in m
assert 'controller.toggleSleep()' in m
hub=files["main/VbHubActivity.kt"]
assert 'VerticalPager(state=state' in hub
assert 'VbStatusTimeline(activity,onExit)' in hub
assert 'if(section=="STATUS" || section=="DIGIMON")' in hub
assert '"ITEMS" -> listOf(' not in hub
for section in ["VITAL","HEART RATE","MILE","BASIC INFO",
                "DIGIMON STATUS","BATTLE RECORD","TROPHIES","EVOLUTION"]:
    assert '"'+section+'"' in hub
assert 'item("PARTNER")' in hub
assert '"SOUND ON"' in hub
assert 'item("BACKGROUND")' in hub
home=files["character/PixelHome.kt"]
assert "Art.selectedBackground(canvas,scene)" in home
assert "Art.city(canvas)" not in home
main=files["main/MainScreen.kt"]
assert 'LaunchedEffect(scene) {pager.scrollToPage(0)}' in main
assert 'if(page==0)' in main
assert 'scope.launch {pager.animateScrollToPage(1)}' in main
background=files["background/BackgroundSelectionActivity.kt"]
assert 'cityPreview' not in background
assert 'selectPixelCityHome()' not in background
art=files["composable/pixel/VbPixelArt.kt"]
assert "rect(c,79f,62f,280f,314f,NAVY)" in art
assert "val y=114f+i*35f" in art
assert 'footer.take(24)' in art
for x in (79,359):
    for y in (62,376):
        assert math.hypot(x-219,y-219)<219
assert 114 + 6*35 + 32 < 362
assert "onSelect(SendOrReceive.SEND)" in files["transfer/TransferScreen.kt"]
assert "onSelect(SendOrReceive.RECEIVE)" in files["transfer/TransferScreen.kt"]
assert "saveService.saveAsync()" in files["main/MainScreenControllerImpl.kt"]
for name in ("app","companion"):
    gradle=(root/name/"build.gradle.kts").read_text(encoding="utf-8")
    assert 'versionName = "0.6.7-VB-Classic"' in gradle
    assert "versionCode = 67" in gradle
print("PASS: round safe area, legibility geometry, native Home backgrounds")
print("PASS: combined Status/Digimon pager; no dead Items; Sleep/Wake; game save and NFC routes")
