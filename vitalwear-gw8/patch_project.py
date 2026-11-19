#!/usr/bin/env python3
"""Experimental Galaxy Watch 8 integration patch for the 457R0 VitalWear fork.
Keeps the official VBHelper app untouched. Produces distinct test package ids.
Requires legitimately obtained card/firmware files; does not distribute Bandai assets.
"""
from pathlib import Path
import wave, math, struct, sys
root = Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear").resolve()
def edit(path, before, after, expected=1):
    p = root / path
    s = p.read_text(encoding="utf-8-sig")
    n = s.count(before)
    if n != expected:
        raise RuntimeError(f"{path}: expected {expected} match, found {n} for {before!r}")
    p.write_text(s.replace(before, after), encoding="utf-8")
    print("PATCH:", path, before[:70])

# Keep phone/watch in their own test namespace so this experiment never overwrites
# the already functioning old VitalWear pair or the official VBHelper APK.
new_id = 'applicationId = "dev.mikeki.vitalwear.gw8"'
edit("app/build.gradle.kts", 'applicationId = "com.github.nacabaro.vbhelper"', new_id)
edit("companion/build.gradle.kts", 'applicationId = "com.github.cfogrady.vitalwear"', new_id)
edit("companion/build.gradle.kts", 'applicationIdSuffix = ".debug"', '// Both variants use same Data Layer package identifier')

# Import locally supplied DIM/BEM files directly. A physical VB is not queried.
# The watch still requires a separately supplied compatible 1.0.B firmware.
p = root / "companion/src/main/java/com/github/cfogrady/vitalwear/card/ImportCardActivity.kt"
s = p.read_text(encoding="utf-8-sig")
before = """            if (validatedCardManager.isValidatedCard(card.header.dimId)) {
                importState.value = ImportState.ImportCard
            } else {
                importState.value = ImportState.UnlockCard
            }"""
if s.count(before)!=1: raise RuntimeError("Unexpected ImportCardActivity flow; refusing patch")
p.write_text(s.replace(before, """            // No physical VB required for local card import.
            // Original file bytes still go through DimReader verification.
            importState.value = ImportState.ImportCard"""),encoding="utf-8")
print("PATCH: local card import")

# Copy-first on NFC COMMIT: do not remove current character from watch.
p = root / "app/src/main/java/com/github/cfogrady/vitalwear/transfer/hce/VitalWearHostApduService.kt"
s=p.read_text(encoding="utf-8-sig")
before = """                serviceScope.launch {
                    runCatching {
                        repository.deleteCurrentCharacterAfterSuccessfulSend()
                    }.onFailure {
                        Log.w(TAG, "Post-send delete failed after COMMIT", it)
                    }
                }"""
if s.count(before)!=1: raise RuntimeError("Unexpected NFC transfer code; refusing patch")
p.write_text(s.replace(before, """                // Safe test mode: copy to receiving app, keep original on watch.
                Log.i(TAG, "SAFE NFC COPY committed; source character preserved")"""),encoding="utf-8")
print("PATCH: NFC copy-first, no delete")

# Do not attempt signature-protected VBHelper CardImportProvider writes.
p=root/"companion/src/main/java/com/github/cfogrady/vitalwear/card/ImportCardActivity.kt"
s=p.read_text(encoding="utf-8")
before="VBHelperCardSync.syncCard(applicationContext, cardPayload, cardName.value)"
if s.count(before)==1:
    p.write_text(s.replace(before, '// VBHelper database untouched; NFC communication remains separate.'),encoding="utf-8")
    print("PATCH: avoid VBHelper shared database writes")

