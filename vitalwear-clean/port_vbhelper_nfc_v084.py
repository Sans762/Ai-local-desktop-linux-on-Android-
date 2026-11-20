#!/usr/bin/env python3
"""v0.8.4: Restore VB Helper NFC HCE protocol in cfogrady/VitalWear Clean.

The content source for these transfer-only files is 457R0/VitalWear, cloned
by CI as VitalWearForkSchema. Keep upstream Home, menu, original backgrounds,
training, gameplay, heartbeat, step counter, databases, and phone UI.

The HCE service accepts the existing VBHelper ISO-DEP AID, reads/writes
protobuf chunks and restores embedded card/species metadata when needed.
It is a feature port, NOT a release-test substitute for real Watch8 + VBHelper.
"""
from pathlib import Path
import sys,re,xml.etree.ElementTree as ET
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
fork=Path(sys.argv[2] if len(sys.argv)>2 else "VitalWearForkSchema")
app="app/src/main/java/com/github/cfogrady/vitalwear"
files=[
    "protos/src/main/proto/com/github/cfogrady/vitalwear/protos/Character.proto",
    f"{app}/transfer/TransferActivity.kt",
    f"{app}/transfer/TransferScreen.kt",
    f"{app}/transfer/TransferEmbeddedMetadata.kt",
    f"{app}/transfer/TransferHaptics.kt",
    f"{app}/transfer/hce/VitalWearHceProtocol.kt",
    f"{app}/transfer/hce/VitalWearHceSession.kt",
    f"{app}/transfer/hce/VitalWearHceSessionManager.kt",
    f"{app}/transfer/hce/VitalWearHceTransferRepository.kt",
    f"{app}/transfer/hce/VitalWearHostApduService.kt",
    "app/src/main/res/xml/vitalwear_hce_service.xml",
    "app/src/test/java/com/github/cfogrady/vitalwear/transfer/TransferImportResolutionTest.kt",
]
for rel in files:
    path=fork/rel
    if not path.is_file(): raise RuntimeError(f"Fork source missing: {rel}")
    target=root/rel
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(path.read_bytes())
    print("PORT",rel)

def edit(rel,old,new,n=1):
    p=root/rel
    s=p.read_text(encoding="utf-8-sig")
    if s.count(old)!=n:raise RuntimeError(f"{rel}: wanted {n} hits of {old[:75]!r}; found {s.count(old)}")
    p.write_text(s.replace(old,new),encoding="utf-8")
    print("ADAPT",rel)

transfer=f"{app}/transfer/TransferActivity.kt"
repo=f"{app}/transfer/hce/VitalWearHceTransferRepository.kt"

# In clean source, import history is saved through the original DB; the extra
# transfer-seen cross-app content provider is NOT needed for NFC transfer.
# Avoid transplanting VBHelper's private content-provider and its permission.
edit(transfer,'''    private val transferSeenDao
        get() = (application as VitalWearApp).sharedTransferSeenDao

''','')
edit(transfer,'''        val seenTimestamp = System.currentTimeMillis()
        transferSeenDao.recordImportedCharacterSeen(importCharacter, seenTimestamp)
''','')
edit(repo,"import com.github.cfogrady.vitalwear.transfer.recordImportedCharacterSeen\n","")
edit(repo,"import kotlinx.coroutines.launch\n","")
edit(repo,"import kotlinx.coroutines.CoroutineScope\n","")
edit(repo,"import kotlinx.coroutines.SupervisorJob\n","")
edit(repo,'''    private val seenSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

''','')
p=root/repo
s=p.read_text(encoding="utf-8")
begin=s.index("        seenSyncScope.launch {")
end=s.index("        // Keep COMMIT fast on HCE:",begin)
s=s[:begin]+s[end:]
p.write_text(s,encoding="utf-8")

# If a previous VitalWear instance is installed, both apps may advertise the
# SAME NFC AID. While this TransferActivity is foreground, ask Android to route
# the transfer to this clean service. Failure is non-fatal on non-NFC devices.
edit(transfer, "import android.content.Context\n",
    "import android.content.Context\nimport android.content.ComponentName\nimport android.nfc.NfcAdapter\nimport android.nfc.cardemulation.CardEmulation\nimport com.github.cfogrady.vitalwear.transfer.hce.VitalWearHostApduService\n")
