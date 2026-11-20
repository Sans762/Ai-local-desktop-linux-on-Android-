#!/usr/bin/env python3
"""v0.8.7: no more INSTALL_FAILED_UPDATE_INCOMPATIBLE.

0.8.5 and 0.8.6 were accidentally signed by different ephemeral Gradle
debug keys despite a stable cached key existing on the CI runner.
Android will not overwrite a previously installed APK with a different
certificate, and deleting the old app can destroy Digimon saves.

Switch the paired Wear + phone package ID once and explicitly sign both
using the fixed, cached key. Old app/data remains untouched. Future releases
must keep the same applicationId AND certificate.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
old_id="dev.mikeki.vitalwear.gw8.clean"
stable_id="dev.mikeki.vitalwear.gw8.stable"
for module in ("app","companion"):
    gradle=root/module/"build.gradle.kts"
    s=gradle.read_text()
    for old in (
        f'applicationId = "{old_id}"',
        'versionCode = 86',
        'versionName = "0.8.6-Clean-VBHelper-ReceiveFix"',
        '    buildTypes {\n',
        '        debug {\n',
    ):
        assert s.count(old)==1,(module,old,s.count(old))
    s=s.replace(f'applicationId = "{old_id}"',
                f'applicationId = "{stable_id}"')
    s=s.replace('versionCode = 86','versionCode = 87')
    s=s.replace('versionName = "0.8.6-Clean-VBHelper-ReceiveFix"',
                'versionName = "0.8.7-InstallAndNfcFix"')
    s=s.replace('    buildTypes {\n',
'''    // Explicit signing: never let Gradle generate a different temporary key.
    signingConfigs {
        create("vitalWearStable") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
''')
    s=s.replace('        debug {\n',
'''        debug {
            signingConfig = signingConfigs.getByName("vitalWearStable")
''')
    gradle.write_text(s)
    # The Android debug-source label overrides the release source label.
    label=root/module/"src/debug/res/values/strings.xml"
    if label.exists():
        v=label.read_text()
        assert "<string name=\"app_name\">" in v
        import re
        v=re.sub(r'<string name="app_name">[^<]+</string>',
                 ('<string name="app_name">VitalWear GW8</string>' if module=="app"
                  else '<string name="app_name">VitalWear GW8 Companion</string>'),v)
        label.write_text(v)
    print("FIXED INSTALL:",module,stable_id,"versionCode=87")
