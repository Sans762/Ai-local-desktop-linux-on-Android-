#!/usr/bin/env python3
"""Protect NFC return imports with a durable pending-recovery record on WATCH.
This cannot fix a buggy VBHelper deletion callback; source backup remains essential.
Must apply after confirm_nfc_before_ack.py, pixel_ui_v06.py.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base="app/src/main/java/com/github/cfogrady/vitalwear/"
def write(rel,data):
 p=root/rel;p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text(data,encoding="utf-8");print("WRITE",rel)
def edit(rel,old,new):
 p=root/rel;s=p.read_text(encoding="utf-8-sig")
 if s.count(old)!=1:raise RuntimeError(f"Expected 1 occurrence in {rel}, got {s.count(old)}: {old[:100]}")
 p.write_text(s.replace(old,new),encoding="utf-8");print("PATCH",rel)

write(base+"transfer/hce/NfcPendingVault.kt",r'''package com.github.cfogrady.vitalwear.transfer.hce

import android.content.Context
import android.util.Log
import com.github.cfogrady.vitalwear.VitalWearApp
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable emergency copy of a received VBHelper character, BEFORE HCE acknowledges COMMIT.
 * Safe on process death / NFC field loss, and retryable after importing missing DIM/BEM files.
 * Not a substitute for backing up VBHelper: its existing failure callback is unsafe.
 */
object NfcPendingVault {
    private val retrying=AtomicBoolean(false)
    private const val MAX_PROTO_BYTES=2_000_000
    private const val MAX_COPIES=8

    fun stash(context:Context,bytes:ByteArray):File? {
        if(bytes.isEmpty() || bytes.size>MAX_PROTO_BYTES)return null
        return try {
            val folder=File(context.filesDir,"hce_received_backup")
            check(folder.exists() || folder.mkdirs())
            val id=UUID.randomUUID().toString()
            val temp=File(folder,id+".tmp")
            val out=File(folder,id+".pb")
            FileOutputStream(temp).use{stream->
                stream.write(bytes)
                stream.fd.sync()
            }
            check(temp.renameTo(out))
            val files=folder.listFiles{f->f.extension=="pb"}
                ?.sortedByDescending{it.lastModified()} ?: emptyList()
            files.drop(MAX_COPIES).forEach{it.delete()}
            out
        }catch(e:Exception) {
            Log.e("VW_HCE_VAULT","Unable to persist NFC proto",e)
            null
        }
    }

    fun count(context:Context):Int {
        return File(context.filesDir,"hce_received_backup")
            .listFiles{f->f.extension=="pb"}?.size ?: 0
    }

    suspend fun retryPending(app:VitalWearApp):Boolean=withContext(Dispatchers.IO) {
        if(!retrying.compareAndSet(false,true))return@withContext false
        try {
            val list=File(app.filesDir,"hce_received_backup")
                .listFiles{f->f.extension=="pb"}
                ?.sortedBy{it.lastModified()} ?: emptyList()
            if(list.isEmpty())return@withContext false
            var restored=false
            val importer=VitalWearHceTransferRepository(app)
            for(file in list) {
                try {
                    val ok=importer.importCharacter(file.readBytes())
                    if(ok) {
                        file.delete()
                        restored=true
                    }
                }catch(e:Exception) {
                    Log.w("VW_HCE_VAULT","Pending character still cannot load",e)
                }
            }
            restored
        }finally {
            retrying.set(false)
        }
    }
}
''')

hce=base+"transfer/hce/VitalWearHostApduService.kt"
edit(hce,
'''                VitalWearHceSessionManager.clear(resetStatus=false)
                VitalWearHceSessionManager.markSyncing()
                bumpApduCount()
                // HCE may respond asynchronously.''',
'''                // An ACK from VBHelper may trigger deletion even if its UI later fails.
                // Durably save the incoming protobuf before acknowledging or importing.
                val durableBackup=NfcPendingVault.stash(applicationContext,payload)
                    ?: return VitalWearHceProtocol.buildResponse(
                        statusWord=VitalWearHceProtocol.SW_CONDITIONS_NOT_SATISFIED
                    )
                VitalWearHceSessionManager.clear(resetStatus=false)
                VitalWearHceSessionManager.markSyncing()
                bumpApduCount()
                // HCE may respond asynchronously.''')
edit(hce,
'''                    val statusWord=if(accepted) {
                        VitalWearHceSessionManager.markSuccess()''',
'''                    val statusWord=if(accepted) {
                        durableBackup.delete()
                        VitalWearHceSessionManager.markSuccess()''')

# Expose a real recovery action, not a fake entry.
menu=base+"main/VbMenuScreen.kt"
edit(menu,
'''import com.github.cfogrady.vitalwear.heartrate.SensorDiagnosticsActivity''',
'''import com.github.cfogrady.vitalwear.heartrate.SensorDiagnosticsActivity
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.transfer.hce.NfcPendingVault
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext''')
edit(menu,
'''    val actions=listOf(
        Item("STATUS"''',
'''    val actions=listOf(
        Item("STATUS"''') if False else None
# The existing local coroutine scope is declared after "actions", so recovery uses a direct scoped
# coroutine created via rememberCoroutineScope before the actions list.
edit(menu,
'''    val evolved=(pet?.speciesStats?.phase?:0)>1
    val actions=listOf(''',
'''    val evolved=(pet?.speciesStats?.phase?:0)>1
    val recoveryScope=rememberCoroutineScope()
    val app=context.applicationContext as VitalWearApp
    val actions=listOf(''')
edit(menu,
'''        Item("CONNECT",true){controller.launchTransferActivity()},
        Item("SENSOR",true)''',
'''        Item("CONNECT",true){controller.launchTransferActivity()},
        Item("RECOVER NFC",true){
            recoveryScope.launch {
                val success=withContext(Dispatchers.IO){NfcPendingVault.retryPending(app)}
                Toast.makeText(context,
                    if(success)"Recovered Digimon on watch"
                    else "No recoverable Digimon yet. Check DIM/BEM card import.",
                    Toast.LENGTH_LONG).show()
            }
        },
        Item("SENSOR",true)''')
print("RECOVERY SYSTEM INSTALLED")
