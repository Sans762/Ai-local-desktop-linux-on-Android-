#!/usr/bin/env python3
"""Non-emulator regression gates for full-fidelity VBHelper SQLite export path."""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
app=root/"app/src/main/java/com/github/cfogrady/vitalwear"
phone=root/"companion/src/main/java/com/github/cfogrady/vitalwear"
src=(phone/"main/VbHelperBackupImportActivity.kt").read_text()
required=[
    'SQLiteDatabase.OPEN_READONLY',
    'ActivityResultContracts.OpenDocument()',
    'SQLite format 3\\u0000',
    'FROM UserCharacter u',
    'JOIN CardCharacter c',
    'JOIN Card d',
    'JOIN Sprite s',
    '.setTransferCard(metadata)',
    '.setTransferSpecies(species)',
    'setIdle1(idle)',
    'setIdle2(frame("spriteIdle2"))',
    'setWalk1(frame("spriteWalk1"))',
    'setWalk2(frame("spriteWalk2"))',
    'setRun1(frame("spriteRun1"))',
    'setRun2(frame("spriteRun2"))',
    'setTrain1(frame("spriteTrain1"))',
    'setTrain2(frame("spriteTrain2"))',
    'setWin(frame("spriteHappy"))',
    'setDown(frame("spriteSleep"))',
    'setAttack(frame("spriteAttack"))',
    'setDodge(frame("spriteDodge"))',
    'vitalwear/VBHELPER_FULL_CHARACTER',
    'vitalwear/VBHELPER_FULL_RESULT',
    'receipt.await()',
    'require(data.size == w * h * 2)',
    'status.text = if (result)',
]
for needle in required:
    assert needle in src, "Missing VBHelper full-sprite protocol: "+needle
wearreceiver=(app/"communication/PhoneCommunicationService.kt").read_text()
for needle in [
    'vitalwear/VBHELPER_FULL_CHARACTER',
    'vitalwear/VBHELPER_FULL_RESULT',
    'VitalWearHceTransferRepository(app).importCharacter(bytes)',
    'if (success) 1 else 0'
]:
    assert needle in wearreceiver, needle
watchmeta=(app/"transfer/TransferEmbeddedMetadata.kt").read_text()
assert 'if (!hasTransferCard() || !hasTransferSpecies()) return null' in watchmeta
assert 'speciesEntityDao.update(importedSpecies)' in watchmeta
sanitize=(app/"transfer/TransferActivity.kt").read_text()
assert 'builder.setTransferSpecies(this.transferSpecies)' in sanitize
assert 'builder.setTransferCard(this.transferCard)' in sanitize
for module in ("app","companion"):
    g=(root/module/"build.gradle.kts").read_text()
    assert 'versionCode = 88' in g
    assert 'applicationId = "dev.mikeki.vitalwear.gw8.stable"' in g
    assert 'signingConfig = signingConfigs.getByName("vitalWearStable")' in g
print("PASS full sprites, read-only VBHelper database, status acknowledgement, no fake art, stable appId/signature")
