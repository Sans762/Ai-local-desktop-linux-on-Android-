#!/usr/bin/env python3
"""0.8.1 hotfix for both apps closing on launch after 0.6.x -> 0.8.0 update.

Root issue: previously installed 457R0 watch SQLite schema=8, phone schema=4.
0.8.0 built against cfogrady originals schema 5 and 3: Room downgrade
without migration. Restore legacy DB formats *without clearing any user data*.
Engine, menus, home, card import UI all remain original cfogrady.
"""
from pathlib import Path
import re
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
ref=Path(sys.argv[2] if len(sys.argv)>2 else "VitalWearForkSchema")
wroot="app/src/main/java/com/github/cfogrady/vitalwear"
proot="companion/src/main/java/com/github/cfogrady/vitalwear"

def replace(path,old,new,n=1):
    p=root/path
    s=p.read_text(encoding="utf-8-sig")
    found=s.count(old)
    if found!=n:
        raise ValueError(f"{path}: expected {n} matches, got {found}: {old[:100]}")
    p.write_text(s.replace(old,new),encoding="utf-8")
    print("MODIFIED",path)

def copy_fork_schema(rel):
    original=ref/rel
    assert original.is_file(),original
    target=root/rel
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(original.read_bytes())
    print("RESTORED DATABASE ONLY",rel)

# WATCH: exact old SQLite Room schema. All non-DB code still original cfogrady.
copy_fork_schema(f"{wroot}/data/AppDatabase.kt")
copy_fork_schema(f"{wroot}/character/data/CharacterEntity.kt")
for i in (6,7,8):
    copy_fork_schema(
        f"app/schemas/com.github.cfogrady.vitalwear.data.AppDatabase/{i}.json"
    )
# New upstream constructors pass only the original 23 parameters.
# Defaults for extra *existing* database columns allow them to compile without
# dropping 0.6.x character history and stats.
p=root/f"{wroot}/character/data/CharacterEntity.kt"
s=p.read_text(encoding="utf-8")
matched=re.findall(r'@ColumnInfo\(name = "[^"]+", defaultValue = "0"\) var \w+: Int,',s)
if len(matched)!=22:
    raise RuntimeError(f"Expected 22 extra persisted Digimon fields, found {len(matched)}")
for field in matched:
    s=s.replace(field,field[:-1]+" = 0,")
p.write_text(s,encoding="utf-8")

# PHONE: exact previous database schema v4, but the direct import path doesn't
# require accessing it, so leave it untouched rather than downgrading or
# attempting to rebuild a removed card catalog.
copy_fork_schema(f"{proot}/data/AppDatabase.kt")
copy_fork_schema(
    "companion/schemas/com.github.cfogrady.vitalwear.data.AppDatabase/4.json")
p=root/f"{proot}/VitalWearCompanion.kt"
s=p.read_text(encoding="utf-8-sig")
if s.count('        buildDependencies()')!=1:
    raise RuntimeError("Phone DI initialization no longer matches upstream")
s=s.replace('        buildDependencies()','        // Direct card file import does not need to open the old card database.')
start=s.index('    private fun buildDependencies()')
s=s[:start] + "}\n"
p.write_text(s,encoding="utf-8")
p=root/f"{proot}/card/ImportCardActivity.kt"
s=p.read_text(encoding="utf-8-sig")
for line in (
    '        cardLoader = (applicationContext as VitalWearCompanion).cardLoader\n',
    '        val cardMetaEntityDao = (applicationContext as VitalWearCompanion).cardMetaEntityDao\n',
):
    if s.count(line)!=1:raise RuntimeError("ImportCard startup changed: "+line)
    s=s.replace(line,"")
block='''            LaunchedEffect(true) {
                withContext(Dispatchers.IO) {
                    cardsLoaded = cardMetaEntityDao.getAll().map{it.cardName}.toSet()
                }
            }
'''
if s.count(block)!=1:raise RuntimeError("Card catalog lookup changed")
s=s.replace(block,"")
block='''                    if(cardsLoaded.contains(name)) {
                        Toast.makeText(applicationContext, "Card named \\"$name\\" has already been imported", Toast.LENGTH_SHORT).show()
                    } else {
                        importState.value = ImportState.LoadFile
                    }'''
if s.count(block)!=1:raise RuntimeError("Import duplicate-block changed")
s=s.replace(block,'''                    // Local DIM/BEM file import; no physical VB or legacy DB.
                    importState.value = ImportState.LoadFile''')
p.write_text(s,encoding="utf-8")

# Robustness: if a background index saved by the old UI is no longer valid in
# the selected firmware or card, keep app alive and fall back to first
# firmware background. Do not modify the user's preference or game data.
p=root/f"{wroot}/background/BackgroundManager.kt"
s=p.read_text(encoding="utf-8-sig")
old='''            backgroundSetter.invoke(firmware.backgrounds[backgroundIdx])'''
new='''            val backgrounds=firmware.backgrounds
            if(backgrounds.isNotEmpty()) {
                backgroundSetter.invoke(backgrounds.getOrElse(backgroundIdx){backgrounds[0]})
            }'''
if s.count(old)!=1:raise RuntimeError("firmware background access changed")
s=s.replace(old,new)
old='''                backgroundSetter.invoke(backgrounds[backgroundIdx])'''
new='''                val chosen=backgrounds.getOrNull(backgroundIdx)
                    ?:firmware.backgrounds.firstOrNull()
                if(chosen!=null) backgroundSetter.invoke(chosen)'''
if s.count(old)!=1:raise RuntimeError("card background access changed")
s=s.replace(old,new)
p.write_text(s,encoding="utf-8")
# Bump real APK version so Android can upgrade without wiping data.
for module in ("app","companion"):
    replace(f"{module}/build.gradle.kts",
            'versionCode = 80','versionCode = 81')
    replace(f"{module}/build.gradle.kts",
            'versionName = "0.8.0-Upstream"',
            'versionName = "0.8.1-Upstream-DBFix"')

print("PASS: previous Room v8 watch/v4 phone data preserved, local card file startup decoupled")
