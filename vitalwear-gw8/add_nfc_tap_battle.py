#!/usr/bin/env python3
"""Add real NFC Tap Battle via phone reader and existing Watch HCE.
It opens the original randomized BattleActivity and preserves VBHelper transfer.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
w="app/src/main/java/com/github/cfogrady/vitalwear/"
p="companion/src/main/java/com/github/cfogrady/vitalwear/"
def change(rel,a,b):
    path=root/rel
    t=path.read_text(encoding="utf-8-sig")
    n=t.count(a)
    if n!=1:raise RuntimeError(rel+" count="+str(n)+" expected one "+a[:90])
    path.write_text(t.replace(a,b),encoding="utf-8")
    print("PATCH",rel)
def write(rel,s):
    path=root/rel;path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(s,encoding="utf-8");print("WRITE",rel)

write(w+"battle/NfcTapBattleBus.kt",r'''package com.github.cfogrady.vitalwear.battle

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.github.cfogrady.vitalwear.R
import kotlinx.coroutines.flow.MutableSharedFlow

/** NFC HCE cannot start background activities. Prompt notification when necessary. */
object NfcTapBattleBus {
    val requests = MutableSharedFlow<Long>(extraBufferCapacity=1)
    private var lastTap=0L
    @Synchronized fun request(context:Context):Boolean {
        val now=SystemClock.elapsedRealtime()
        if(now-lastTap<5000L)return false
        if(requests.subscriptionCount.value>0 && requests.tryEmit(now)) {
            lastTap=now
            return true
        }
        if(Build.VERSION.SDK_INT>=33 &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            return false
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            "vitalwear_nfc_battle","NFC Digimon Battles",NotificationManager.IMPORTANCE_HIGH))
        val open=Intent(context,BattleActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val action=PendingIntent.getActivity(context,711,open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(711,Notification.Builder(context,"vitalwear_nfc_battle")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("NFC Tap Battle")
            .setContentText("Tap to fight a random opponent")
            .setContentIntent(action).setAutoCancel(true).build())
        lastTap=now
        return true
    }
}
''')
change(w+"transfer/hce/VitalWearHceProtocol.kt",
'    const val INS_VIBRATE: Byte = 0x70',
'    const val INS_VIBRATE: Byte = 0x70\n    const val INS_TAP_BATTLE: Byte = 0x71')
change(w+"transfer/hce/VitalWearHostApduService.kt",
'import com.github.cfogrady.vitalwear.VitalWearApp',
'import com.github.cfogrady.vitalwear.VitalWearApp\nimport com.github.cfogrady.vitalwear.battle.NfcTapBattleBus')
change(w+"transfer/hce/VitalWearHostApduService.kt",
'                VitalWearHceProtocol.INS_VIBRATE -> handleVibrate(data)',
'                VitalWearHceProtocol.INS_VIBRATE -> handleVibrate(data)\n                VitalWearHceProtocol.INS_TAP_BATTLE -> handleTapBattle()')
change(w+"transfer/hce/VitalWearHostApduService.kt",
'    private fun handleVibrate(data: ByteArray): ByteArray {',
'''    private fun handleTapBattle(): ByteArray {
        val app=application as VitalWearApp
        if (VitalWearHceSessionManager.currentMode()!=VitalWearHceSessionManager.Mode.IDLE)
            return VitalWearHceProtocol.buildResponse(statusWord=VitalWearHceProtocol.SW_CONDITIONS_NOT_SATISFIED)
        val active=app.characterManager.getCurrentCharacter()
        if(active==null || active.characterStats.sleeping || active.speciesStats.phase<=1)
            return VitalWearHceProtocol.buildResponse(statusWord=VitalWearHceProtocol.SW_CONDITIONS_NOT_SATISFIED)
        return if(NfcTapBattleBus.request(applicationContext))
            VitalWearHceProtocol.buildResponse()
        else VitalWearHceProtocol.buildResponse(statusWord=VitalWearHceProtocol.SW_CONDITIONS_NOT_SATISFIED)
    }

    private fun handleVibrate(data: ByteArray): ByteArray {''')
change(w+"main/MainActivity.kt",
'import com.github.cfogrady.vitalwear.battle.BattleActivity',
'''import com.github.cfogrady.vitalwear.battle.BattleActivity
import com.github.cfogrady.vitalwear.battle.NfcTapBattleBus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect''')
change(w+"main/MainActivity.kt",
'        val activityLaunchers = buildActivityLaunchers()\n        setContent {',
'''        val activityLaunchers = buildActivityLaunchers()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NfcTapBattleBus.requests.collect {
                    startActivity(android.content.Intent(this@MainActivity, BattleActivity::class.java))
                }
            }
        }
        setContent {''')

write(p+"battle/NfcBattleReaderActivity.kt",r'''package com.github.cfogrady.vitalwear.battle

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Phone ReaderMode sends one new HCE APDU; VBHelper's protocols are untouched. */
class NfcBattleReaderActivity: ComponentActivity(), NfcAdapter.ReaderCallback {
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private var status by mutableStateOf("Tap the Galaxy Watch 8 NFC antenna against the phone")
    private var listening=false
    private val select=byteArrayOf(0x00,0xA4.toByte(),0x04,0x00,0x0A,
        0xF0.toByte(),0x56,0x49,0x54,0x41,0x4C,0x57,0x45,0x41,0x52,0x00)
    private val trigger=byteArrayOf(0x80.toByte(),0x71,0x00,0x00,0x00)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(28.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.Center) {
                    Text("NFC TAP BATTLE", style=MaterialTheme.typography.h5)
                    Spacer(Modifier.height(16.dp))
                    Text(status)
                    Spacer(Modifier.height(12.dp))
                    Text("Open VitalWear on the watch, then tap to find a random enemy.")
                    Spacer(Modifier.height(22.dp))
                    Button(onClick={finish()}) { Text("Back") }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        val nfc=adapter
        status=when {
            nfc==null -> "No NFC hardware in this phone"
            !nfc.isEnabled -> "Turn on NFC in phone Settings"
            else -> try {
                nfc.enableReaderMode(this,this,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,null)
                listening=true
                "READY - touch the watch to the phone NFC area"
            } catch(e:Exception) { "NFC reader unavailable: "+e.javaClass.simpleName }
        }
    }
    override fun onPause() {
        if(listening) runCatching { adapter?.disableReaderMode(this) }
        listening=false
        super.onPause()
    }
    private fun ok(response:ByteArray):Boolean {
        val n=response.size
        return n>=2 && response[n-2]==0x90.toByte() && response[n-1]==0x00.toByte()
    }
    override fun onTagDiscovered(tag:Tag) {
        val dep=IsoDep.get(tag)
        if(dep==null) {
            runOnUiThread { status="Not a compatible watch NFC tag (ISO-DEP required)" }
            return
        }
        val message=try {
            dep.connect()
            dep.timeout=3500
            if(!ok(dep.transceive(select))) "VitalWear not detected - unlock the watch"
            else if(ok(dep.transceive(trigger)))
                "SUCCESS - random fight requested on watch. Tap notification if game closed."
            else "Watch declined - check partner, sleep, stage and NFC transfer state"
        } catch(e:Exception) { "NFC lost or timed out: "+e.javaClass.simpleName }
        finally { runCatching { dep.close() } }
        runOnUiThread { status=message }
    }
}
''')
change("companion/src/main/AndroidManifest.xml",
'        <activity\n            android:name="com.github.cfogrady.vitalwear.main.MainActivity"',
'''        <activity android:name="com.github.cfogrady.vitalwear.battle.NfcBattleReaderActivity"
            android:exported="false" />
        <activity
            android:name="com.github.cfogrady.vitalwear.main.MainActivity"''')
change(p+"main/MainActivity.kt",
'import com.github.cfogrady.vitalwear.firmware.FirmwareImportActivity',
'import com.github.cfogrady.vitalwear.firmware.FirmwareImportActivity\nimport com.github.cfogrady.vitalwear.battle.NfcBattleReaderActivity')
change(p+"main/MainActivity.kt",
'            Button(onClick = {\n                startActivity(Intent(applicationContext, FirmwareImportActivity::class.java))\n            }) {',
'''            Button(onClick = {
                startActivity(Intent(this@MainActivity,NfcBattleReaderActivity::class.java))
            }) { Text(text="NFC Tap Battle",color=Color.Cyan) }
            Button(onClick = {
                startActivity(Intent(applicationContext, FirmwareImportActivity::class.java))
            }) {''')
print("READY: real NFC Tap Battle patch. Requires CI compilation and hardware verification.")
