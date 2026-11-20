#!/usr/bin/env python3
"""Align real Adventure and VB Helper transfer selection with approved UI.
Does not alter NFC packets, card commit semantics, battles or save data.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
src=root/"app/src/main/java/com/github/cfogrady/vitalwear"
def patch(path,old,new):
    p=src/path
    s=p.read_text(encoding="utf-8-sig")
    if s.count(old)!=1:
        raise RuntimeError(f"{path}: expected 1 of {old[:40]!r}, got {s.count(old)}")
    p.write_text(s.replace(old,new),encoding="utf-8")
    print("PATCH",path)
# Functional choice of SEND/RECEIVE still goes through the unchanged transfer engine.
transfer=src/"transfer/TransferScreen.kt"
s=transfer.read_text(encoding="utf-8-sig")
start=s.index("@Composable\nfun SelectSendOrReceive(")
end=s.index("\n@Composable\nfun TransferResult(",start)
replacement='''@Composable
fun SelectSendOrReceive(onSelect:(SendOrReceive)->Unit={}) {
    val context=androidx.compose.ui.platform.LocalContext.current
    com.github.cfogrady.vitalwear.main.VbChoicePage(
        "CONNECT",
        listOf(
            com.github.cfogrady.vitalwear.main.VbActionEntry("SEND") {
                onSelect(SendOrReceive.SEND)
            },
            com.github.cfogrady.vitalwear.main.VbActionEntry("RECEIVE") {
                onSelect(SendOrReceive.RECEIVE)
            },
            com.github.cfogrady.vitalwear.main.VbActionEntry("VB HELPER") {
                onSelect(SendOrReceive.RECEIVE)
            },
            com.github.cfogrady.vitalwear.main.VbActionEntry("RETURN") {
                (context as? android.app.Activity)?.finish()
            }
        ),
        onBack={(context as? android.app.Activity)?.finish()}
    )
}
'''
s=s[:start]+replacement+s[end:]
transfer.write_text(s,encoding="utf-8")
print("PIXEL VB HELPER TRANSFER SELECTOR")

# Adventure uses actual imported cards, stage unlocks, stage goal, boss bitmap.
a=src/"adventure/AdventureMenuScreen.kt"
s=a.read_text(encoding="utf-8-sig")
start=s.index("        AdventureMenuState.CardSelection -> CardSelection(controller) {")
end=s.index("\n        AdventureMenuState.ZoneSelection ->",start)
new='''        AdventureMenuState.CardSelection -> {
            var choices by remember { mutableStateOf<List<CardMetaEntity>?>(null) }
            LaunchedEffect(Unit) {
                choices=kotlinx.coroutines.withContext(Dispatchers.IO) {
                    controller.loadCards()
                }
            }
            if(choices==null) Loading{} else {
                val context=LocalContext.current
                com.github.cfogrady.vitalwear.main.VbChoicePage(
                    "ADVENTURE",
                    choices!!.map { card ->
                        com.github.cfogrady.vitalwear.main.VbActionEntry(
                            card.cardName.take(11)
                        ) {
                            adventureMenuState=AdventureMenuState.Loading
                            cardSelected=card
                            CoroutineScope(Dispatchers.IO).launch {
                                backgrounds=controller.loadCardBackgrounds(card.cardName)
                                cardIcon=controller.loadCardIcon(card.cardName)
                                adventureMenuState=AdventureMenuState.ZoneSelection
                            }
                        }
                    } + com.github.cfogrady.vitalwear.main.VbActionEntry("RETURN") {
                        onFinish()
                    },
                    onBack=onFinish
                )
            }
        }'''
s=s[:start]+new+s[end:]
start=s.index("@Composable\nfun ZoneSelection(")
end=s.index("\nprivate fun trimTransparentHorizontalPadding",start)
s=s[:start]+'''@Composable
fun ZoneSelection(
    controller:ZoneSelectionController,
    cardMetaEntity:CardMetaEntity,
    backgrounds:List<Bitmap>,
    cardIcon:Bitmap,
    onZoneSelected:(AdventureEntity)->Unit
) {
    var stages by remember(cardMetaEntity.cardName) {
        mutableStateOf<List<AdventureEntity>?>(null)
    }
    LaunchedEffect(cardMetaEntity.cardName) {
        val max=controller.getMaxAdventureForActiveCharacter(cardMetaEntity.cardName)
        val all=controller.getAdventuresForCard(cardMetaEntity.cardName)
        stages=all.take((max+1).coerceAtLeast(0).coerceAtMost(all.size))
    }
    val context=LocalContext.current
    val options=stages
    if(options==null) Loading{} else {
        com.github.cfogrady.vitalwear.main.VbChoicePage(
            "ADVENTURE",
            options.map { area ->
                com.github.cfogrady.vitalwear.main.VbActionEntry(
                    "AREA "+(area.adventureId+1)
                ){onZoneSelected(area)}
            } + com.github.cfogrady.vitalwear.main.VbActionEntry("RETURN") {
                (context as? android.app.Activity)?.finish()
            },
            onBack={(context as? android.app.Activity)?.finish()}
        )
    }
}
''' + s[end:]
start=s.index("@Composable\nfun ZoneConfirm(")
end=s.index("\n@Preview(",start)
s=s[:start]+'''@Composable
fun ZoneConfirm(
    controller:ZoneConfirmController,
    cardMetaEntity:CardMetaEntity,
    adventureEntity:AdventureEntity,
    backgrounds:List<Bitmap>,
    onConfirm:()->Unit
) {
    var boss by remember(adventureEntity) {mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(cardMetaEntity.cardName,adventureEntity) {
        boss=if(adventureEntity.hiddenBoss)controller.adventureBitmaps.hiddenImage
            else controller.loadBossCharacterBitmap(
                cardMetaEntity.cardName,adventureEntity.characterId)
    }
    val context=LocalContext.current
    if(boss==null) Loading{} else {
        com.github.cfogrady.vitalwear.main.VbDetailPage(
            "ADVENTURE",
            "AREA "+(adventureEntity.adventureId+1),
            listOf("STEP GOAL" to adventureEntity.steps.toString(),
                "CARD" to cardMetaEntity.cardName.take(9),
                "READY" to "TAP NEXT"),
            sprite=boss,
            onBack={(context as? android.app.Activity)?.finish()},
            onNext=onConfirm
        )
    }
}
''' + s[end:]
a.write_text(s,encoding="utf-8")
print("ACTUAL ADVENTURE CARD/STAGE/BOSS MENU PIXEL UI")

# Store explicit game release version on both apps for consistent update-over behavior.
for module in ("app","companion"):
    g=root/module/"build.gradle.kts"
    s=g.read_text(encoding="utf-8-sig")
    if s.count("versionCode = 64")!=1 or s.count('versionName = "0.6.4-HomeScenes"')!=1:
        raise RuntimeError("Unexpected "+module+" version")
    g.write_text(s.replace("versionCode = 64","versionCode = 65")
        .replace('versionName = "0.6.4-HomeScenes"','versionName = "0.6.5-UI-Unified"'),
        encoding="utf-8")
print("UNIFIED DEEP MENU CHOICES READY")
