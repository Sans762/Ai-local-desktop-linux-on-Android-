#!/usr/bin/env python3
"""0.6.2: readable portrait UI using actual VitalWear game/firmware data.
Applied AFTER pixel_ui_v06.py and compact_inner_menus_v061.py.
Never bundles or modifies user-supplied VBBE firmware; import remains on-device.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base = Path("app/src/main/java/com/github/cfogrady/vitalwear")

def patch(rel,old,new,expected=1):
    path=root/base/rel
    data=path.read_text(encoding="utf-8-sig")
    found=data.count(old)
    if found!=expected:
        raise RuntimeError(f"{rel}: expected {expected} of {old[:90]!r}; found {found}")
    path.write_text(data.replace(old,new), encoding="utf-8")
    print("FIX",rel,old[:43])

def write(rel,data):
    path=root/base/rel
    if not path.exists(): raise RuntimeError("Missing source "+str(path))
    path.write_text(data,encoding="utf-8")
    print("REWRITE",rel)

# True circular safe zone: sqrt(.60^2 + .78^2)=.984 <1.
# Prior .78 x .87 was overflowing corners of watch's circular active glass.
patch("composable/pixel/PixelKit.kt",
'''        val w=maxWidth*0.78f
        val h=maxHeight*0.87f''',
'''        val diameter=minOf(maxWidth,maxHeight)
        val w=diameter*0.60f
        val h=diameter*0.78f''')
patch("composable/pixel/PixelKit.kt",
'''    val muted=Color(0xFF799CB5)''',
'''    val muted=Color(0xFFB4D2DF)''')
patch("composable/pixel/PixelKit.kt",
'''            textSize=12f''',
'''            textSize=16f''')
patch("composable/pixel/PixelKit.kt",
'''        Bitmap.createBitmap(w,16,Bitmap.Config.ARGB_8888).also {
            AndroidCanvas(it).drawText(label,1f,12f,paint)
        }
    }
    val width=(height.value*bitmap.width/16f).dp''',
'''        Bitmap.createBitmap(w,21,Bitmap.Config.ARGB_8888).also {
            AndroidCanvas(it).drawText(label,1f,17f,paint)
        }
    }
    val width=(height.value*bitmap.width/21f).dp''')
patch("composable/pixel/PixelKit.kt",
'''    Box(modifier.fillMaxWidth().height(34.dp)''',
'''    Box(modifier.fillMaxWidth().height(36.dp)''')
patch("composable/pixel/PixelKit.kt",
'''        PixelText(label,height=11.dp,color=foreground)''',
'''        PixelText(label,height=14.dp,color=foreground)''')
# Original firmware graphics are read from firmwareManager at runtime,
# not copied to the public repository.
write("main/VbMenuScreen.kt", r'''package com.github.cfogrady.vitalwear.main

import android.graphics.Bitmap
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.composable.pixel.PixelButton
import com.github.cfogrady.vitalwear.composable.pixel.PixelIcon
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText
import com.github.cfogrady.vitalwear.heartrate.SensorDiagnosticsActivity
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class MenuItem(
    val label:String,
    val bitmap:Bitmap?,
    val available:Boolean=true,
    val open:()->Unit
)

/** Firmware icon is cropped from original 80x80 VB menu tile.
 *  Cropping avoids the tiny unreadable caption baked into the original sprite.
 */
@Composable
private fun FirmwarePixelIcon(bitmap:Bitmap?,fallback:String) {
    if(bitmap==null) {
        PixelIcon(fallback,PixelPalette.cyan,Modifier)
        return
    }
    val crop=remember(bitmap) {
        val x=0
        val h=(bitmap.height*0.70f).toInt().coerceIn(1,bitmap.height)
        Bitmap.createBitmap(bitmap,x,0,bitmap.width,h)
    }
    Image(bitmap=crop.asImageBitmap(),contentDescription=fallback,
        modifier=Modifier.size(23.dp),contentScale=ContentScale.Fit,
        filterQuality=FilterQuality.None)
}

/** Visual reference: seven outlined cyan menu entries, neon-lime active row.
 *  UI scrolls when physical round display cannot fit seven 36dp touch targets.
 *  Each visible action invokes the existing real activity, not a stub.
 */
@Composable
fun VbMenuScreen(controller:MainScreenController) {
    val ctx=LocalContext.current
    val fw=(ctx.applicationContext as VitalWearApp).firmwareManager.getFirmware().value
    val icons=fw?.menuBitmaps
    val partner by controller.activePartner.collectAsStateWithLifecycle()
    val hasPet=partner!=null
    val awake=partner?.characterStats?.sleeping!=true
    val evolved=(partner?.speciesStats?.phase?:0)>1
    val items=listOf(
        MenuItem("STATUS",icons?.statsIcon,hasPet){controller.launchStatsMenuActivity()},
        MenuItem("TRAINING",icons?.trainingIcon,hasPet&&awake){controller.launchTrainingMenuActivity()},
        MenuItem("ADVENTURE",icons?.adventureIcon,hasPet&&awake&&evolved){controller.launchAdventureActivity()},
        MenuItem("BATTLE",null,hasPet&&awake&&evolved){controller.launchBattleActivity()},
        // ITEMS is not implemented in the game engine: use real CHARACTER screen instead.
        MenuItem("CHARACTER",icons?.characterSelectorIcon){controller.launchCharacterSelectionActivity()},
        MenuItem("CONNECT",icons?.connectIcon){controller.launchTransferActivity()},
        MenuItem("SENSOR",null){ctx.startActivity(Intent(ctx,SensorDiagnosticsActivity::class.java))},
        MenuItem("SETTINGS",icons?.settingsIcon){controller.launchSettingsActivity()},
        MenuItem(if(awake)"SLEEP" else "WAKE",icons?.sleepIcon,hasPet){controller.toggleSleep()}
    )
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val focus=remember { FocusRequester() }
    var selected by remember { mutableIntStateOf(0) }
    var delta by remember { mutableFloatStateOf(0f) }
    fun move(d:Int) {
        selected=(selected+d).coerceIn(0,items.lastIndex)
        scope.launch {list.animateScrollToItem(selected)}
    }
    LaunchedEffect(Unit) {focus.requestFocus()}
    PixelShell {
        Column(Modifier.fillMaxSize()
            .onRotaryScrollEvent {
                delta+=it.verticalScrollPixels
                if(abs(delta)>=23f) {
                    move(if(delta>0f)1 else -1)
                    delta=0f
                }
                true
            }.focusRequester(focus).focusable()
            .padding(horizontal=4.dp,vertical=11.dp)) {
            Row(Modifier.fillMaxWidth().height(24.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                PixelText("MENU",height=16.dp)
                PixelText("///",height=12.dp,color=PixelPalette.cyan)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(PixelPalette.cyan))
            Spacer(Modifier.height(5.dp))
            LazyColumn(state=list,modifier=Modifier.weight(1f),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                itemsIndexed(items) { index,item ->
                    Row(Modifier.fillMaxWidth().height(37.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        FirmwarePixelIcon(item.bitmap,item.label)
                        Spacer(Modifier.size(3.dp))
                        PixelButton(item.label,selected=selected==index,enabled=item.available,
                            modifier=Modifier.weight(1f),onClick={
                                selected=index
                                item.open()
                            })
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth().height(18.dp),
                horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                Box(Modifier.weight(1f).fillMaxSize()
                    .background(PixelPalette.panel).clickable {move(-1)},
                    contentAlignment=Alignment.Center) {
                    PixelText("UP",height=11.dp,color=PixelPalette.cyan)
                }
                Box(Modifier.weight(1f).fillMaxSize()
                    .background(PixelPalette.lime)
                    .clickable {if(items[selected].available)items[selected].open()},
                    contentAlignment=Alignment.Center) {
                    PixelText("OK",height=11.dp,color=PixelPalette.lcd)
                }
                Box(Modifier.weight(1f).fillMaxSize()
                    .background(PixelPalette.panel).clickable {move(1)},
                    contentAlignment=Alignment.Center) {
                    PixelText("DOWN",height=11.dp,color=PixelPalette.cyan)
                }
            }
        }
    }
}
''')
# Fix actual original VB menu structure and visibly show remaining evolution time.
# Keep all character stats; never substitute training timer for evolution.
write("stats/StatsMenuActivity.kt",r'''package com.github.cfogrady.vitalwear.stats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.character.VBCharacter
import com.github.cfogrady.vitalwear.common.character.CharacterSprites
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText
import kotlinx.coroutines.launch

class StatsMenuActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app=application as VitalWearApp
            val partner=remember {app.characterManager.getCurrentCharacter()}
            if(partner==null) PixelShell {
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    PixelText("NO PARTNER",height=13.dp)
                }
            } else StatusPages(partner,app)
        }
    }
}

@Composable
private fun StatusPages(partner:VBCharacter,app:VitalWearApp) {
    val bpm by app.heartRateService.lastHeartRate.collectAsState()
    val steps by app.stepService.dailySteps.collectAsState()
    val total=7+partner.transformationOptions.size
    val pager=rememberPagerState(pageCount={total})
    val scope=rememberCoroutineScope()
    val sprite=partner.characterSprites.sprites[CharacterSprites.IDLE_1]
    PixelShell {
        Column(Modifier.fillMaxSize().padding(horizontal=5.dp,vertical=12.dp),
            horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                PixelText("DIGIMON",height=13.dp,color=PixelPalette.cyan)
                PixelText("${pager.currentPage+1}/$total",height=10.dp,
                    color=PixelPalette.lime)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(PixelPalette.cyan))
            Spacer(Modifier.height(4.dp))
            Image(bitmap=sprite.asImageBitmap(),contentDescription="Actual Digimon",
                modifier=Modifier.size(32.dp),filterQuality=FilterQuality.None)
            Spacer(Modifier.height(3.dp))
            VerticalPager(state=pager,modifier=Modifier.weight(1f)) {page ->
                Column(Modifier.fillMaxSize(),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    when(page) {
                        0 -> {
                            BigHeading("VITAL",partner.characterStats.vitals.toString())
                        }
                        1 -> {
                            Heading("HEART RATE")
                            StatRow("CURRENT",if(bpm>0) "$bpm BPM" else "--")
                            StatRow("BASE","${app.heartRateService.restingHeartRate()} BPM")
                        }
                        2 -> {
                            BigHeading("STEPS",steps.toString())
                            StatRow("MILE",steps.toString())
                        }
                        3 -> {
                            Heading("BASIC INFO")
                            StatRow("PHASE",(partner.speciesStats.phase+1).toString())
                            StatRow("TYPE",partner.attributeName())
                        }
                        4 -> {
                            Heading("STATS")
                            StatRow("BP",partner.speciesStats.displayBp())
                            StatRow("HP",partner.speciesStats.displayHp())
                            StatRow("AP",partner.speciesStats.displayAp())
                        }
                        5 -> {
                            Heading("BATTLE RECORD")
                            StatRow("BATTLES",partner.characterStats.currentPhaseBattles.toString())
                            StatRow("WIN RATE",partner.characterStats.currentPhaseWinRatio().toString()+"%")
                            StatRow("PP",partner.characterStats.trainedPP.toString())
                        }
                        6 -> {
                            val secs=partner.characterStats.timeUntilNextTransformation.coerceAtLeast(0)
                            val hh=secs/3600
                            val mm=(secs%3600)/60
                            Heading("NEXT EVOLVE")
                            BigHeading("TIME","${hh}H ${mm}M")
                            StatRow("READY",if(secs==0L)"CHECK REQS" else "WAITING")
                        }
                        else -> {
                            val option=partner.transformationOptions[page-7]
                            Heading("EVOLVE REQS")
                            Image(option.sprite.asImageBitmap(),
                                contentDescription="Evolution candidate",
                                modifier=Modifier.size(27.dp),filterQuality=FilterQuality.None)
                            StatRow("VITAL",option.requiredVitals.toString())
                            StatRow("BATTLES",option.requiredBattles.toString())
                            StatRow("WIN RATE",option.requiredWinRatio.toString()+"%")
                            StatRow("PP",option.requiredPp.toString())
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(20.dp),
                horizontalArrangement=Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f).fillMaxSize().clickable {
                    scope.launch {pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}
                },contentAlignment=Alignment.Center) {
                    PixelText("PREV",height=11.dp,color=PixelPalette.cyan)
                }
                Box(Modifier.weight(1f).fillMaxSize().clickable {
                    scope.launch {pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(total-1))}
                },contentAlignment=Alignment.Center) {
                    PixelText("NEXT",height=11.dp,color=PixelPalette.lime)
                }
            }
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
private fun Heading(label:String) {
    PixelText(label,height=12.dp,color=PixelPalette.lime)
}

@Composable
private fun BigHeading(label:String,value:String) {
    Heading(label)
    Spacer(Modifier.height(4.dp))
    PixelText(value,height=20.dp,color=PixelPalette.white)
}

@Composable
private fun StatRow(label:String,value:String) {
    Row(Modifier.fillMaxWidth().height(24.dp)
        .background(PixelPalette.panel).padding(horizontal=5.dp),
        horizontalArrangement=Arrangement.SpaceBetween,
        verticalAlignment=Alignment.CenterVertically) {
        PixelText(label,height=10.dp,color=PixelPalette.cyan)
        PixelText(value.take(12),height=11.dp,color=PixelPalette.white)
    }
}
''')
# Keep prior NFC code intact. Increment versionCode so users can tell build versions apart.
for module in ("app","companion"):
    f=root/module/"build.gradle.kts"
    s=f.read_text(encoding="utf-8-sig")
    if s.count("versionCode = 61")!=1 or s.count('versionName = "0.6.1-Compact"')!=1:
        raise RuntimeError("Unexpected app version in "+module)
    f.write_text(s.replace("versionCode = 61","versionCode = 62")
        .replace('versionName = "0.6.1-Compact"','versionName = "0.6.2-Readable"'),encoding="utf-8")
print("0.6.2 Readable UI applied without changes to NFC/game data.")
