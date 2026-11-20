#!/usr/bin/env python3
"""Contract checks for v0.8.4 NFC/VBHelper port before building an APK."""
from pathlib import Path
import sys,xml.etree.ElementTree as ET
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
src=root/"app/src/main/java/com/github/cfogrady/vitalwear"
def verify(rel,patterns):
    s=(src/rel).read_text()
    for p in patterns:assert p in s,(rel,p)
    print("PASS",rel)
verify("transfer/hce/VitalWearHceProtocol.kt",
    ['INS_NEGOTIATE','INS_READ_CHUNK','INS_WRITE_CHUNK','INS_COMMIT','INS_STATUS',
     'INS_SYNC_UI','INS_VIBRATE','AID','MODE_WATCH_TO_PHONE','MODE_PHONE_TO_WATCH'])
verify("transfer/hce/VitalWearHostApduService.kt",
    ['HostApduService','processCommandApdu','handleNegotiate','handleReadChunk',
     'handleWriteChunk','handleCommit','handleStatus','VitalWearHceSessionManager'])
verify("transfer/hce/VitalWearHceSessionManager.kt",
    ['armSend','armReceive','markSuccess','markFailure','readChunk','writeChunk'])
verify("transfer/hce/VitalWearHceTransferRepository.kt",
    ['getActiveCharacterPayload','importCharacter','resolveImportedCardMetaOrPlaceholder',
     'sanitizeForImport','toCharacterEntity','maxAdventureCompletedByCardMap'])
verify("transfer/TransferActivity.kt",
    ['receiveCharacter','getActiveCharacterProto','getLastReceivedCharacterSprites',
     'setTransferCard','buildTransferSpeciesMetadata','deleteActiveCharacter'])
verify("transfer/TransferScreen.kt",
    ['R.string.transfer_button_send_to_vbh',
     'R.string.transfer_button_receive_from_vbh','TransferState.ENTRY'])
verify("transfer/TransferEmbeddedMetadata.kt",
    ['resolveImportedCardMetaOrPlaceholder','hasTransferSpecies',
     'ensureTransferSpeciesExists','saveTransferSprite'])
proto=(root/"protos/src/main/proto/com/github/cfogrady/vitalwear/protos/Character.proto").read_text()
assert "message TransferCard" in proto
assert "message TransferSpecies" in proto
assert "optional TransferCard transfer_card = 7;" in proto
assert "optional TransferSpecies transfer_species = 8;" in proto
assert "int32 firmware_major_version = 38;" in proto
manifest=(root/"app/src/main/AndroidManifest.xml").read_text()
ET.fromstring(manifest)
assert "android.permission.NFC" in manifest
assert "android.hardware.nfc.hce" in manifest
assert ".transfer.hce.VitalWearHostApduService" in manifest
assert "android.permission.BIND_NFC_SERVICE" in manifest
aid=root/"app/src/main/res/xml/vitalwear_hce_service.xml"
assert "F0564954414C57454152" in aid.read_text()
ET.fromstring(aid.read_text())
ui=(root/"app/src/main/java/com/github/cfogrady/vitalwear/main/MainScreen.kt").read_text()
main=(src/"main/MainActivity.kt").read_text()
assert "CardEmulation.getInstance(adapter).setPreferredService" in main
assert "CardEmulation.getInstance(adapter).unsetPreferredService(this)" in main
assert "VerticalPager" in ui
assert "VbMenuScreen" not in ui
assert "VbPixelArt" not in ui
for mod in ("app","companion"):
    g=(root/mod/"build.gradle.kts").read_text()
    assert ('versionName = "0.8.4-Clean-VBHelper-NFC"' in g or
            'versionName = "0.8.5-Clean-VBHelper-SafeExport"' in g or
            'versionName = "0.8.6-Clean-VBHelper-ReceiveFix"' in g)
    assert ('versionCode = 84' in g or 'versionCode = 85' in g or 'versionCode = 86' in g)
    assert 'applicationId = "dev.mikeki.vitalwear.gw8.clean"' in g
assert "sharedTransferSeenDao" not in (src/"transfer/hce/VitalWearHceTransferRepository.kt").read_text()
assert "sharedTransferSeenDao" not in (src/"transfer/TransferActivity.kt").read_text()
print("OK VB HELPER HCE PORT CONTRACT, app data untouched")
