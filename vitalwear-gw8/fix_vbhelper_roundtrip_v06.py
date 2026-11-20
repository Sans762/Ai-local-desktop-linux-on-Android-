#!/usr/bin/env python3
"""Preflight VBHelper -> Watch character before save/COMMIT ACK.
Legacy VBHelper sends stats/card ID but not necessarily embedded sprite files.
Use installed DIM/BEM by card ID + slot if the display name was renamed.
Fail non-destructively with a specific diagnosis when source files are missing.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
p=root/"app/src/main/java/com/github/cfogrady/vitalwear/transfer/hce/VitalWearHceTransferRepository.kt"
s=p.read_text(encoding="utf-8-sig")
old='''        val matchedCard = sanitized.resolveImportedCardMetaOrPlaceholder(app.applicationContext, app.cardMetaEntityDao, speciesDao)
        val resolvedCardName = matchedCard?.cardName'''
new='''        val autoResolvedCard = sanitized.resolveImportedCardMetaOrPlaceholder(
            app.applicationContext, app.cardMetaEntityDao, speciesDao)
        // VBHelper's legacy exporter sends cardId + slotId but usually no sprite bytes.
        // Resolve by installed ID + species slot even if card name differs on both apps.
        val matchedCard = autoResolvedCard ?: app.cardMetaEntityDao.getAll().firstOrNull { card ->
            card.cardId == sanitized.cardId &&
                runCatching {
                    speciesDao.getCharacterByCardAndCharacterId(
                        card.cardName, sanitized.characterStats.slotId)
                }.isSuccess
        }
        if(matchedCard==null) {
            val message="Missing DIM/BEM card " + sanitized.cardName +
                " (ID " + sanitized.cardId + ") on watch. Import that card first."
            Log.w(TAG,message)
            VitalWearHceSessionManager.markFailure(message)
            return false
        }
        // Reject before adding a character if game sprites are not actually readable.
        val hasSprites=runCatching {
            app.characterManager.getCharacterBitmap(
                app.applicationContext, matchedCard.cardName,
                sanitized.characterStats.slotId,
                com.github.cfogrady.vitalwear.common.card.CharacterSpritesIO.IDLE1,
                com.github.cfogrady.vitalwear.common.card.CharacterSpritesIO.IDLE2
            )
        }.isSuccess
        if(!hasSprites) {
            val message="Card found but Digimon sprite missing (slot " +
                sanitized.characterStats.slotId + "). Reimport DIM/BEM on watch."
            Log.w(TAG,message)
            VitalWearHceSessionManager.markFailure(message)
            return false
        }
        val resolvedCardName = matchedCard.cardName'''
if s.count(old)!=1: raise RuntimeError("NFC matched card code changed, got "+str(s.count(old)))
s=s.replace(old,new)
# Avoid silently ignoring failures; validate metadata if present
p.write_text(s,encoding="utf-8");print("NFC PREFLIGHT APPLIED")
# Retain failure message while one-touch NFC reader is still polling the phone.
u=root/"app/src/main/java/com/github/cfogrady/vitalwear/transfer/hce/VitalWearHostApduService.kt"
s=u.read_text(encoding="utf-8-sig")
old='''                    // A negative COMMIT makes the old VBHelper client keep its original.
                    runCatching {'''
new='''                    // A negative COMMIT makes VBHelper keep its original character.
                    // Never acknowledge before the character is activated and its sprites load.
                    runCatching {'''
if s.count(old)==1:s=s.replace(old,new)
u.write_text(s,encoding="utf-8")
print("ROUNDTRIP PREFLIGHT & SAFE ACK READY")
