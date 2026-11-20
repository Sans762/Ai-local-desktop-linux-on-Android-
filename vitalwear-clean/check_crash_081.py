#!/usr/bin/env python3
"""Static regression tests for a *non-destructive* 0.6.7 -> 0.8.1 update."""
from pathlib import Path
import sys,json,re
r=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
fork=Path(sys.argv[2] if len(sys.argv)>2 else "VitalWearForkSchema")
watch=r/"app/src/main/java/com/github/cfogrady/vitalwear"
phone=r/"companion/src/main/java/com/github/cfogrady/vitalwear"
wdb=(watch/"data/AppDatabase.kt").read_text()
pdb=(phone/"data/AppDatabase.kt").read_text()
assert "version = 8" in wdb
assert "version = 4" in pdb
wentity=(watch/"character/data/CharacterEntity.kt").read_text()
for f in ["age_in_days","activity_level","heart_rate_current","total_trophies",
          "ability_type","item_remaining_time","firmware_major_version"]:
    assert f'@ColumnInfo(name = "{f}"' in wentity
assert wentity.count('defaultValue = "0"')==22
startup=(phone/"VitalWearCompanion.kt").read_text()
assert "        buildDependencies()" not in startup
assert "private fun buildDependencies()" not in startup
importer=(phone/"card/ImportCardActivity.kt").read_text()
assert "cardMetaEntityDao.getAll()" not in importer
assert "cardsLoaded.contains(name)" not in importer
assert "validatedCardManager.isValidatedCard" not in importer
assert "check(nodes.isNotEmpty())" in importer
assert "ImportState.Error" in importer
for m in ("app","companion"):
    g=(r/m/"build.gradle.kts").read_text()
    assert ('versionName = "0.8.1-Upstream-DBFix"' in g or
            'versionName = "0.8.2-Upstream-Safe"' in g or
            'versionName = "0.8.3-Upstream-RuntimeFix"' in g or
            'versionName = "0.8.4-Clean-VBHelper-NFC"' in g)
    assert any(f'versionCode = {n}' in g for n in (81,82,83,84))
    if 'versionCode = 83' in g:
        assert 'implementation("org.slf4j:slf4j-api:1.7.36")' in g
for module,version in [("app",8),("companion",4)]:
    relative=f"{module}/schemas/com.github.cfogrady.vitalwear.data.AppDatabase/{version}.json"
    j=json.loads((r/relative).read_text())
    old=json.loads((fork/relative).read_text())
    assert j["database"]["identityHash"]==old["database"]["identityHash"]
    assert j["database"]["version"]==version
    print("DB IDENTICAL:",module,version,j["database"]["identityHash"])
assert "getOrElse(backgroundIdx)" in (watch/"background/BackgroundManager.kt").read_text()
assert "TYPE_HEART_RATE" in (watch/"heartrate/SingleHeartRateSensorListener.kt").read_text()
assert "TYPE_STEP_COUNTER" in (watch/"steps/StepSensorService.kt").read_text()
print("PASS: phone will not open legacy card DB, watch original data retained, no destructive migration")
