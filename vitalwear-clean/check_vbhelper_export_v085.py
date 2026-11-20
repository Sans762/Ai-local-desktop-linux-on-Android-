#!/usr/bin/env python3
"""v0.8.5 review guard: no character loss + file-based VBHelper export path."""
from pathlib import Path
import sys
r=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
app=r/"app/src/main/java/com/github/cfogrady/vitalwear"
phone=r/"companion/src/main/java/com/github/cfogrady/vitalwear"
hce=(app/"transfer/hce/VitalWearHostApduService.kt").read_text()
a=hce.index("VitalWearHceSessionManager.Mode.SEND_TO_PHONE -> {")
b=hce.index("VitalWearHceSessionManager.Mode.RECEIVE_FROM_PHONE -> {",a)
send=hce[a:b]
assert "deleteCurrentCharacterAfterSuccessfulSend()" not in send
assert "NFC export acknowledged; retaining local Digimon backup" in send
screen=(app/"transfer/TransferScreen.kt").read_text()
assert 'Text("Copy to VBH via phone")' in screen
assert "controller.sendCopyToVBHelperPhone()" in screen
controller=(app/"transfer/TransferActivity.kt").read_text()
assert "override fun sendCopyToVBHelperPhone()" in controller
assert "getActiveCharacterProto()" in controller
assert 'openChannel(' in controller
assert '"vitalwear/VBHELPER_EXPORT"' in controller
assert "character.writeTo(stream)" in controller
assert "deleteActiveCharacter()" not in controller[controller.index("override fun sendCopyToVBHelperPhone()"):controller.index("//------------------------------- Controller Members",controller.index("override fun sendCopyToVBHelperPhone()"))]
receiver=(phone/"communication/WatchCommunicationService.kt").read_text()
assert "Character.parseFrom(bytes)" in receiver
assert 'File(filesDir,"vbhelper_export")' in receiver
assert 'File(dir,"pending.vitalwear")' in receiver
assert '"vitalwear/VBHELPER_EXPORT"' in receiver
assert "4*1024*1024" in receiver
main=(phone/"main/MainActivity.kt").read_text()
assert 'setPackage("com.github.nacabaro.vbhelper")' in main
assert 'type="application/x-vitalwear-character"' in main
assert 'Intent.ACTION_SEND' in main
assert 'Intent.FLAG_GRANT_READ_URI_PERMISSION' in main
assert 'Text(text="Send Digimon copy to VB Helper"' in main
provider=(r/"companion/src/main/res/xml/provider_paths.xml").read_text()
assert 'name="vbhelper-export" path="vbhelper_export/"' in provider
gradle=(r/"companion/build.gradle.kts").read_text()
assert 'implementation(project(":protos"))' in gradle
assert 'implementation(libs.protobuf.javalite)' in gradle
for mod in ("app","companion"):
    src=(r/mod/"build.gradle.kts").read_text()
    assert 'versionCode = 85' in src
    assert 'versionName = "0.8.5-Clean-VBHelper-SafeExport"' in src
    assert 'applicationId = "dev.mikeki.vitalwear.gw8.clean"' in src
print("PASS v085: HCE nondestructive, watch-pairing channel, phone share, protobuf and ID")