edit(transfer,
'''    override fun onDestroy() {
        VitalWearHceSessionManager.clear()
        super.onDestroy()
    }''',
'''    override fun onResume() {
        super.onResume()
        runCatching {
            val adapter=NfcAdapter.getDefaultAdapter(this) ?: return@runCatching
            CardEmulation.getInstance(adapter).setPreferredService(
                this,ComponentName(this,VitalWearHostApduService::class.java))
        }
    }

    override fun onPause() {
        runCatching {
            val adapter=NfcAdapter.getDefaultAdapter(this) ?: return@runCatching
            CardEmulation.getInstance(adapter).unsetPreferredService(this)
        }
        super.onPause()
    }

    override fun onDestroy() {
        VitalWearHceSessionManager.clear()
        super.onDestroy()
    }''')

# Original clean HeartRateService StateFlow<Int?> (null before first reading).
# Fork TransferActivity expected Int, so avoid displaying/storing fake BPM.
edit(transfer,
    "heartRateCurrent = app.heartRateService.lastHeartRate.value,",
    "heartRateCurrent = app.heartRateService.lastHeartRate.value ?: 0,")
edit(repo,
    "heartRateCurrent = app.heartRateService.lastHeartRate.value,",
    "heartRateCurrent = app.heartRateService.lastHeartRate.value ?: 0,")

# Make receive/send UI labels explicit, using source VB Helper terminology.
upstream_strings=root/"app/src/main/res/values/strings.xml"
strings=upstream_strings.read_text(encoding="utf-8-sig")
imported=(fork/"app/src/main/res/values/strings.xml").read_text(encoding="utf-8-sig")
for match in re.finditer(r'<string name="(transfer_(?:title_app_link|button_send_to_vbh|button_receive_from_vbh|waiting_send|waiting_receive|waiting_success|waiting_failure|waiting_preparing))">[^<]*</string>',imported):
    entry=match.group()
    key=match.group(1)
    if f'name="{key}"' in strings:
        # Explicit text is guaranteed to match the UI controls.
        strings=re.sub(r'<string name="'+key+r'">[^<]*</string>',lambda _:entry,strings,count=1)
    else:
        strings=strings.replace("</resources>", "    "+entry+"\n</resources>")
upstream_strings.write_text(strings,encoding="utf-8")

# Keep original Activity/navigation and game engine; register only HCE.
manifest=root/"app/src/main/AndroidManifest.xml"
xml=manifest.read_text(encoding="utf-8-sig")
if "android.permission.NFC" not in xml:
    xml=xml.replace("    <application",'''    <uses-permission android:name="android.permission.NFC" />
    <uses-feature android:name="android.hardware.nfc" android:required="false" />
    <uses-feature android:name="android.hardware.nfc.hce" android:required="false" />
    <application''',1)
if "android.permission.VIBRATE" not in xml:
    xml=xml.replace("    <application",'''    <uses-permission android:name="android.permission.VIBRATE" />
    <application''',1)
service='''        <service
            android:name=".transfer.hce.VitalWearHostApduService"
            android:exported="true"
            android:permission="android.permission.BIND_NFC_SERVICE">
            <intent-filter>
                <action android:name="android.nfc.cardemulation.action.HOST_APDU_SERVICE" />
            </intent-filter>
            <meta-data
                android:name="android.nfc.cardemulation.host_apdu_service"
                android:resource="@xml/vitalwear_hce_service" />
        </service>
'''
if 'VitalWearHostApduService' not in xml:
    xml=xml.replace("</application>",service+"    </application>")
ET.fromstring(xml)
manifest.write_text(xml,encoding="utf-8")
print("NFC HOST CARD EMULATION REGISTERED IN ORIGINAL MANIFEST")

