#!/usr/bin/env python3
"""0.8.6: Accept VB Helper's protobuf-only NFC Digimon without losing original data."""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
app=root/"app/src/main/java/com/github/cfogrady/vitalwear"

p=app/"transfer/TransferEmbeddedMetadata.kt"
s=p.read_text()
start=s.index("internal fun Character.resolveImportedCardMetaOrPlaceholder(")
end=s.index("\nprivate fun Character.ensureTransferSpeciesExists(",start)
new=r'''internal fun Character.resolveImportedCardMetaOrPlaceholder(
    context: Context,
    cardMetaEntityDao: CardMetaEntityDao,
    speciesEntityDao: SpeciesEntityDao,
): CardMetaEntity? {
    if (!hasCharacterStats() || characterStats.slotId < 0) return null
    val matched = resolveImportedCardMeta(cardMetaEntityDao, speciesEntityDao)
    if (matched != null) {
        ensureTransferSpeciesExists(context, matched, speciesEntityDao)
        ensureGenericTransferSpeciesExists(context, matched, speciesEntityDao)
        return matched
    }
    // VB Helper's NFC export omits TransferCard and TransferSpecies.
    // Generate an isolated local record without touching genuine card data.
    val incomingId = if (hasTransferCard() && transferCard.cardId > 0)
        transferCard.cardId else cardId
    val baseName = if (hasTransferCard() && transferCard.cardName.isNotBlank())
        transferCard.cardName else cardName.ifBlank { "Transferred Card" }
    val placeholderName = buildPlaceholderCardName(cardMetaEntityDao, baseName, incomingId)
    val isBem = if (hasTransferCard()) transferCard.isBem
        else characterStats.deviceType ==
            Character.CharacterStats.TransferDeviceType.TRANSFER_DEVICE_TYPE_BE
    val placeholder = cardMetaEntityDao.getByName(placeholderName) ?: CardMetaEntity(
        cardName = placeholderName,
        cardId = incomingId,
        cardChecksum = 0,
        cardType = if (isBem) CardType.BEM else CardType.DIM,
        franchise = if (hasTransferCard()) transferCard.franchise else 0,
        maxAdventureCompletion = null,
    ).also { cardMetaEntityDao.insert(it) }
    ensureTransferSpeciesExists(context, placeholder, speciesEntityDao)
    ensureGenericTransferSpeciesExists(context, placeholder, speciesEntityDao)
    return cardMetaEntityDao.getByName(placeholderName) ?: placeholder
}

/** Temporary locally generated artwork is never written over real sprites. */
private fun Character.ensureGenericTransferSpeciesExists(
    context: Context,
    card: CardMetaEntity,
    dao: SpeciesEntityDao,
) {
    val slot = characterStats.slotId
    val existing = runCatching {
        dao.getCharacterByCardAndCharacterId(card.cardName, slot)
    }.getOrNull()
    if (existing != null) return

    val dir = buildTransferSpriteDir(card.cardName, card.cardId, slot)
    val pixel = genericTransferSprite()
    for (kind in listOf(
        CharacterSpritesIO.NAME, CharacterSpritesIO.IDLE1,
        CharacterSpritesIO.IDLE2, CharacterSpritesIO.WALK1,
        CharacterSpritesIO.WALK2, CharacterSpritesIO.RUN1,
        CharacterSpritesIO.RUN2, CharacterSpritesIO.TRAIN1,
        CharacterSpritesIO.TRAIN2, CharacterSpritesIO.WIN,
        CharacterSpritesIO.DOWN, CharacterSpritesIO.ATTACK,
        CharacterSpritesIO.DODGE, CharacterSpritesIO.SPLASH
    )) saveTransferSprite(context, dir, kind, pixel, 24, 24)

    dao.insertAll(listOf(SpeciesEntity(
        cardName = card.cardName,
        characterId = slot,
        phase = transformationHistoryList.lastOrNull()?.phase?.coerceAtLeast(1) ?: 1,
        attribute = 0,
        type = 0,
        attackId = 0,
        criticalAttackId = 0,
        dpStars = 0,
        bp = 100,
        hp = 100,
        ap = 100,
        battlePool1 = 0,
        battlePool2 = 0,
        battlePool3 = 0,
        spriteDirName = dir,
        raised = true,
    )))
}

/** Original placeholder art: RGB565 little endian, pure green transparent. */
private fun genericTransferSprite(): ByteArray {
    val output = ByteArray(24 * 24 * 2)
    for (y in 0 until 24) for (x in 0 until 24) {
        val dx = x - 12
        val dy = y - 12
        val body = dx * dx * 2 + dy * dy * 3 < 185
        val antenna = kotlin.math.abs(dx) == 4 && y in 2..8
        val eye = kotlin.math.abs(dx) in 4..5 && y in 11..12
        val mouth = kotlin.math.abs(dx) <= 3 && y == 17
        val color = when {
            eye -> 0xFFFF
            mouth -> 0
            body || antenna -> 0x39FF
            else -> 0x07E0
        }
        val offset = (y * 24 + x) * 2
        output[offset] = color.toByte()
        output[offset + 1] = (color shr 8).toByte()
    }
    return output
}
'''
s=s[:start]+new+s[end:]
p.write_text(s)
print("PATCH: accept NFC protobuf with missing card and species art")

p=app/"transfer/hce/VitalWearHceTransferRepository.kt"
s=p.read_text()
a=s.index("        if (validationError != null) {")
b=s.index("\n        val importCharacter",a)
s=s[:a]+'''        if (validationError != null) {
            Log.e(TAG, "Import failed: $validationError")
            return false
        }
''' + s[b:]
old="        val incoming = Character.parseFrom(payload)\n        val sanitized = incoming.sanitizeForImport()"
new='''        val incoming = Character.parseFrom(payload)
        if (!incoming.hasCharacterStats() || incoming.characterStats.slotId < 0) {
            Log.e(TAG, "Invalid NFC protobuf")
            return false
        }
        val sanitized = incoming.sanitizeForImport()'''
assert s.count(old)==1
p.write_text(s.replace(old,new))
for mod in ("app","companion"):
    p=root/mod/"build.gradle.kts"
    s=p.read_text()
    assert s.count("versionCode = 85")==1
    assert s.count('versionName = "0.8.5-Clean-VBHelper-SafeExport"')==1
    s=s.replace("versionCode = 85","versionCode = 86")
    s=s.replace('versionName = "0.8.5-Clean-VBHelper-SafeExport"',
                'versionName = "0.8.6-Clean-VBHelper-ReceiveFix"')
    p.write_text(s)
print("PATCH: version 86 paired watch + companion")
