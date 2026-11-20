#!/usr/bin/env python3
"""2026-11-20: Repair actual overflowing round-watch inner screens.

Source VitalWear still drew Stats with 3.0/3.5em on a full circular canvas.
Replace Stats and Training with compact portrait PixelKit screens and put
adventure/background selection within the same centered portrait viewport.
No changes to NFC/game-save implementation.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base=Path("app/src/main/java/com/github/cfogrady/vitalwear")

def write(rel,data):
    p=root/base/rel
    assert p.exists(), "Expected source file missing: "+str(p)
    p.write_text(data, encoding="utf-8")
    print("COMPACT REWRITE:",rel)

def patch(rel,old,new,expected=1):
    p=root/base/rel
    s=p.read_text(encoding="utf-8-sig")
    found=s.count(old)
    if found!=expected:
        raise RuntimeError(f"{rel}: expected {expected} instances of {old!r}; found {found}")
    p.write_text(s.replace(old,new),encoding="utf-8")
    print("COMPACT PATCH:",rel,found)

write("stats/StatsMenuActivity.kt",r'''package com.github.cfogrady.vitalwear.stats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.character.VBCharacter
import com.github.cfogrady.vitalwear.common.character.CharacterSprites
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText

/** All status pages stay inside a centered, portrait-shaped game viewport. */
class StatsMenuActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app=application as VitalWearApp
            val partner=remember { app.characterManager.getCurrentCharacter() }
            if(partner==null) {
                PixelShell { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    PixelText("NO PARTNER",height=11.dp)
                } }
            } else StatusPages(partner,app)
        }
    }
}

@Composable
private fun StatusPages(partner:VBCharacter,app:VitalWearApp) {
    val total=3+partner.transformationOptions.size
    val pager=rememberPagerState(pageCount={total})
    val sprite=partner.characterSprites.sprites[CharacterSprites.IDLE_1]
    val backdrop=remember { app.backgroundManager.selectedBackground.value }
    PixelShell {
        if(backdrop!=null) {
            Image(backdrop.asImageBitmap(),contentDescription="Current game background",
                modifier=Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds,
                filterQuality=FilterQuality.None)
        }
        Box(Modifier.fillMaxSize().background(Color(0xD9031323)))
        Column(Modifier.fillMaxSize().padding(horizontal=7.dp,vertical=10.dp),
            horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically) {
                PixelText("STATUS",height=12.dp,color=PixelPalette.cyan)
                PixelText((pager.currentPage+1).toString()+"/"+total,height=8.dp,color=PixelPalette.lime)
            }
            Spacer(Modifier.height(5.dp))
            Image(sprite.asImageBitmap(),contentDescription="Current Digimon",
                modifier=Modifier.size(35.dp),filterQuality=FilterQuality.None,
                contentScale=ContentScale.Fit)
            VerticalPager(state=pager,modifier=Modifier.weight(1f)) {page ->
                Column(Modifier.fillMaxSize().padding(top=7.dp),
                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    when(page) {
                        0 -> {
                            val mins=(partner.characterStats.trainingTimeRemainingInSeconds/60)
                                .coerceAtLeast(0)
                            val limit=if(mins>60) (mins/60).toString()+"h" else mins.toString()+"m"
                            PixelStatRow("LIMIT",limit)
                            PixelStatRow("PHASE",(partner.speciesStats.phase+1).toString())
                            PixelStatRow("TYPE",partner.attributeName())
                        }
                        1 -> {
                            PixelStatRow("BP",partner.speciesStats.displayBp())
                            PixelStatRow("HP",partner.speciesStats.displayHp())
                            PixelStatRow("AP",partner.speciesStats.displayAp())
                            PixelStatRow("TRAIN BP",partner.characterStats.trainedBp.toString())
                        }
                        2 -> {
                            PixelStatRow("VITALS",partner.characterStats.vitals.toString())
                            PixelStatRow("FIGHTS",partner.characterStats.currentPhaseBattles.toString())
                            PixelStatRow("WIN RATE",partner.characterStats.currentPhaseWinRatio().toString()+"%")
                            PixelStatRow("PP",partner.characterStats.trainedPP.toString())
                        }
                        else -> {
                            val next=partner.transformationOptions[page-3]
                            val locked=(next.requiredAdventureCompleted?:-1)>
                                (partner.cardMeta.maxAdventureCompletion?:-1)
                            PixelText(if(locked)"NEXT LOCKED" else "NEXT EVOLVE",
                                height=9.dp,color=PixelPalette.lime)
                            PixelStatRow("VITALS",next.requiredVitals.toString())
                            PixelStatRow("FIGHTS",next.requiredBattles.toString())
                            PixelStatRow("WIN RATE",next.requiredWinRatio.toString()+"%")
                            PixelStatRow("PP",next.requiredPp.toString())
                        }
                    }
                }
            }
            PixelText("SWIPE UP / DOWN",height=7.dp,color=PixelPalette.muted)
        }
    }
}

private fun VBCharacter.attributeName():String = when(speciesStats.attribute) {
    0->"NONE"
    1->"VIRUS"
    2->"DATA"
    3->"VACCINE"
    4->"FREE"
    else->"UNKNOWN"
}

@Composable
private fun PixelStatRow(label:String,value:String) {
    Row(modifier=Modifier.fillMaxWidth().height(21.dp)
            .background(PixelPalette.panel).padding(horizontal=5.dp),
        horizontalArrangement=Arrangement.SpaceBetween,
        verticalAlignment=Alignment.CenterVertically) {
        PixelText(label,height=8.dp,color=PixelPalette.cyan)
        PixelText(value.take(11),height=9.dp,color=PixelPalette.white)
    }
}
''')

write("training/TrainingMenuActivity.kt",r'''package com.github.cfogrady.vitalwear.training

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp
import com.github.cfogrady.vitalwear.common.util.ActivityHelper
import com.github.cfogrady.vitalwear.composable.pixel.PixelButton
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Functional vertical pixel menu; launches original training activities. */
class TrainingMenuActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val trainingLauncher=ActivityHelper(this)
            .getActivityLauncherWithResultHandling(TrainingActivity::class.java) { result ->
                if(result.data?.extras?.getBoolean(TrainingActivity.FINISH_TO_MENU,true)!=true)
                    finish()
            }
        setContent {
            val types=remember {
                listOf(TrainingType.SQUAT,TrainingType.CRUNCH,TrainingType.PUNCH,TrainingType.DASH)
            }
            val scroll=rememberLazyListState()
            val scope=rememberCoroutineScope()
            val focus=remember { FocusRequester() }
            var selected by remember { mutableIntStateOf(0) }
            var rotary by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            PixelShell {
                Column(Modifier.fillMaxSize().padding(horizontal=7.dp,vertical=12.dp)) {
                    PixelText("TRAINING",height=13.dp,color=PixelPalette.cyan)
                    LazyColumn(
                        state=scroll,
                        modifier=Modifier.fillMaxSize().padding(top=8.dp)
                            .onRotaryScrollEvent {
                                rotary+=it.verticalScrollPixels
                                if(abs(rotary)>23f) {
                                    selected=(selected+if(rotary>0)1 else -1)
                                        .coerceIn(0,types.lastIndex)
                                    rotary=0f
                                    scope.launch {scroll.animateScrollToItem(selected)}
                                }
                                true
                            }.focusRequester(focus).focusable(),
                        verticalArrangement=Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(types) { index,type ->
                            PixelButton(type.name+" "+type.durationSeconds+" SEC",
                                selected=selected==index,onClick={
                                    selected=index
                                    trainingLauncher.invoke { intent ->
                                        intent.putExtra(TrainingActivity.TRAINING_TYPE,type)
                                    }
                                })
                        }
                    }
                }
            }
        }
    }
}
''')

# Original inner adventure pages still used 3em and 5em labels on a FULL circle.
# Keep game graphics and navigation, but constrain each screen to a portrait viewport.
patch("adventure/AdventureMenuScreen.kt",
    "controller.vitalBoxFactory.VitalBox {",
    "com.github.cfogrady.vitalwear.composable.pixel.PixelShell {",expected=4)
patch("adventure/AdventureMenuScreen.kt","fontSize = 3.em","fontSize = 1.35.em",expected=3)
patch("adventure/AdventureMenuScreen.kt","fontSize = 5.em","fontSize = 1.9.em",expected=1)

# Backgrounds are still tappable; avoid oversized full-circle image buttons.
patch("background/BackgroundSelectionActivity.kt",
    "vitalBoxFactory.VitalBox {",
    "com.github.cfogrady.vitalwear.composable.pixel.PixelShell {",expected=1)

# The original "NEW" screen used a 6em word larger than the round screen's usable center.
patch("character/activity/CharacterSelection.kt",
    'Text(text = "NEW", fontSize = 6.em',
    'Text(text = "NEW", fontSize = 1.45.em',expected=1)

print("COMPACT INNER MENUS PATCH APPLIED")
