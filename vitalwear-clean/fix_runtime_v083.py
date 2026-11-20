#!/usr/bin/env python3
"""v0.8.3: fix the actual Wear OS launch crash from AndroidRuntime logs.

2026-11-20 emulator: NoClassDefFoundError org/slf4j/LoggerFactory
in UnorderedSpriteReader -> BemSpriteReader -> FirmwareManager -> VitalWearApp.
The Java8 VB-DIM-Reader is attached as a local jar, so its Maven transitive
dependencies are NOT automatically added by Gradle. Add SLF4J API to runtime
for both watch and phone (phone imports using DimReader on demand).
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
for module in ("app", "companion"):
    p=root/module/"build.gradle.kts"
    src=p.read_text(encoding="utf-8")
    old='compileOnly(files("libs/VB-DIM-Reader.jar"))'
    new='''compileOnly(files("libs/VB-DIM-Reader.jar"))
    // The original java8 DIM jar calls org.slf4j.LoggerFactory in its static
    // initializer. Missing runtime dependency crashed the Watch immediately.
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("at.favre.lib:bytes:1.5.0")
    implementation("commons-io:commons-io:2.11.0")'''
    if src.count(old)!=1: raise RuntimeError(f"{module}: missing DIM reader compileOnly")
    src=src.replace(old,new)
    for a,b in [("versionCode = 82","versionCode = 83"),
                ('versionName = "0.8.2-Upstream-Safe"',
                 'versionName = "0.8.3-Upstream-RuntimeFix"')]:
        if src.count(a)!=1:raise RuntimeError(f"{module}: unexpected version {a}")
        src=src.replace(a,b)
    if 'applicationId = "dev.mikeki.vitalwear.gw8.clean"' not in src:
        raise RuntimeError(f"{module}: clean ID is not preserved")
    p.write_text(src,encoding="utf-8")
    print("FIXED SLF4J runtime:",module)
print("Build now requires a real Watch emulator cold-launch test.")
