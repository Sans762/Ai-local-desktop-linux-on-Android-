#!/usr/bin/env python3
"""Pixel UI v0.6 — actual Compose screens, not mockups.
Replaces Home/Menu, styles transfer and reuses all existing game engines.
No copyrighted graphic assets required: partner sprites and scenes are game data.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear").resolve()
base="app/src/main/java/com/github/cfogrady/vitalwear/"
def write(rel,s):
    q=root/rel;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s,encoding="utf-8");print("WRITE",rel)
def edit(rel,old,new):
    q=root/rel;s=q.read_text(encoding="utf-8-sig")
    if s.count(old)!=1:raise RuntimeError("Expected exactly one match in "+rel+": "+str(s.count(old))+" for "+old[:75])
    q.write_text(s.replace(old,new),encoding="utf-8");print("EDIT",rel)

write(base+"composable/pixel/PixelKit.kt",r'''package com.github.cfogrady.vitalwear.composable.pixel

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object PixelPalette {
    val lcd=Color(0xFF031323)
    val panel=Color(0xFF0A2340)
    val cyan=Color(0xFF23BDF9)
    val lime=Color(0xFFB6F128)
    val white=Color(0xFFEAF8FF)
    val muted=Color(0xFF799CB5)
}

/** Rasterize every string onto an 8-bit bitmap and scale with NO filtering.
 * Produces actual pixel-edged text rather than smooth Android fonts.
 */
@Composable
fun PixelText(text:String, height:Dp=12.dp, color:Color=PixelPalette.white, modifier:Modifier=Modifier) {
    val bitmap=remember(text,color) {
        val label=text.uppercase()
        val paint=Paint().apply {
            isAntiAlias=false
            isFilterBitmap=false
            typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
            textSize=12f
            this.color=color.toArgb()
        }
        val w=(paint.measureText(label)+3).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(w,16,Bitmap.Config.ARGB_8888).also {
            AndroidCanvas(it).drawText(label,1f,12f,paint)
        }
    }
    val width=(height.value*bitmap.width/16f).dp
    Image(bitmap.asImageBitmap(),contentDescription=text,
        modifier=modifier.width(width).height(height),
        filterQuality=FilterQuality.None, contentScale=ContentScale.FillBounds)
}

@Composable
fun PixelShell(modifier:Modifier=Modifier, content:@Composable BoxScope.()->Unit) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xFF060D16)),
        contentAlignment=Alignment.Center) {
        val w=maxWidth*0.78f
        val h=maxHeight*0.87f
        Box(Modifier.width(w).height(h).background(Color(0xFF0C1016))
            .border(3.dp,Color(0xFF172330)),contentAlignment=Alignment.Center) {
            Box(Modifier.fillMaxSize().padding(horizontal=5.dp,vertical=7.dp)
                .background(PixelPalette.lcd),content=content)
            Row(Modifier.align(Alignment.TopCenter).height(5.dp).width(w*0.78f),
                horizontalArrangement=Arrangement.SpaceEvenly) {
                Box(Modifier.weight(1f).fillMaxSize().background(PixelPalette.lime))
                Box(Modifier.weight(0.4f).fillMaxSize().background(PixelPalette.panel))
                Box(Modifier.weight(1f).fillMaxSize().background(PixelPalette.lime))
            }
            Row(Modifier.align(Alignment.BottomCenter).height(5.dp).width(w*0.78f),
                horizontalArrangement=Arrangement.SpaceEvenly) {
                Box(Modifier.weight(1f).fillMaxSize().background(PixelPalette.lime))
                Box(Modifier.weight(0.4f).fillMaxSize().background(PixelPalette.panel))
                Box(Modifier.weight(1f).fillMaxSize().background(PixelPalette.lime))
            }
        }
    }
}

@Composable
fun PixelButton(label:String,selected:Boolean=false,enabled:Boolean=true,
                onClick:()->Unit, modifier:Modifier=Modifier) {
    val bg=if(selected)PixelPalette.lime else PixelPalette.panel
    val edge=if(selected)PixelPalette.lime else PixelPalette.cyan
    val foreground=if(!enabled)PixelPalette.muted else if(selected)PixelPalette.lcd else PixelPalette.white
    Box(modifier.fillMaxWidth().height(34.dp)
        .border(2.dp,edge)
        .background(bg)
        .clickable(enabled=enabled,onClick=onClick)
        .padding(horizontal=6.dp),
        contentAlignment=Alignment.CenterStart) {
        PixelText(label,height=11.dp,color=foreground)
    }
}

/** An 8x8 native pixel icon; never platform emoji or antialiased vector artwork. */
@Composable
fun PixelIcon(type:String,color:Color,modifier:Modifier=Modifier) {
    val pattern=when(type) {
        "STATUS" -> listOf("00111100","01100110","11000011","11011011","11011011","11000011","01100110","00111100")
        "TRAINING" -> listOf("10000001","11000011","11111111","01111110","01111110","11111111","11000011","10000001")
        "ADVENTURE" -> listOf("00011000","00111100","01100110","11000011","11011011","11111111","11111111","00000000")
        "BATTLE" -> listOf("10000001","01000010","00100100","00011000","00011000","00100100","01000010","10000001")
        "CONNECT" -> listOf("00100100","01000010","10011001","10100101","10100101","10011001","01000010","00100100")
        "SENSOR" -> listOf("01100110","11111111","11111111","11111111","01111110","00111100","00011000","00000000")
        "SETTINGS" -> listOf("00100100","01111110","11011011","11111111","11111111","11011011","01111110","00100100")
        else -> listOf("00011000","00111100","01111110","11111111","11111111","01111110","00111100","00011000")
    }
    Canvas(modifier.size(23.dp)) {
        val px=size.width/8f
        for(y in 0 until 8) for(x in 0 until 8)
            if(pattern[y][x]=='1')drawRect(color,topLeft=androidx.compose.ui.geometry.Offset(x*px,y*px),
                size=androidx.compose.ui.geometry.Size(px,px))
    }
}
''')

write(base+"character/PixelHome.kt",r'''package com.github.cfogrady.vitalwear.character

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText
import kotlinx.coroutines.delay

@Composable
fun PixelHome(controller:PartnerScreenController, scene:Bitmap) {
    val scope=rememberCoroutineScope()
    val characterFrames by remember(controller) { controller.getCharacterBitmaps(scope) }.collectAsStateWithLifecycle()
    val steps by controller.dailyStepCount.collectAsStateWithLifecycle()
    val vitals by controller.vitals.collectAsStateWithLifecycle()
    val time by remember(controller) { controller.getTimeFlow(scope) }.collectAsStateWithLifecycle()
    val app=LocalContext.current.applicationContext as VitalWearApp
    val bpm by app.heartRateService.lastHeartRate.collectAsState()
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(characterFrames.size) {
        while(true) {
            delay(460)
            frame+=1
        }
    }
    PixelShell {
        Image(bitmap=scene.asImageBitmap(),contentDescription="Current game background",
            modifier=Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds,
            filterQuality=FilterQuality.None)
        Box(Modifier.fillMaxWidth().height(142.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color(0xAA001321),Color.Transparent))))
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top=19.dp),
            horizontalAlignment=Alignment.CenterHorizontally) {
            PixelText(time.hour.toString().padStart(2,'0')+":"+
                time.minute.toString().padStart(2,'0'),height=31.dp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                PixelText("VP:"+vitals.toString().padStart(4,'0'),height=9.dp,color=PixelPalette.lime)
                PixelText("ST:"+steps.toString().padStart(5,'0'),height=9.dp)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.background(Color(0xDB081C2E)).padding(horizontal=7.dp,vertical=5.dp)) {
                PixelText("BPM:"+(if(bpm>0)bpm.toString() else "--"),
                    height=11.dp,color=Color(0xFFFF94A4))
            }
        }
        // A real imported Digimon sprite, enlarged with nearest-neighbour scaling.
        // It follows partner switching/evolution through the existing live Flow.
        val sprite=if(characterFrames.isNotEmpty())
            characterFrames[frame % characterFrames.size] else null
        if(sprite!=null) {
            Image(bitmap=sprite.asImageBitmap(),contentDescription="Current Digimon",
                modifier=Modifier.align(Alignment.BottomCenter)
                    .padding(bottom=17.dp).size(67.dp),
                contentScale=ContentScale.Fit,filterQuality=FilterQuality.None)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom=2.dp)) {
            PixelText("SWIPE FOR MENU",height=7.dp,color=PixelPalette.white)
        }
    }
}
''')

write(base+"main/VbMenuScreen.kt",r'''package com.github.cfogrady.vitalwear.main

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.cfogrady.vitalwear.heartrate.SensorDiagnosticsActivity
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText
import com.github.cfogrady.vitalwear.composable.pixel.PixelIcon
import com.github.cfogrady.vitalwear.composable.pixel.PixelButton
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class Item(val title:String,val enabled:Boolean,val click:()->Unit)

@Composable
fun VbMenuScreen(controller:MainScreenController) {
    val context=LocalContext.current
    val pet by controller.activePartner.collectAsStateWithLifecycle()
    val hasPet=pet!=null
    val awake=pet?.characterStats?.sleeping!=true
    val evolved=(pet?.speciesStats?.phase?:0)>1
    val actions=listOf(
        Item("STATUS",hasPet){controller.launchStatsMenuActivity()},
        Item("TRAINING",hasPet&&awake){controller.launchTrainingMenuActivity()},
        Item("ADVENTURE",hasPet&&awake&&evolved){controller.launchAdventureActivity()},
        Item("BATTLE",hasPet&&awake&&evolved){controller.launchBattleActivity()},
        Item("CHARACTER",true){controller.launchCharacterSelectionActivity()},
        Item("CONNECT",true){controller.launchTransferActivity()},
        Item("SENSOR",true){context.startActivity(Intent(context,SensorDiagnosticsActivity::class.java))},
        Item("SETTINGS",true){controller.launchSettingsActivity()},
        Item(if(awake)"SLEEP" else "WAKE",hasPet){controller.toggleSleep()}
    )
    val scroll=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val focus=remember{FocusRequester()}
    var selected by remember{mutableIntStateOf(0)}
    var rotary by remember{mutableFloatStateOf(0f)}
    LaunchedEffect(Unit){focus.requestFocus()}
    PixelShell {
        Column(Modifier.fillMaxSize()
            .onRotaryScrollEvent {
                rotary+=it.verticalScrollPixels
                if(abs(rotary)>=23f) {
                    selected=(selected+if(rotary>0)1 else -1).coerceIn(0,actions.lastIndex)
                    rotary=0f
                    scope.launch{scroll.animateScrollToItem(selected)}
                }
                true
            }.focusRequester(focus).focusable()
            .padding(start=8.dp,end=8.dp,top=15.dp,bottom=9.dp)) {
            Box(Modifier.fillMaxWidth().background(PixelPalette.panel)
                .padding(horizontal=4.dp,vertical=7.dp),contentAlignment=Alignment.Center) {
                PixelText("VB // MENU",height=15.dp,color=PixelPalette.cyan)
            }
            LazyColumn(state=scroll,verticalArrangement=Arrangement.spacedBy(5.dp),
                modifier=Modifier.padding(top=8.dp)) {
                itemsIndexed(actions){index,item->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        PixelIcon(item.title,if(selected==index)PixelPalette.lime else PixelPalette.cyan,
                            modifier=Modifier.padding(end=4.dp))
                        PixelButton(label=item.title,selected=selected==index,
                            enabled=item.enabled,
                            modifier=Modifier.weight(1f),onClick={
                                selected=index
                                item.click()
                            })
                    }
                }
            }
        }
    }
}
''')

write(base+"main/MainScreen.kt",r'''package com.github.cfogrady.vitalwear.main

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.cfogrady.vitalwear.character.PixelHome
import kotlinx.coroutines.launch

/** Unified real UI: live pet portrait + pixel button menu. */
@Composable
fun MainScreen(controller:MainScreenController) {
    val current by controller.activePartner.collectAsStateWithLifecycle()
    val ready by controller.readyToTransform.collectAsStateWithLifecycle()
    val scene by remember(controller){controller.getBackgroundFlow()}.collectAsStateWithLifecycle()
    val sleep=current?.characterStats?.sleeping==true
    var launched by remember{mutableStateOf(false)}
    LaunchedEffect(ready,sleep) {
        if(!ready||sleep) launched=false
        else if(!launched) {
            launched=true
            controller.launchTransformActivity()
        }
    }
    val hasPet=current!=null
    val pager=rememberPagerState(pageCount={if(hasPet)2 else 1})
    val scope=rememberCoroutineScope()
    val focus=remember{FocusRequester()}
    LaunchedEffect(Unit){focus.requestFocus()}
    VerticalPager(state=pager,modifier=Modifier.fillMaxSize()
        .onRotaryScrollEvent {
            val next=(pager.currentPage+(if(it.verticalScrollPixels>0f)1 else -1))
                .coerceIn(0,pager.pageCount-1)
            if(next!=pager.currentPage)scope.launch{pager.animateScrollToPage(next)}
            true
        }
        .focusRequester(focus).focusable()) { page ->
        if(hasPet && page==0) PixelHome(controller.partnerScreenController,scene)
        else VbMenuScreen(controller)
    }
}
''')

# Transfer screen is also a pixel-art menu, not a Material3 pill-button dialog.
transfer=base+"transfer/TransferScreen.kt"
p=root/transfer
s=p.read_text(encoding="utf-8-sig")
start=s.index('@Preview(\n    device = WearDevices.LARGE_ROUND',s.index('fun TransferScreen('))
end=s.index('\n@Composable\nfun TransferResult(',start)
s=s[:start]+'''@Composable
fun SelectSendOrReceive(onSelect:(SendOrReceive)->Unit={}) {
    com.github.cfogrady.vitalwear.composable.pixel.PixelShell {
        Column(modifier=Modifier.fillMaxSize().padding(horizontal=7.dp),
            verticalArrangement=Arrangement.Center,
            horizontalAlignment=Alignment.CenterHorizontally) {
            com.github.cfogrady.vitalwear.composable.pixel.PixelText(
                "CONNECT",height=17.dp,
                color=com.github.cfogrady.vitalwear.composable.pixel.PixelPalette.cyan)
            Spacer(Modifier.height(13.dp))
            com.github.cfogrady.vitalwear.composable.pixel.PixelButton(
                "SEND TO VBH",onClick={onSelect(SendOrReceive.SEND)})
            Spacer(Modifier.height(8.dp))
            com.github.cfogrady.vitalwear.composable.pixel.PixelButton(
                "GET FROM VBH",onClick={onSelect(SendOrReceive.RECEIVE)})
            Spacer(Modifier.height(10.dp))
            com.github.cfogrady.vitalwear.composable.pixel.PixelText(
                "HOLD NFC TO PHONE",height=8.dp)
        }
    }
}
'''+s[end:]
if 'import androidx.compose.foundation.layout.Spacer' not in s:
    s=s.replace('import androidx.compose.foundation.layout.offset','import androidx.compose.foundation.layout.offset\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.fillMaxWidth')
if 'import androidx.compose.ui.unit.dp' not in s:s=s.replace('import androidx.compose.ui.unit.Dp','import androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp')
p.write_text(s,encoding="utf-8");print("PIXEL TRANSFER MENU",transfer)
print("PIXEL HOME / MENU / CONNECT COMPLETE")
