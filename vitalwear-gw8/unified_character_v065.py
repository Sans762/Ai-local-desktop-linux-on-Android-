#!/usr/bin/env python3
"""Rebuild character selection menu with actual stored characters/actions."""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
p=root/"app/src/main/java/com/github/cfogrady/vitalwear/character/activity/CharacterSelection.kt"
s=p.read_text(encoding="utf-8-sig")
begin=s.index("@Composable\nfun CharacterSelection(controller: CharacterSelectionController)")
end=s.index("\ninterface PagedCharacterSelectionMenuController",begin)
new=r'''@Composable
fun CharacterSelection(controller:CharacterSelectionController) {
    val context=LocalContext.current
    var characters by remember {mutableStateOf<List<CharacterPreview>?>(null)}
    var selected by remember {mutableStateOf<CharacterPreview?>(null)}
    val creating by controller.loadingNewCharacterState.collectAsState()
    LaunchedEffect(creating) {
        characters=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            controller.getPreviewCharacters()
        }
    }
    val known=characters
    if(known==null || creating) {
        Loading{}
    } else if(selected==null) {
        com.github.cfogrady.vitalwear.main.VbChoicePage(
            "DIGIMON",
            listOf(com.github.cfogrady.vitalwear.main.VbActionEntry("NEW"){
                controller.newCharacter()
            }) + known.map { character ->
                com.github.cfogrady.vitalwear.main.VbActionEntry(
                    character.cardName.take(11)
                ){selected=character}
            } + com.github.cfogrady.vitalwear.main.VbActionEntry("RETURN"){
                (context as? android.app.Activity)?.finish()
            },
            onBack={(context as? android.app.Activity)?.finish()}
        )
    } else {
        val pet=selected!!
        com.github.cfogrady.vitalwear.main.VbChoicePage(
            "DIGIMON",
            listOf(
                com.github.cfogrady.vitalwear.main.VbActionEntry("SELECT"){
                    controller.swapToCharacter(pet)
                },
                com.github.cfogrady.vitalwear.main.VbActionEntry("SUPPORT"){
                    controller.setSupportCharacter(pet)
                    selected=null
                },
                com.github.cfogrady.vitalwear.main.VbActionEntry("DELETE"){
                    controller.deleteCharacter(pet)
                    characters=known.filterNot{it.cardName==pet.cardName && it.slotId==pet.slotId}
                    selected=null
                },
                com.github.cfogrady.vitalwear.main.VbActionEntry("RETURN"){
                    selected=null
                }
            ),
            onBack={selected=null}
        )
    }
}
'''
s=s[:begin]+new+s[end:]
if "import androidx.compose.runtime.LaunchedEffect" not in s:
    s=s.replace("import androidx.compose.runtime.Composable",
                "import androidx.compose.runtime.Composable\\nimport androidx.compose.runtime.LaunchedEffect")
p.write_text(s,encoding="utf-8")
print("WORKING DIGIMON COLLECTION SELECT/SUPPORT/DELETE MENU REPLACED")
