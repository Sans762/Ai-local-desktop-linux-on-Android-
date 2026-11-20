#!/usr/bin/env python3
"""0.8.2: installable *alongside* old build, never erase a character save.

The old 0.6.7 APK was signed with an ephemeral debug key whose private key
does not exist in Actions cache. Android will refuse an in-place install with
a different signature; it is NOT safe to advise uninstalling the game.
Use one clean application ID for both new Watch & Phone to restore Wear Data Layer.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
for module in ("app","companion"):
    p=root/module/"build.gradle.kts"
    s=p.read_text()
    one='applicationId = "dev.mikeki.vitalwear.gw8"'
    assert s.count(one)==1,(module,"unexpected original applicationId")
    s=s.replace(one,'applicationId = "dev.mikeki.vitalwear.gw8.clean"')
    assert s.count('versionCode = 81')==1
    assert s.count('versionName = "0.8.1-Upstream-DBFix"')==1
    s=s.replace('versionCode = 81','versionCode = 82')
    s=s.replace('versionName = "0.8.1-Upstream-DBFix"',
                'versionName = "0.8.2-Upstream-Safe"')
    if module=="app":
        assert 'applicationIdSuffix = ".debug"' in s
        s=s.replace('applicationIdSuffix = ".debug"',
                   '// No debug suffix; phone and watch must have the same Data Layer appId')
    p.write_text(s)
    print("MATCHED PAIRED APP ID:",module)
print("0.8.2 SIDE BY SIDE SAFE INSTALL. PREVIOUS APP DATA UNTOUCHED.")
