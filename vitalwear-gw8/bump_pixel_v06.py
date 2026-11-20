#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
for module in ("app","companion"):
    p=r/module/"build.gradle.kts"
    s=p.read_text(encoding="utf-8-sig")
    if s.count("versionCode = 56")!=1:raise RuntimeError("version code changed for "+module)
    if s.count('versionName = "0.5.6-NFCBattle"')!=1:raise RuntimeError("version name changed for "+module)
    p.write_text(s.replace("versionCode = 56","versionCode = 61").replace(
        'versionName = "0.5.6-NFCBattle"','versionName = "0.6.1-Compact"'),encoding="utf-8")
print("Game and phone version 0.6.1 / versionCode 61")
