#!/usr/bin/env python3
"""v0.8.8: exact Digimon transfer via the user's own VBHelper database export.

VBHelper's stock NFC exporter omits TransferSpecies. A companion cannot read
VBHelper's sandbox; the user-owned Export data (.vbhelper SQLite) is the safe
route to complete original sprites, card metadata and character statistics.

Do not alter appId, signing config, user data or existing game menus.
Do not claim plain NFC transmits sprite data.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
wear=root/"app/src/main/java/com/github/cfogrady/vitalwear"
phone=root/"companion/src/main/java/com/github/cfogrady/vitalwear"

def replace(p,old,new,n=1):
    text=p.read_text(encoding="utf-8-sig")
    assert text.count(old)==n,(p,old[:90],text.count(old))
    p.write_text(text.replace(old,new),encoding="utf-8")
    print("PATCH",p)

# VitalWear's sanitizer silently discarded TransferCard + TransferSpecies.
# Without this fix even an exporter that sends real art loses it.
p=wear/"transfer/TransferActivity.kt"
s=p.read_text()
begin=s.index("fun Character.sanitizeForImport(): Character {")
tail=s[begin:]
old='''        .putAllMaxAdventureCompletedByCard(
            this.maxAdventureCompletedByCardMap.filterKeys { it.isNotBlank() }
                .mapValues { (_, value) -> value.coerceAtLeast(0) }
        )
        .build()
}'''
assert tail.count(old)==1
new='''        .putAllMaxAdventureCompletedByCard(
            this.maxAdventureCompletedByCardMap.filterKeys { it.isNotBlank() }
                .mapValues { (_, value) -> value.coerceAtLeast(0) }
        )
        // Metadata is not user stats, but essential for real Digimon sprites.
        .also { builder ->
            if (this.hasTransferCard()) builder.setTransferCard(this.transferCard)
            if (this.hasTransferSpecies()) builder.setTransferSpecies(this.transferSpecies)
        }
        .build()
}'''
s=s[:begin]+tail.replace(old,new)
p.write_text(s)

# Fix v0.8.6 fallback that showed the wrong blue cartoon and prevented
# later full metadata from updating those generic frames.
p=wear/"transfer/TransferEmbeddedMetadata.kt"
s=p.read_text()
start=s.index("internal fun Character.resolveImportedCardMetaOrPlaceholder(")
stop=s.index("\nprivate fun Character.ensureTransferSpeciesExists(",start)
resolver='''internal fun Character.resolveImportedCardMetaOrPlaceholder(
    context: Context,
    cardMetaEntityDao: CardMetaEntityDao,
    speciesEntityDao: SpeciesEntityDao,
): CardMetaEntity? {
    if (!hasCharacterStats() || characterStats.slotId < 0) return null

    val matched = resolveImportedCardMeta(cardMetaEntityDao, speciesEntityDao)
    if (matched != null) {
        // Repair card type too: v0.8.7's fake card could be DIM for a BE Digimon.
        if (matched.cardChecksum == 0 && hasTransferCard() && hasTransferSpecies()) {
            val restored = matched.copy(
                cardType = if (transferCard.isBem) CardType.BEM else CardType.DIM,
                franchise = transferCard.franchise
            )
            cardMetaEntityDao.update(restored)
        }
        // Full metadata can repair placeholders from the older 0.8.7 build.
        ensureTransferSpeciesExists(context, matched, speciesEntityDao)
        return cardMetaEntityDao.getByName(matched.cardName) ?: matched
    }

    // Stock VBHelper NFC payload has no sprites. Never create a fake Digimon
    // or report a successful transfer unless real card art is available.
    if (!hasTransferCard() || !hasTransferSpecies()) return null
    if (!transferSpecies.hasValidArtwork()) return null

    val transfer = transferCard
    val name = transfer.cardName.takeIf { it.isNotBlank() }
        ?: cardName.takeIf { it.isNotBlank() }
        ?: return null
    val resolvedName = buildPlaceholderCardName(cardMetaEntityDao, name, transfer.cardId)
    val meta = cardMetaEntityDao.getByName(resolvedName) ?: CardMetaEntity(
        cardName = resolvedName,
        cardId = transfer.cardId,
        cardChecksum = 0,
        cardType = if (transfer.isBem) CardType.BEM else CardType.DIM,
        franchise = transfer.franchise,
        maxAdventureCompletion = null,
    ).also { cardMetaEntityDao.insert(it) }
    ensureTransferSpeciesExists(context, meta, speciesEntityDao)
    return meta
}

private fun Character.TransferSpecies.hasValidArtwork(): Boolean {
    val w = spriteWidth
    val h = spriteHeight
    if (w !in 1..128 || h !in 1..128) return false
    val expected = w * h * 2
    return idle1.size() == expected &&
        idle2.size() == expected &&
        walk1.size() == expected &&
        nameSpriteWidth in 1..128 && nameSpriteHeight in 1..128 &&
        nameSprite.size() == nameSpriteWidth * nameSpriteHeight * 2
}
'''
s=s[:start]+resolver+s[stop:]
# Need overwrite only the old fake placeholder and preserve any actual
# pre-installed DIM/BEM artwork.
old='''    if (existing != null) {
        return
    }

    val spriteDirName = buildTransferSpriteDir(matchedCard.cardName, matchedCard.cardId, transferSpecies.slotId)'''
new='''    if (!transferSpecies.hasValidArtwork()) {
        throw IllegalArgumentException("Invalid incoming Digimon sprite dimensions")
    }
    if (existing != null && !existing.spriteDirName.startsWith("transfer_")) {
        // Real DIM/BEM artwork installed: never overwrite proprietary card art.
        return
    }
    val upgradingPlaceholder = existing != null
    val spriteDirName = existing?.spriteDirName
        ?: buildTransferSpriteDir(matchedCard.cardName, matchedCard.cardId, transferSpecies.slotId)'''
assert s.count(old)==1
s=s.replace(old,new)
old='''    speciesEntityDao.insertAll(
        listOf(
            SpeciesEntity('''
new='''    val importedSpecies = SpeciesEntity('''
assert s.count(old)==1
s=s.replace(old,new)
old='''                raised = transferSpecies.stage > 0,
            )
        )
    )
}'''
new='''                raised = transferSpecies.stage > 0,
            )
    if (upgradingPlaceholder) {
        speciesEntityDao.update(importedSpecies)
    } else {
        speciesEntityDao.insertAll(listOf(importedSpecies))
    }
}'''
assert s.count(old)==1
s=s.replace(old,new)
p.write_text(s)
p=root/"common/src/main/java/com/github/cfogrady/vitalwear/common/card/db/SpeciesEntityDao.kt"
s=p.read_text()
old='''    @Insert
    fun insertAll(speciesEntities: Collection<SpeciesEntity>)'''
new='''    @Insert
    fun insertAll(speciesEntities: Collection<SpeciesEntity>)

    @Update
    fun update(speciesEntity: SpeciesEntity)'''
assert s.count(old)==1
p.write_text(s.replace(old,new))

# Add a dedicated Data Layer receiver for complete, user-consented backup
# transfers. The sender waits for a *real database import* acknowledgement.
p=wear/"communication/PhoneCommunicationService.kt"
s=p.read_text()
anchor="import kotlinx.coroutines.tasks.await\n"
assert s.count(anchor)==1
s=s.replace(anchor,anchor+'''import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.github.cfogrady.vitalwear.transfer.hce.VitalWearHceTransferRepository
import java.io.ByteArrayOutputStream
''')
anchor='''            ChannelTypes.FIRMWARE_DATA -> {'''
assert s.count(anchor)==1
handler='''            "vitalwear/VBHELPER_FULL_CHARACTER" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val app = application as VitalWearApp
                    val client = Wearable.getChannelClient(this@PhoneCommunicationService)
                    var success = false
                    try {
                        withTimeout(30000L) {
                            app.characterManager.initialized.first { it }
                        }
                        val bytes = withTimeout(30000L) {
                            client.getInputStream(channel).await().use { stream ->
                                val buffer = ByteArrayOutputStream()
                                val chunk = ByteArray(8192)
                                while (true) {
                                    val count = stream.read(chunk)
                                    if (count < 0) break
                                    if (buffer.size() + count > 2 * 1024 * 1024)
                                        throw IllegalArgumentException("Digimon export too large")
                                    buffer.write(chunk, 0, count)
                                }
                                buffer.toByteArray()
                            }
                        }
                        success = withTimeout(30000L) {
                            VitalWearHceTransferRepository(app).importCharacter(bytes)
                        }
                        Timber.i("Full Digimon received, saved=" + success)
                    } catch (e: Exception) {
                        Timber.e(e, "Full VBHelper import rejected")
                    } finally {
                        runCatching { client.close(channel).await() }
                        runCatching {
                            Wearable.getMessageClient(this@PhoneCommunicationService)
                                .sendMessage(
                                    channel.nodeId,
                                    "vitalwear/VBHELPER_FULL_RESULT",
                                    byteArrayOf(if (success) 1 else 0)
                                ).await()
                        }.onFailure { Timber.e(it, "Unable to send VBHelper import receipt") }
                    }
                }
            }
'''
s=s.replace(anchor,handler+anchor)
p.write_text(s)

# Classic Android UI to keep the importer small, readable, and reliable.
# It does not import VBHelper database into VitalWear's Room database.
p=phone/"main/VbHelperBackupImportActivity.kt"
p.parent.mkdir(parents=True,exist_ok=True)
assert not p.exists()
p.write_text(r'''package com.github.cfogrady.vitalwear.main

import android.app.AlertDialog
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.github.cfogrady.vitalwear.protos.Character
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Imports an explicit VBHelper Settings > Export data backup.
 * No root, no private database access, no physical VB hardware.
 * Backup remains on phone; only selected character and real sprites
 * are sent to the paired Galaxy Watch by Wear OS Data Layer.
 */
class VbHelperBackupImportActivity : ComponentActivity() {
    private data class SourceMon(
        val id: Long, val card: String, val slot: Int, val stage: Int
    )

    private var backup: File? = null
    private lateinit var status: TextView

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            status.text = "กำลังอ่านสำเนาข้อมูล VB Helper..."
            try {
                val selected = withContext(Dispatchers.IO) { loadBackup(uri) }
                backup?.delete()
                backup = selected
                val monsters = withContext(Dispatchers.IO) {
                    listMonsters(selected)
                }
                require(monsters.isNotEmpty()) { "ไม่พบดิจิมอนในฐานข้อมูล" }
                status.text = "พบดิจิมอน " + monsters.size + " ตัว เลือกตัวที่จะส่ง"
                val labels = monsters.map { mon ->
                    mon.card + " • Slot " + mon.slot + " • Stage " + mon.stage
                }.toTypedArray()
                AlertDialog.Builder(this@VbHelperBackupImportActivity)
                    .setTitle("เลือกดิจิมอนตัวจริง")
                    .setItems(labels) { _, index ->
                        sendMonster(monsters[index])
                    }
                    .setNegativeButton("ยกเลิก", null)
                    .show()
            } catch (e: Exception) {
                status.text = "อ่านไฟล์ไม่ได้: " + (e.message ?: "รูปแบบไม่ถูกต้อง")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            text = "ส่งดิจิมอนพร้อมภาพจริงจาก VB Helper"
            textSize = 22f
        })
        layout.addView(TextView(this).apply {
            text = "1. เปิด VB Helper > Settings > Export data\n" +
                "2. เลือกไฟล์ .vbhelper ที่บันทึกไว้\n" +
                "3. เลือกดิจิมอน แล้วส่งไป Galaxy Watch\n\n" +
                "จะอ่านเฉพาะไฟล์ที่คุณเลือก ไม่แก้ไขหรือลบข้อมูลใน VB Helper"
            textSize = 16f
        })
        layout.addView(Button(this).apply {
            text = "เลือกไฟล์ Export data จาก VB Helper"
            setOnClickListener {
                picker.launch(arrayOf("*/*"))
            }
        })
        status = TextView(this).apply {
            text = "พร้อมอ่านข้อมูลดิจิมอน"
            textSize = 16f
        }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onDestroy() {
        backup?.delete()
        super.onDestroy()
    }

    private fun loadBackup(uri: Uri): File {
        val file = File.createTempFile("vbhelper_owned_", ".sqlite", cacheDir)
        try {
            var total = 0L
            val input = contentResolver.openInputStream(uri)
                ?: error("เปิดไฟล์ไม่ได้")
            input.use { stream ->
                file.outputStream().use { output ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = stream.read(chunk)
                        if (read < 0) break
                        total += read
                        require(total <= 120L * 1024 * 1024) {
                            "ไฟล์ใหญ่เกิน 120 MB"
                        }
                        output.write(chunk, 0, read)
                    }
                }
            }
            require(total > 100L) { "ไฟล์ว่างหรือไม่ใช่ฐานข้อมูล" }
            val header = ByteArray(16)
            file.inputStream().use { require(it.read(header) == 16) }
            require(String(header, Charsets.US_ASCII) == "SQLite format 3\u0000") {
                "ต้องเลือกไฟล์ .vbhelper ที่ Export จาก VB Helper"
            }
            openReadonly(file).use { db ->
                for (name in listOf(
                    "UserCharacter", "CardCharacter", "Card", "Sprite"
                )) {
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(name)
                    ).use { cursor ->
                        require(cursor.moveToFirst()) {
                            "ไม่พบตาราง " + name + " ในไฟล์"
                        }
                    }
                }
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun openReadonly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            file.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )

    private fun listMonsters(file: File): List<SourceMon> =
        openReadonly(file).use { db ->
            db.rawQuery(
                """SELECT u.id, d.name, c.charaIndex, c.stage
                   FROM UserCharacter u
                   JOIN CardCharacter c ON c.id = u.charId
                   JOIN Card d ON d.id = c.cardId
                   ORDER BY u.id DESC""", emptyArray()
            ).use { cur ->
                val result = mutableListOf<SourceMon>()
                while (cur.moveToNext()) {
                    result.add(SourceMon(
                        cur.getLong(0), cur.getString(1),
                        cur.getInt(2), cur.getInt(3)
                    ))
                }
                result
            }
        }

    private fun sendMonster(mon: SourceMon) {
        lifecycleScope.launch {
            status.text = "กำลังเตรียมภาพจริงของ " + mon.card + "..."
            try {
                val file = backup ?: error("กรุณาเลือกไฟล์อีกครั้ง")
                val character = withContext(Dispatchers.IO) {
                    buildRealCharacter(file, mon.id)
                }
                status.text = "กำลังส่งดิจิมอนและภาพจริงไปนาฬิกา..."
                val result = withContext(Dispatchers.IO) {
                    sendAndConfirm(character)
                }
                status.text = if (result) {
                    "สำเร็จ! นาฬิกายืนยันว่าบันทึกดิจิมอนและภาพจริงแล้ว"
                } else {
                    "ยังไม่สำเร็จ: นาฬิกาไม่ยืนยันการบันทึก ห้ามลบดิจิมอนใน VB Helper"
                }
            } catch (e: Exception) {
                status.text = "ส่งไม่สำเร็จ: " + (e.message ?: "โปรดตรวจการเชื่อมต่อ")
            }
        }
    }

    private fun Cursor.index(name: String): Int = getColumnIndexOrThrow(name)
    private fun Cursor.num(name: String, default: Int = 0): Int =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }
            ?.let { getInt(it) } ?: default
    private fun Cursor.text(name: String, default: String = ""): String =
        getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }
            ?.let { getString(it) } ?: default
    private fun Cursor.blob(name: String): ByteArray =
        getBlob(index(name)) ?: ByteArray(0)

    private fun validSprite(data: ByteArray, w: Int, h: Int): ByteString {
        require(w in 1..128 && h in 1..128) {
            "ขนาดภาพดิจิมอนไม่ถูกต้อง"
        }
        require(data.size == w * h * 2) {
            "ข้อมูลภาพไม่ครบหรือรูปแบบภาพไม่ตรง"
        }
        return ByteString.copyFrom(data)
    }

    private fun buildRealCharacter(file: File, id: Long): Character =
        openReadonly(file).use { db ->
            val sql = """SELECT u.*, c.charaIndex AS vbSlot,
                c.stage AS vbStage, c.attribute AS vbAttribute,
                c.baseHp AS vbBaseHp, c.baseBp AS vbBaseBp,
                c.baseAp AS vbBaseAp, c.nameSprite AS vbName,
                c.nameWidth AS vbNameW, c.nameHeight AS vbNameH,
                d.cardId AS vbCardId, d.name AS vbCardName,
                d.stageCount AS vbStages, d.isBEm AS vbBem,
                s.width AS vbW, s.height AS vbH,
                s.spriteIdle1, s.spriteIdle2, s.spriteWalk1,
                s.spriteWalk2, s.spriteRun1, s.spriteRun2,
                s.spriteTrain1, s.spriteTrain2, s.spriteHappy,
                s.spriteSleep, s.spriteAttack, s.spriteDodge
                FROM UserCharacter u
                JOIN CardCharacter c ON c.id = u.charId
                JOIN Card d ON d.id = c.cardId
                JOIN Sprite s ON s.id = c.spriteId
                WHERE u.id=? LIMIT 1"""
            db.rawQuery(sql, arrayOf(id.toString())).use { c ->
                require(c.moveToFirst()) { "ไม่พบตัวละครที่เลือก" }
                val width = c.num("vbW")
                val height = c.num("vbH")
                val nameW = c.num("vbNameW")
                val nameH = c.num("vbNameH")
                val idle = validSprite(c.blob("spriteIdle1"), width, height)
                val name = validSprite(c.blob("vbName"), nameW, nameH)
                fun frame(field: String): ByteString {
                    val data = c.blob(field)
                    return if (data.isNotEmpty()) validSprite(data, width, height)
                        else idle
                }
                val rawAttribute = c.text("vbAttribute").uppercase()
                val attribute = when {
                    rawAttribute.contains("VACCINE") -> 0
                    rawAttribute.contains("DATA") -> 1
                    rawAttribute.contains("VIRUS") -> 2
                    else -> 0
                }
                val cardName = c.text("vbCardName")
                val slot = c.num("vbSlot")
                val bem = c.num("vbBem") != 0
                val metadata = Character.TransferCard.newBuilder()
                    .setCardId(c.num("vbCardId"))
                    .setCardName(cardName)
                    .setIsBem(bem)
                    .setStageCount(c.num("vbStages"))
                    .setFranchise(0)
                    .build()
                val species = Character.TransferSpecies.newBuilder()
                    .setSlotId(slot)
                    .setStage(c.num("vbStage"))
                    .setAttribute(attribute)
                    .setBaseHp(c.num("vbBaseHp"))
                    .setBaseBp(c.num("vbBaseBp"))
                    .setBaseAp(c.num("vbBaseAp"))
                    .setNameSprite(name)
                    .setNameSpriteWidth(nameW)
                    .setNameSpriteHeight(nameH)
                    .setIdle1(idle)
                    .setIdle2(frame("spriteIdle2"))
                    .setWalk1(frame("spriteWalk1"))
                    .setWalk2(frame("spriteWalk2"))
                    .setRun1(frame("spriteRun1"))
                    .setRun2(frame("spriteRun2"))
                    .setTrain1(frame("spriteTrain1"))
                    .setTrain2(frame("spriteTrain2"))
                    .setWin(frame("spriteHappy"))
                    .setDown(frame("spriteSleep"))
                    .setAttack(frame("spriteAttack"))
                    .setDodge(frame("spriteDodge"))
                    .setSplash(idle)
                    .setSpriteWidth(width)
                    .setSpriteHeight(height)
                    .build()
                val wins = c.num("totalBattlesWon")
                val lost = c.num("totalBattlesLost")
                val phaseWins = c.num("currentPhaseBattlesWon")
                val phaseLost = c.num("currentPhaseBattlesLost")
                val stats = Character.CharacterStats.newBuilder()
                    .setSlotId(slot)
                    .setVitals(c.num("vitalPoints"))
                    .setTimeUntilNextTransformation(
                        c.num("transformationCountdown").toLong() * 60L
                    )
                    .setTrainedPp(c.num("trophies"))
                    .setTotalBattles(wins + lost)
                    .setTotalWins(wins)
                    .setCurrentPhaseBattles(phaseWins + phaseLost)
                    .setCurrentPhaseWins(phaseWins)
                    .setMood(c.num("mood"))
                    .setAgeInDays(c.num("ageInDays"))
                    .setActivityLevel(c.num("activityLevel"))
                    .setHeartRateCurrent(c.num("heartRateCurrent"))
                    .setDeviceType(
                        if (c.text("characterType") == "BEDevice")
                            Character.CharacterStats.TransferDeviceType.TRANSFER_DEVICE_TYPE_BE
                        else Character.CharacterStats.TransferDeviceType.TRANSFER_DEVICE_TYPE_VB
                    )
                val extra = if (c.text("characterType") == "BEDevice") {
                    "BECharacterData"
                } else {
                    "VBCharacterData"
                }
                db.rawQuery(
                    "SELECT * FROM " + extra + " WHERE id=?",
                    arrayOf(id.toString())
                ).use { extraRow ->
                    if (extraRow.moveToFirst()) {
                        if (extra == "BECharacterData") {
                            stats.setTrainedBp(extraRow.num("trainingBp"))
                                .setTrainedHp(extraRow.num("trainingHp"))
                                .setTrainedAp(extraRow.num("trainingAp"))
                                .setTrainingTimeRemainingInSeconds(
                                    extraRow.num("remainingTrainingTimeInMinutes").toLong() * 60L
                                )
                        } else {
                            stats.setGeneration(extraRow.num("generation"))
                                .setTotalTrophies(extraRow.num("totalTrophies"))
                        }
                    }
                }
                val proto = Character.newBuilder()
                    .setCardName(cardName)
                    .setCardId(c.num("vbCardId"))
                    .setCharacterStats(stats)
                    .setSettings(
                        Character.Settings.newBuilder()
                            .setTrainingInBackground(false)
                            .setAllowedBattles(Character.Settings.AllowedBattles.CARD_ONLY)
                    )
                    .setTransferCard(metadata)
                    .setTransferSpecies(species)

                db.rawQuery(
                    """SELECT d.name, cc.charaIndex, cc.stage
                       FROM TransformationHistory t
                       JOIN CardCharacter cc ON cc.id=t.stageId
                       JOIN Card d ON d.id=cc.cardId
                       WHERE t.monId=? ORDER BY t.transformationDate ASC, t.id ASC""",
                    arrayOf(id.toString())
                ).use { history ->
                    while (history.moveToNext()) {
                        proto.addTransformationHistory(
                            Character.TransformationEvent.newBuilder()
                                .setCardName(history.getString(0))
                                .setSlotId(history.getInt(1))
                                .setPhase(history.getInt(2))
                        )
                    }
                }
                proto.build()
            }
        }

    private suspend fun sendAndConfirm(character: Character): Boolean {
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        require(nodes.isNotEmpty()) {
            "ไม่พบนาฬิกาที่เชื่อมต่อกับมือถือ"
        }
        val messageClient = Wearable.getMessageClient(this)
        val receipt = CompletableDeferred<Boolean>()
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == "vitalwear/VBHELPER_FULL_RESULT" &&
                nodes.any { it.id == event.sourceNodeId } &&
                !receipt.isCompleted
            ) {
                receipt.complete(event.data.firstOrNull()?.toInt() == 1)
            }
        }
        messageClient.addListener(listener).await()
        try {
            val channelClient = Wearable.getChannelClient(this)
            for (node in nodes) {
                val channel = channelClient.openChannel(
                    node.id, "vitalwear/VBHELPER_FULL_CHARACTER"
                ).await()
                try {
                    channelClient.getOutputStream(channel).await().use { output ->
                        character.writeTo(output)
                        output.flush()
                    }
                } finally {
                    channelClient.close(channel).await()
                }
                val result = withTimeoutOrNull(35000L) {
                    receipt.await()
                }
                if (result != null) return result
            }
            return false
        } finally {
            messageClient.removeListener(listener).await()
        }
    }
}
''',encoding="utf-8")

# Register activity in companion only; don't touch existing database.
p=root/"companion/src/main/AndroidManifest.xml"
replace(p,
    '        <activity android:name="com.github.cfogrady.vitalwear.card.ImportCardActivity"/>',
    '        <activity android:name="com.github.cfogrady.vitalwear.main.VbHelperBackupImportActivity" android:exported="false"/>\n'+
    '        <activity android:name="com.github.cfogrady.vitalwear.card.ImportCardActivity"/>')
p=phone/"main/MainActivity.kt"
replace(p,
    '''            Button(onClick = {
                startActivity(Intent(applicationContext, ImportCardActivity::class.java))
            }) {''',
    '''            Button(onClick = {
                startActivity(Intent(this@MainActivity, VbHelperBackupImportActivity::class.java))
            }) {
                Text(text = "Import real Digimon from VB Helper backup", color = Color.Cyan)
            }
            Button(onClick = {
                startActivity(Intent(applicationContext, ImportCardActivity::class.java))
            }) {''')

for module in ("app","companion"):
    p=root/module/"build.gradle.kts"
    replace(p,"versionCode = 87","versionCode = 88")
    replace(p,
        'versionName = "0.8.7-InstallAndNfcFix"',
        'versionName = "0.8.8-FullSprites-VBHelperBackup"')
# Contract regression checks: fail build if real art is silently discarded.
activity=(wear/"transfer/TransferActivity.kt").read_text()
assert "if (this.hasTransferSpecies()) builder.setTransferSpecies(this.transferSpecies)" in activity
assert "if (this.hasTransferCard()) builder.setTransferCard(this.transferCard)" in activity
meta=(wear/"transfer/TransferEmbeddedMetadata.kt").read_text()
assert "if (!hasTransferCard() || !hasTransferSpecies()) return null" in meta
assert "speciesEntityDao.update(importedSpecies)" in meta
assert "ensureGenericTransferSpeciesExists(" not in meta.split("internal fun Character.resolveImportedCardMetaOrPlaceholder(",1)[1].split("private fun Character.ensureTransferSpeciesExists(",1)[0]
assert 'applicationId = "dev.mikeki.vitalwear.gw8.stable"' in (root/"app/build.gradle.kts").read_text()
assert 'applicationId = "dev.mikeki.vitalwear.gw8.stable"' in (root/"companion/build.gradle.kts").read_text()
assert "signature" not in "placeholder"  # explicit placeholder cannot claim verification
print("v0.8.8: full metadata survives sanitizer; fake art blocked; legacy package preserved")
print("v0.8.8: exact original sprites + paired acknowledgement, stable in-place update retained")
