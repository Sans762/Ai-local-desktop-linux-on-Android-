#!/usr/bin/env python3
"""Restore completely standalone DIM/BEM import -> Wear OS ChannelClient.
Applies on top of patch_project.py to upstream 457R0/VitalWear.
Does not invoke or modify VBHelper, and does not require a real VB device.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear").resolve()
def replace(rel,original,updated):
    p=root/rel
    text=p.read_text(encoding="utf-8-sig")
    found=text.count(original)
    if found!=1:
        raise RuntimeError(f"{rel} expected exactly one replacement; found {found}. Context: {original[:100]!r}")
    p.write_text(text.replace(original,updated), encoding="utf-8")
    print("FIXED",rel,original[:50])
phoneMain="companion/src/main/java/com/github/cfogrady/vitalwear/main/MainActivity.kt"
replace(phoneMain,"import com.github.cfogrady.vitalwear.VitalWearCompanion","import com.github.cfogrady.vitalwear.VitalWearCompanion\nimport com.github.cfogrady.vitalwear.card.ImportCardActivity")
replace(phoneMain,'''                Toast.makeText(
                    applicationContext,
                    "Card import moved to VBHelper. Use VBHelper to import DIM/BEM cards.",
                    Toast.LENGTH_LONG,
                ).show()''','''                startActivity(Intent(this@MainActivity, ImportCardActivity::class.java))''')
replace(phoneMain,'Import Card Image (Moved to VBHelper)','Import Card to Watch')
manifest="companion/src/main/AndroidManifest.xml"
replace(manifest,'android.hardware.nfc" android:required="true"','android.hardware.nfc" android:required="false"')
replace(manifest,'''    <!-- Access VBHelper's shared card database (signature-level; automatically granted) -->
    <uses-permission android:name="com.github.nacabaro.vbhelper.permission.ACCESS_CARD_DATA" />
''','')
replace(manifest,'<activity android:name="com.github.cfogrady.vitalwear.card.ValidateCardActivity"/>','''<activity
            android:name="com.github.cfogrady.vitalwear.card.ImportCardActivity"
            android:exported="false" />
        <activity android:name="com.github.cfogrady.vitalwear.card.ValidateCardActivity"/>''')
p="companion/src/main/java/com/github/cfogrady/vitalwear/card/ImportCardActivity.kt"
replace(p,'''        UnlockCard,
        ImportCard,
        Success''','''        UnlockCard,
        ImportCard,
        Success,
        Error''')
replace(p,'''    private val importStatus = MutableStateFlow("Preparing transfer")''','''    private val importStatus = MutableStateFlow("Preparing transfer")
    private val errorText = MutableStateFlow("")''')
replace(p,'''                        withContext(Dispatchers.IO) {
                            importCard()
                            importState.value = ImportState.Success
                        }''','''                        try {
                            withContext(Dispatchers.IO) {
                                importCard()
                            }
                            importState.value = ImportState.Success
                        } catch (e: Exception) {
                            Timber.e(e, "Direct watch card import failed")
                            errorText.value = e.message ?: "Unknown card transfer error"
                            importState.value = ImportState.Error
                        }''')
replace(p,'''                ImportState.Success -> {
                    LaunchedEffect(true) {
                        Handler(Looper.getMainLooper()!!).postDelayed({
                            finish()
                        }, 1000)
                    }
                    Text(text = "Imported Successfully")
                }''','''                ImportState.Success -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Card sent to watch. Check the watch notification to confirm import.")
                        Button(onClick = { finish() }) { Text("Done") }
                    }
                }
                ImportState.Error -> {
                    val reason by errorText.collectAsState()
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Import failed: $reason")
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }''')
replace(p,'''        contentResolver.openInputStream(uri).use {
            card = dimReader.readCard(it, false)
            // No physical VB required for local card import.
            // Original file bytes still go through DimReader verification.
            importState.value = ImportState.ImportCard
        }''','''        try {
            contentResolver.openInputStream(uri).use { stream ->
                require(stream != null) { "Unable to open selected card file" }
                card = dimReader.readCard(stream, false)
                // No physical VB needed: read and validate imported local file.
                importState.value = ImportState.ImportCard
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to parse DIM/BEM card file")
            errorText.value = e.message ?: "Invalid card file"
            importState.value = ImportState.Error
        }''')
replace(p,'''        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        val cardWriter = DimWriter()''','''        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        check(nodes.isNotEmpty()) {
            "No Galaxy Watch connected. Check Bluetooth, Wi-Fi and open VitalWear on watch."
        }
        val cardWriter = DimWriter()''')
# Source fork's sender wrote only (name, uniqueSprites, size, bytes), but the
# fork's watch CardReceiver expects (name, uniqueSprites, convertToBem, size, bytes).
# Missing convertToBem caused payload sizes to be misparsed as huge values.
replace(p,'''        val metadataLength = cardNameBytes.size + 1 + 1 + 4''','''        val metadataLength = cardNameBytes.size + 1 + 1 + 1 + 4''')
replace(p,'''                output.writeByte(if(uniqueSprites.value) 1 else 0)
                output.writeInt(cardPayload.size)''','''                output.writeByte(if(uniqueSprites.value) 1 else 0)
                output.writeByte(0) // convertToBem=false. Required by watch receiver.
                output.writeInt(cardPayload.size)''')
replace(p,'''        importStatus.value = "Transfer complete"
        importPercent.value = 100

        // Sync the card data to VBHelper so both apps share one database.
        // This is non-blocking and failure-tolerant — a missing VBHelper install is fine.
        importStatus.value = "Syncing to VBHelper"
        // VBHelper database untouched; NFC communication remains separate.''','''        importStatus.value = "Transfer sent; confirm successful import on watch"
        importPercent.value = 100
        // Card import is fully standalone; VBHelper remains completely untouched.''')
print("ALL OK: Direct importer visible, registered, and sender payload matches watch receiver")
