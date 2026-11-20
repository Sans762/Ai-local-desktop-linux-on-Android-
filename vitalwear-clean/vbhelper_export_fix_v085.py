#!/usr/bin/env python3
"""v0.8.5: Safe VB Helper export via Wear Data Layer and existing VBHelper share importer.

VBHelper 0.6.5.1-pre sends COMMIT BEFORE database import. Thus COMMIT
acknowledgement does not prove the Digimon was stored in VBHelper.
DO NOT delete the watch character on this acknowledgement.

Also provide a non-NFC phone export: Wear Channel -> phone companion ->
ACTION_SEND application/x-vitalwear-character to installed VBHelper.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
app="app/src/main/java/com/github/cfogrady/vitalwear"
phone="companion/src/main/java/com/github/cfogrady/vitalwear"

def change(rel,original,replacement,count=1):
    p=root/rel
    s=p.read_text(encoding="utf-8-sig")
    n=s.count(original)
    if n!=count:
        raise RuntimeError(f"UNEXPECTED SOURCE {rel}: expected {count} got {n}: {original[:80]!r}")
    p.write_text(s.replace(original,replacement),encoding="utf-8")
    print("PATCH",rel)

# Safest possible NFC transfer: preserve source until a confirmed copy exists.
change(f"{app}/transfer/hce/VitalWearHostApduService.kt",
'''                serviceScope.launch {
                    runCatching {
                        repository.deleteCurrentCharacterAfterSuccessfulSend()
                    }.onFailure {
                        Log.w(TAG, "Post-send delete failed after COMMIT", it)
                    }
                }
''',
'''                // VBHelper sends COMMIT before checking whether its own
                // database imported the character. Never delete on COMMIT.
                Log.i(TAG,"NFC export acknowledged; retaining local Digimon backup")
''')

# Add a plain third option on the original Transfer menu.
change(f"{app}/transfer/TransferScreen.kt",
    "    fun deleteActiveCharacter()\n",
    "    fun deleteActiveCharacter()\n    fun sendCopyToVBHelperPhone()\n")
change(f"{app}/transfer/TransferScreen.kt",
'''        })
        TransferState.WAITING -> {''',
'''        }, onShareToPhone = { controller.sendCopyToVBHelperPhone() })
        TransferState.WAITING -> {''')
change(f"{app}/transfer/TransferScreen.kt",
    "fun SelectSendOrReceive(onSelect: (SendOrReceive) -> Unit = {}) {",
'''fun SelectSendOrReceive(
    onSelect: (SendOrReceive) -> Unit = {},
    onShareToPhone: () -> Unit = {},
) {''')
change(f"{app}/transfer/TransferScreen.kt",
'''            Text(stringResource(R.string.transfer_button_receive_from_vbh))
        }
    }
}''',
'''            Text(stringResource(R.string.transfer_button_receive_from_vbh))
        }
        CompactButton(onClick = onShareToPhone) {
            Text("Copy to VBH via phone")
        }
    }
}''')

# Send through native Wear OS paired-device Data Layer, never delete character.
rel=f"{app}/transfer/TransferActivity.kt"
change(rel,"import android.os.Bundle\n",
'''import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
''')
change(rel,"    //------------------------------- Controller Members ---------------------------------------//",
'''    override fun sendCopyToVBHelperPhone() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val character=getActiveCharacterProto()
                    ?: throw IllegalStateException("No active Digimon")
                val nodes=Wearable.getNodeClient(this@TransferActivity).connectedNodes.await()
                if(nodes.isEmpty()) throw IllegalStateException("Connect the paired phone first")
                val client=Wearable.getChannelClient(this@TransferActivity)
                var success=false
                var lastError:Exception?=null
                for(node in nodes) {
                    try {
                        val channel=client.openChannel(
                            node.id,"vitalwear/VBHELPER_EXPORT").await()
                        try {
                            client.getOutputStream(channel).await().use { stream ->
                                character.writeTo(stream)
                                stream.flush()
                            }
                            success=true
                            break
                        } finally {
                            runCatching { client.close(channel).await() }
                        }
                    } catch(e:Exception) {
                        lastError=e
                    }
                }
                if(!success) throw (lastError ?: IllegalStateException("Phone not reachable"))
                runOnUiThread {
                    Toast.makeText(this@TransferActivity,
                        "Copy sent. Open phone Companion, tap Send to VB Helper.",
                        Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch(e:Exception) {
                runOnUiThread {
                    Toast.makeText(this@TransferActivity,
                        "Export failed: "+e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    //------------------------------- Controller Members ---------------------------------------//''')

# Phone companion: asynchronously save incoming protobuf to app-private files.
rel=f"{phone}/communication/WatchCommunicationService.kt"
change(rel,"import com.google.android.gms.wearable.ChannelClient\n",
'''import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.github.cfogrady.vitalwear.protos.Character
import kotlinx.coroutines.tasks.await
import java.io.File
import android.util.Log
''')
change(rel,'''        when(channel.path) {
            ChannelTypes.LOGS_DATA -> {''',
'''        when(channel.path) {
            "vitalwear/VBHELPER_EXPORT" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val client=Wearable.getChannelClient(this@WatchCommunicationService)
                    try {
                        val bytes=client.getInputStream(channel).await().use { stream ->
                            val b=ByteArray(16*1024)
                            val buffer=java.io.ByteArrayOutputStream()
                            while(true) {
                                val n=stream.read(b)
                                if(n<0) break
                                if(buffer.size()+n>4*1024*1024)
                                    throw IllegalArgumentException("Digimon file exceeds 4 MB")
                                buffer.write(b,0,n)
                            }
                            buffer.toByteArray()
                        }
                        val character=Character.parseFrom(bytes)
                        require(character.hasCharacterStats() &&
                                character.cardName.isNotBlank() &&
                                character.characterStats.slotId>=0) {
                            "Incomplete Digimon export"
                        }
                        val dir=File(filesDir,"vbhelper_export").apply { mkdirs() }
                        val tmp=File(dir,"pending.tmp")
                        val file=File(dir,"pending.vitalwear")
                        tmp.writeBytes(bytes)
                        if(file.exists()) file.delete()
                        check(tmp.renameTo(file)) {"Failed to save Digimon copy"}
                        Log.i("VW_VBHELPER_EXPORT",
                            "Copy ready: "+character.cardName+", "+bytes.size+" bytes")
                    } catch(e:Exception) {
                        Log.e("VW_VBHELPER_EXPORT","Could not save copy",e)
                    } finally {
                        runCatching { client.close(channel).await() }
                    }
                }
            }
            ChannelTypes.LOGS_DATA -> {''')

# Share using the MIME/Intent already handled in VBHelper MainActivity.
rel=f"{phone}/main/MainActivity.kt"
change(rel,"import android.content.Intent\n",
'''import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import java.io.File
''')
change(rel,'''            Button(onClick = {
                startActivity(Intent(applicationContext, ImportCardActivity::class.java))
            }) {''',
'''            Button(onClick = {
                val file=File(filesDir,"vbhelper_export/pending.vitalwear")
                if(!file.isFile || file.length()==0L) {
                    Toast.makeText(applicationContext,
                        "First use Watch > Transfer > Copy to VBH via phone",
                        Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val uri=FileProvider.getUriForFile(
                            this@MainActivity,packageName+".provider",file)
                        val intent=Intent(Intent.ACTION_SEND).apply {
                            type="application/x-vitalwear-character"
                            setPackage("com.github.nacabaro.vbhelper")
                            putExtra(Intent.EXTRA_STREAM,uri)
                            clipData=ClipData.newRawUri("VitalWear Digimon",uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(intent)
                    } catch(e:Exception) {
                        Toast.makeText(applicationContext,
                            "VB Helper unavailable: "+e.message,
                            Toast.LENGTH_LONG).show()
                    }
                }
            }) {
                Text(text="Send Digimon copy to VB Helper",color=Color.Cyan)
            }
            Button(onClick = {
                startActivity(Intent(applicationContext, ImportCardActivity::class.java))
            }) {''')

change("companion/src/main/res/xml/provider_paths.xml",
    "</paths>",
    '''    <files-path name="vbhelper-export" path="vbhelper_export/" />
</paths>''')
change("companion/build.gradle.kts",
    '    implementation(project(":common"))\n',
    '''    implementation(project(":common"))
    implementation(project(":protos"))
    implementation(libs.protobuf.javalite)
''')
for m in ("app","companion"):
    change(f"{m}/build.gradle.kts","versionCode = 84","versionCode = 85")
    change(f"{m}/build.gradle.kts",
           'versionName = "0.8.4-Clean-VBHelper-NFC"',
           'versionName = "0.8.5-Clean-VBHelper-SafeExport"')

print("v0.8.5 safe HCE and phone VB Helper sharing paths patched")
