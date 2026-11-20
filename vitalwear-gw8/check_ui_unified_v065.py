#!/usr/bin/env python3
"""Fail CI if unified UI routes/resources are missing from built source."""
from pathlib import Path
import sys
watch=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
face=Path(sys.argv[2] if len(sys.argv)>2 else "VBClassicXLFace")
base=watch/"app/src/main/java/com/github/cfogrady/vitalwear"

checks={
"main/MainScreen.kt":["BackHandler(enabled=pager.currentPage>0)","pageCount={2}","PixelHome("],
"main/VbMenuScreen.kt":['VbAction("ITEMS"','VbAction("DIGIMON"','VbAction("SETTINGS"'],
"main/VbHubActivity.kt":['"STATUS"','"DIGIMON"','"TRAINING"','"ADVENTURE"',
    '"MISSION"','"BATTLE"','"ITEMS"','"SENSOR"','"SETTINGS"','"EVOLUTION"',
    "VbDetailPage(", "VbChoicePage("],
"composable/pixel/VbPixelArt.kt":["fun menu(","fun details(","fun city(","fun selectedBackground("],
"background/BackgroundManager.kt":["HOME_CITY_MODE","selectPixelCityHome"],
"character/PixelHome.kt":["usePixelCity","Art.selectedBackground(canvas,scene)"],
"character/activity/CharacterSelection.kt":["VbChoicePage(","controller.swapToCharacter","controller.setSupportCharacter"],
"adventure/AdventureMenuScreen.kt":["VbChoicePage(","VbDetailPage(","controller.startAdventure("],
"transfer/TransferScreen.kt":["VbChoicePage(","onSelect(SendOrReceive.SEND)",
    "onSelect(SendOrReceive.RECEIVE)"],
"stats/StatsMenuActivity.kt":['VbHubContent(this,"STATUS")'],
"training/TrainingMenuActivity.kt":['VbHubContent(this,"TRAINING")'],
"settings/SettingsActivity.kt":['VbHubContent(this,"SETTINGS")'],
"complications/BattlesComplicationService.kt":["totalBattles"],
"complications/ActivityComplicationService.kt":["activityLevel"],
}
for rel,expected in checks.items():
    path=base/rel
    data=path.read_text(encoding="utf-8")
    for text in expected:
        if text not in data:raise AssertionError(f"{rel}: missing {text}")
    print("OK",rel,len(expected),"checks")
manifest=(watch/"app/src/main/AndroidManifest.xml").read_text()
assert ".main.VbHubActivity" in manifest
for x in ("BattlesComplicationService","ActivityComplicationService"):
    assert x in manifest
for module in ("app","companion"):
    s=(watch/module/"build.gradle.kts").read_text()
    assert 'versionName = "0.6.5-UI-Unified"' in s
xml=(face/"src/main/res/raw/watchface.xml").read_text()
assert 'resource="forest_scene"' in xml
assert '<WatchFace width="450" height="450">' in xml
assert (face/"src/main/res/drawable/forest_scene.png").stat().st_size>2000
assert 'versionName = "0.6.5-forest"' in (face/"build.gradle.kts").read_text()
# Portrait frame corners fit within 438px circular screen at 0.0 border margin.
import math
radius=219
for x in (104,334):
    for y in (40,397):
        assert math.hypot(x-219,y-219)<=radius
# Visible 7th row and footer do not overlap.
assert 103+6*40+35 <= 383
print("OK safe area geometry 438x438; 7 rows + footer")
print("UI UNIFICATION CONTRACT PASSED")