# Release identity: update in-place for *clean* app ID only, leaving existing
# 0.6.x/0.8.x original game installs and their data untouched.
for module in ("app","companion"):
    p=root/module/"build.gradle.kts"
    s=p.read_text(encoding="utf-8-sig")
    for old,new in [('versionCode = 83','versionCode = 84'),
                    ('versionName = "0.8.3-Upstream-RuntimeFix"',
                     'versionName = "0.8.4-Clean-VBHelper-NFC"')]:
        if s.count(old)!=1:raise RuntimeError(f"Unexpected {module} version {old}")
        s=s.replace(old,new)
    if 'applicationId = "dev.mikeki.vitalwear.gw8.clean"' not in s:
        raise RuntimeError(f"{module}: original clean package ID not preserved")
    p.write_text(s,encoding="utf-8")
# Deterministic, local NFC APDU contract tests (no physical VB required).
test=root/"app/src/test/java/com/github/cfogrady/vitalwear/transfer/hce/VitalWearHceProtocolPortTest.kt"
test.parent.mkdir(parents=True,exist_ok=True)
test.write_text(r'''package com.github.cfogrady.vitalwear.transfer.hce

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalWearHceProtocolPortTest {
    @After fun reset()=VitalWearHceSessionManager.clear()

    @Test fun vbhSelectAidRecognized() {
        val command=byteArrayOf(0x00,0xA4.toByte(),0x04,0x00,
            VitalWearHceProtocol.AID.size.toByte())+VitalWearHceProtocol.AID
        assertTrue(VitalWearHceProtocol.isSelectAidApdu(command))
        assertFalse(VitalWearHceProtocol.isSelectAidApdu(
            byteArrayOf(0x00,0xA4.toByte(),0x04,0x00,0x01,0x00)))
    }

    @Test fun vbhNegotiatesWatchToPhoneAndReadsChunks() {
        val payload="DigimonTransfer".toByteArray()
        VitalWearHceSessionManager.armSend(payload)
        val info=VitalWearHceSessionManager.negotiate(
            VitalWearHceProtocol.MODE_WATCH_TO_PHONE,4)
        assertNotNull(info)
        assertEquals(payload.size,info!!.payloadLength)
        assertEquals(4,info.maxChunkSize)
        assertArrayEquals("Digi".toByteArray(),
            VitalWearHceSessionManager.readChunk(0,4))
        assertArrayEquals("monT".toByteArray(),
            VitalWearHceSessionManager.readChunk(4,4))
    }

    @Test fun vbhReceivesInOrderAndCommitsSerializedPayload() {
        VitalWearHceSessionManager.armReceive()
        val info=VitalWearHceSessionManager.negotiate(
            VitalWearHceProtocol.MODE_PHONE_TO_WATCH,64)
        assertNotNull(info)
        assertTrue(VitalWearHceSessionManager.writeChunk(0,byteArrayOf(1,2,3)))
        assertFalse(VitalWearHceSessionManager.writeChunk(0,byteArrayOf(9)))
        assertTrue(VitalWearHceSessionManager.writeChunk(3,byteArrayOf(4,5)))
        assertArrayEquals(byteArrayOf(1,2,3,4,5),
            VitalWearHceSessionManager.takeReceivedPayload())
    }

    @Test fun shortAndExtendedApduBodiesMatch() {
        val short=byteArrayOf(0x80.toByte(),0x30,0x00,0x00,0x03,
            0x11,0x22,0x33)
        val extended=byteArrayOf(0x80.toByte(),0x30,0x00,0x00,0x00,
            0x00,0x03,0x11,0x22,0x33)
        assertArrayEquals(byteArrayOf(0x11,0x22,0x33),
            VitalWearHceProtocol.parseCommandData(short))
        assertArrayEquals(VitalWearHceProtocol.parseCommandData(short),
            VitalWearHceProtocol.parseCommandData(extended))
        assertArrayEquals(byteArrayOf(0x90.toByte(),0x00),
            VitalWearHceProtocol.buildResponse())
    }
}
''',encoding="utf-8")
print("PORT HCE PROTOCOL TESTS ADDED")

print("PORT DONE: VBHELPER APDU + PROTO + EMBEDDED SPRITES, NO OLD UI PATCHES")
