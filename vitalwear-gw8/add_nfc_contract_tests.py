#!/usr/bin/env python3
"""Stable experimental version codes and contract tests for the NFC protocol.
Does not alter the VBHelper package. NFC hardware behavior requires real devices.
"""
from pathlib import Path
import sys
r=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
for module in ("app","companion"):
    p=r/module/"build.gradle.kts"
    src=p.read_text(encoding="utf-8-sig")
    b1='versionCode = libs.versions.projectVersionCode.get().toInt()'
    b2='versionName = libs.versions.projectVersion.get()'
    assert src.count(b1)==1,(module,"versionCode")
    assert src.count(b2)==1,(module,"versionName")
    src=src.replace(b1,"versionCode = 56").replace(b2,'versionName = "0.5.6-NFCBattle"')
    p.write_text(src,encoding="utf-8")
    print("BUMP",module)
test=r/"app/src/test/java/com/github/cfogrady/vitalwear/transfer/hce/NfcTapBattleProtocolTest.kt"
test.parent.mkdir(parents=True,exist_ok=True)
test.write_text(r'''package com.github.cfogrady.vitalwear.transfer.hce

import org.junit.Assert.*
import org.junit.Test

/** No hardware dependency: verifies the phone and watch agree on NFC APDUs. */
class NfcTapBattleProtocolTest {
    @Test fun recognizesAidSelectedByPhone() {
        val select=byteArrayOf(0x00,0xA4.toByte(),0x04,0x00,0x0A,
            0xF0.toByte(),0x56,0x49,0x54,0x41,0x4C,0x57,0x45,0x41,0x52,0x00)
        assertTrue(VitalWearHceProtocol.isSelectAidApdu(select))
        val other=select.copyOf()
        other[8]=0x00
        assertFalse(VitalWearHceProtocol.isSelectAidApdu(other))
    }

    @Test fun separateBattleCommandFromCharacterTransfer() {
        val instructions=setOf(
            VitalWearHceProtocol.INS_NEGOTIATE,
            VitalWearHceProtocol.INS_READ_CHUNK,
            VitalWearHceProtocol.INS_WRITE_CHUNK,
            VitalWearHceProtocol.INS_COMMIT,
            VitalWearHceProtocol.INS_STATUS,
            VitalWearHceProtocol.INS_SYNC_UI,
            VitalWearHceProtocol.INS_VIBRATE
        )
        assertFalse(instructions.contains(VitalWearHceProtocol.INS_TAP_BATTLE))
        assertEquals(0x71.toByte(),VitalWearHceProtocol.INS_TAP_BATTLE)
    }

    @Test fun nfcCommandAndStatusWords() {
        val tap=byteArrayOf(0x80.toByte(),0x71,0x00,0x00,0x00)
        assertEquals(VitalWearHceProtocol.CLA_VITALWEAR,tap[0])
        assertEquals(VitalWearHceProtocol.INS_TAP_BATTLE,tap[1])
        assertTrue(VitalWearHceProtocol.parseCommandData(tap).isEmpty())
        val success=VitalWearHceProtocol.buildResponse()
        assertArrayEquals(byteArrayOf(0x90.toByte(),0x00),success)
        val rejected=VitalWearHceProtocol.buildResponse(
            statusWord=VitalWearHceProtocol.SW_CONDITIONS_NOT_SATISFIED)
        assertArrayEquals(byteArrayOf(0x69,0x85.toByte()),rejected)
    }
}
''',encoding="utf-8")
print("WROTE NFC APDU CONTRACT TEST")