# Original synthesized SFX. No sound or graphics owned by Bandai.
raw=root/"app/src/main/res/raw"
raw.mkdir(parents=True,exist_ok=True)
sounds={
 "digivice_menu":[(590,.08),(750,.08)],
 "digivice_battle":[(330,.11),(460,.11),(660,.12)],
 "digivice_train":[(510,.07),(690,.08),(510,.07)],
 "digivice_evolve":[(370,.15),(490,.16),(620,.16),(880,.25)],
 "digivice_win":[(660,.12),(880,.12),(990,.20)],
 "digivice_hit":[(290,.12),(180,.14)],
}
RATE=16000
for name, notes in sounds.items():
    samples=[]
    for freq,duration in notes:
        n=int(duration*RATE)
        for i in range(n):
            envelope=min(1.0, i/(0.012*RATE))*max(0,(n-i)/(0.06*RATE))
            phase=2*math.pi*freq*i/RATE
            val=(math.sin(phase)+0.18*math.sin(2*phase))*0.22*envelope
            samples.append(int(max(-1,min(1,val))*32767))
        samples.extend([0]*int(RATE*.025))
    with wave.open(str(raw/(name+".wav")),"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes(struct.pack("<"+"h"*len(samples),*samples))
print("SFX assets:",len(sounds))

sound_kt = r'''package com.github.cfogrady.vitalwear.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.github.cfogrady.vitalwear.R
import java.util.Calendar

/** Small event SFX on the WATCH game only; never used by the watch-face. */
object PetSfx {
    private var pool: SoundPool? = null
    private var loaded = mutableSetOf<Int>()
    private var sampleIds = mutableMapOf<Int,Int>()
    private val rawRes = listOf(
        R.raw.digivice_menu, R.raw.digivice_battle,
        R.raw.digivice_train, R.raw.digivice_evolve,
        R.raw.digivice_win, R.raw.digivice_hit
    )
    @Synchronized fun preload(context: Context) {
        if (pool != null) return
        val newPool = SoundPool.Builder().setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            .build()
        newPool.setOnLoadCompleteListener { _, soundId, status ->
            if (status == 0) synchronized(this) { loaded.add(soundId) }
        }
        for (res in rawRes) sampleIds[res] = newPool.load(context.applicationContext,res,1)
        pool = newPool
    }
    @Synchronized fun play(context: Context, res: Int) {
        val prefs=context.getSharedPreferences("digivice_sfx", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return
        val hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (prefs.getBoolean("quiet_hours",true) && (hour>=22 || hour<7)) return
        preload(context)
        val id=sampleIds[res] ?: return
        if (loaded.contains(id)) {
            val volume=prefs.getFloat("volume",0.35f).coerceIn(0f,1f)
            pool?.play(id, volume,volume,1,0,1f)
        }
    }
}
'''
p=root/"app/src/main/java/com/github/cfogrady/vitalwear/sound/PetSfx.kt"
p.parent.mkdir(parents=True,exist_ok=True);p.write_text(sound_kt,encoding="utf-8")

# Play on entering menu/battle/training/evolution. Result SFX for direct battles.
hooks={
 "app/src/main/java/com/github/cfogrady/vitalwear/main/MainActivity.kt":"R.raw.digivice_menu",
 "app/src/main/java/com/github/cfogrady/vitalwear/battle/BattleActivity.kt":"R.raw.digivice_battle",
 "app/src/main/java/com/github/cfogrady/vitalwear/training/TrainingActivity.kt":"R.raw.digivice_train",
 "app/src/main/java/com/github/cfogrady/vitalwear/character/transformation/TransformationActivity.kt":"R.raw.digivice_evolve"
}
for rel,res in hooks.items():
    p=root/rel
    s=p.read_text(encoding="utf-8-sig")
    place="        super.onCreate(savedInstanceState)"
    assert s.count(place)==1,rel
    s=s.replace(place,place+"\n        PetSfx.play(this, "+res+")")
    s=s.replace("import android.os.Bundle", "import android.os.Bundle\nimport com.github.cfogrady.vitalwear.R\nimport com.github.cfogrady.vitalwear.sound.PetSfx")
    if rel.endswith("BattleActivity.kt"):
        before="""                FightTarget(battleCharacterInfo) {
                    val intent = Intent()"""
        after="""                FightTarget(battleCharacterInfo) {
                    PetSfx.play(this, if (it == BattleResult.WIN) R.raw.digivice_win else R.raw.digivice_hit)
                    val intent = Intent()"""
        if before not in s: raise RuntimeError("Missing battle result hook")
        s=s.replace(before,after)
    p.write_text(s,encoding="utf-8")
    print("PATCH: SFX hook",rel)

print("READY: experimental source patch applied; firmware remains separately required")
