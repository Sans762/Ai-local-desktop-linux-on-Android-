#!/usr/bin/env python3
"""Galaxy Watch8 40mm: native coordinate-based, 438x438 pixel-art UI.

Applied AFTER 0.6.2. Renders actual UI (no mockup), with fixed reference
coordinates and pixel-fidelity nearest-neighbour scaling. Runtime Digimon
sprites/stats/actions remain from VitalWear. Original VB firmware stays user-
provided and is never embedded in APK.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base=root/"app/src/main/java/com/github/cfogrady/vitalwear"
def write(p, data):
    out=base/p
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(data,encoding="utf-8")
    print("WRITTEN",out)

write("composable/pixel/VbPixelArt.kt",r'''package com.github.cfogrady.vitalwear.composable.pixel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * Shared native 438x438 logical pixel coordinate system. Scales to other watch
 * display sizes without making the 40mm UI text and menu rows unpredictable.
 *
 * All rectangles in central panel (104..334, 40..398) fit within the 438 px
 * diameter disc: sqrt(115^2+179^2)=212.8 < 219.
 */
object VbPixelArt {
    const val DESIGN = 438f
    const val NAVY = 0xFF061327.toInt()
    const val PANEL = 0xFF0C2142.toInt()
    const val BLUE = 0xFF123A76.toInt()
    const val CYAN = 0xFF17D9FF.toInt()
    const val WHITE = 0xFFF0FCFF.toInt()
    const val LIME = 0xFFBBFF11.toInt()
    const val MUTED = 0xFFA4D0ED.toInt()
    const val PINK = 0xFFFF5A92.toInt()

    private val font:Map<Char,Array<String>> = mapOf(
        'A' to arrayOf("01110","10001","10001","11111","10001","10001","10001"),
        'B' to arrayOf("11110","10001","10001","11110","10001","10001","11110"),
        'C' to arrayOf("01111","10000","10000","10000","10000","10000","01111"),
        'D' to arrayOf("11110","10001","10001","10001","10001","10001","11110"),
        'E' to arrayOf("11111","10000","10000","11110","10000","10000","11111"),
        'F' to arrayOf("11111","10000","10000","11110","10000","10000","10000"),
        'G' to arrayOf("01111","10000","10000","10111","10001","10001","01111"),
        'H' to arrayOf("10001","10001","10001","11111","10001","10001","10001"),
        'I' to arrayOf("11111","00100","00100","00100","00100","00100","11111"),
        'J' to arrayOf("00111","00010","00010","00010","10010","10010","01100"),
        'K' to arrayOf("10001","10010","10100","11000","10100","10010","10001"),
        'L' to arrayOf("10000","10000","10000","10000","10000","10000","11111"),
        'M' to arrayOf("10001","11011","10101","10101","10001","10001","10001"),
        'N' to arrayOf("10001","11001","10101","10011","10001","10001","10001"),
        'O' to arrayOf("01110","10001","10001","10001","10001","10001","01110"),
        'P' to arrayOf("11110","10001","10001","11110","10000","10000","10000"),
        'Q' to arrayOf("01110","10001","10001","10001","10101","10010","01101"),
        'R' to arrayOf("11110","10001","10001","11110","10100","10010","10001"),
        'S' to arrayOf("01111","10000","10000","01110","00001","00001","11110"),
        'T' to arrayOf("11111","00100","00100","00100","00100","00100","00100"),
        'U' to arrayOf("10001","10001","10001","10001","10001","10001","01110"),
        'V' to arrayOf("10001","10001","10001","10001","10001","01010","00100"),
        'W' to arrayOf("10001","10001","10001","10101","10101","10101","01010"),
        'X' to arrayOf("10001","10001","01010","00100","01010","10001","10001"),
        'Y' to arrayOf("10001","10001","01010","00100","00100","00100","00100"),
        'Z' to arrayOf("11111","00001","00010","00100","01000","10000","11111"),
        '0' to arrayOf("01110","10011","10101","10101","11001","10001","01110"),
        '1' to arrayOf("00100","01100","00100","00100","00100","00100","01110"),
        '2' to arrayOf("01110","10001","00001","00010","00100","01000","11111"),
        '3' to arrayOf("11110","00001","00001","00110","00001","00001","11110"),
        '4' to arrayOf("10010","10010","10010","11111","00010","00010","00010"),
        '5' to arrayOf("11111","10000","10000","11110","00001","00001","11110"),
        '6' to arrayOf("01110","10000","10000","11110","10001","10001","01110"),
        '7' to arrayOf("11111","00001","00010","00100","00100","00100","00100"),
        '8' to arrayOf("01110","10001","10001","01110","10001","10001","01110"),
        '9' to arrayOf("01110","10001","10001","01111","00001","00001","01110"),
        ':' to arrayOf("00000","00100","00100","00000","00100","00100","00000"),
        '/' to arrayOf("00001","00001","00010","00100","01000","10000","10000"),
        '-' to arrayOf("00000","00000","00000","11111","00000","00000","00000"),
        '.' to arrayOf("00000","00000","00000","00000","00000","01100","01100"),
        ' ' to arrayOf("00000","00000","00000","00000","00000","00000","00000"),
        '!' to arrayOf("00100","00100","00100","00100","00100","00000","00100")
    )
    fun rect(c:Canvas,x:Float,y:Float,w:Float,h:Float,color:Int) {
        val p=Paint().apply { this.color=color; isAntiAlias=false }
        c.drawRect(x,y,x+w,y+h,p)
    }
    fun outline(c:Canvas,x:Float,y:Float,w:Float,h:Float,color:Int,t:Float=2f) {
        rect(c,x,y,w,t,color);rect(c,x,y+h-t,w,t,color)
        rect(c,x,y,t,h,color);rect(c,x+w-t,y,t,h,color)
    }
    fun textWidth(s:String,scale:Float):Float=s.length*6f*scale
    fun text(c:Canvas,s:String,x:Float,y:Float,scale:Float,color:Int) {
        val p=Paint().apply {this.color=color;isAntiAlias=false}
        s.uppercase().forEachIndexed { i,ch ->
            val glyph=font[ch]?:font[' ']!!
            glyph.forEachIndexed { yy,row ->
                row.forEachIndexed { xx,v ->
                    if(v=='1') c.drawRect(x+i*6*scale+xx*scale,y+yy*scale,
                        x+i*6*scale+(xx+1)*scale,y+(yy+1)*scale,p)
                }
            }
        }
    }
    fun centered(c:Canvas,s:String,y:Float,scale:Float,color:Int) {
        text(c,s,219f-textWidth(s,scale)/2f,y,scale,color)
    }
    /** Navigation viewport mirrors VB portrait insert but belongs to watch glass only. */
    fun shell(c:Canvas) {
        c.drawColor(0xFF030A16.toInt())
        rect(c,104f,40f,230f,357f,0xFF020B1C.toInt())
        outline(c,104f,40f,230f,357f,0xFF17345E.toInt(),4f)
        rect(c,117f,43f,78f,5f,LIME);rect(c,202f,43f,112f,5f,LIME)
        rect(c,117f,389f,78f,5f,LIME);rect(c,202f,389f,112f,5f,LIME)
    }
    private val icons=mapOf(
        "STATUS" to arrayOf("00111100","01100110","11011011","11000011","11000011","11111111","01111110","00000000"),
        "TRAINING" to arrayOf("00000000","11000011","11111111","00111100","00111100","11111111","11000011","00000000"),
        "ADVENTURE" to arrayOf("00011000","00111100","01100110","11000011","11011011","11111111","00000000","00000000"),
        "BATTLE" to arrayOf("10000001","01000010","00100100","00011000","00100100","01000010","10000001","00000000"),
        "DIGIMON" to arrayOf("01111110","11000011","10100101","10011001","10100101","11000011","01111110","00000000"),
        "CONNECT" to arrayOf("00100100","01000010","10011001","10100101","10100101","10011001","01000010","00100100"),
        "SENSOR" to arrayOf("01100110","11111111","11111111","11111111","01111110","00111100","00011000","00000000"),
        "SETTINGS" to arrayOf("00100100","01111110","11011011","11111111","11111111","11011011","01111110","00100100")
    )
    fun icon(c:Canvas,name:String,x:Float,y:Float,scale:Float,color:Int) {
        val pattern=icons[name]?:icons["STATUS"]!!
        pattern.forEachIndexed { ry,row ->
            row.forEachIndexed { rx,v ->
                if(v=='1') rect(c,x+rx*scale,y+ry*scale,scale,scale,color)
            }
        }
    }
    fun menu(c:Canvas,labels:List<String>,allowed:List<Boolean>,selected:Int,first:Int) {
        shell(c)
        text(c,"MENU",119f,65f,3.4f,WHITE)
        text(c,"///",272f,66f,2.4f,CYAN)
        rect(c,116f,94f,205f,3f,CYAN)
        for(i in 0 until 7) {
            val idx=first+i
            if(idx>=labels.size)break
            val y=103f+i*40f
            val active=(idx==selected)
            val col=if(active)LIME else CYAN
            rect(c,114f,y,210f,35f,if(active)LIME else PANEL)
            outline(c,114f,y,210f,35f,col,2.5f)
            icon(c,labels[idx],123f,y+9f,2.25f,if(active)NAVY else WHITE)
            rect(c,151f,y+6f,2f,23f,if(active)NAVY else CYAN)
            val label=labels[idx]
            val scale=if(label.length>8)2.5f else 2.75f
            text(c,label,160f,y+8f,scale,
                if(!allowed[idx]) {if(active) 0xFF477300.toInt() else 0xFF7594AB.toInt()}
                else if(active)NAVY else WHITE)
            if(active) {
                rect(c,97f,y+12f,9f,10f,LIME)
                rect(c,105f,y+15f,5f,4f,LIME)
            }
        }
        // tiny page-position line; no UP / OK / DOWN controls obstructing 7 rows.
        rect(c,117f,385f,60f,3f,CYAN)
        rect(c,181f,385f,30f,3f,CYAN)
    }
    /** Distinctive pixel-night city; does not depend on user's firmware background.
     * Real partner sprite is overlaid at the bottom by the game composable.
     */
    fun city(c:Canvas) {
        shell(c)
        for(y in 51..384 step 4) {
            val shade=when {
                y<150 -> 0xFF073E9B.toInt()
                y<260 -> 0xFF0A5FBD.toInt()
                else -> 0xFF0751A8.toInt()
            }
            rect(c,113f,y.toFloat(),213f,4f,shade)
        }
        // Pixel clouds
        rect(c,132f,76f,53f,7f,0xFF1588EB.toInt())
        rect(c,143f,69f,30f,7f,0xFF1588EB.toInt())
        rect(c,252f,104f,45f,7f,0xFF2996F6.toInt())
        val widths=intArrayOf(24,34,29,42,30,21,35,27)
        val heights=intArrayOf(82,130,97,154,111,142,90,124)
        var x=115
        for(i in widths.indices) {
            val w=widths[i]
            val h=heights[i]
            val top=315-h
            rect(c,x.toFloat(),top.toFloat(),w.toFloat(),h.toFloat(),
                if(i%2==0)0xFF063778.toInt() else 0xFF082B6A.toInt())
            for(py in top+15 until 313 step 12)
                for(px in x+5 until x+w-3 step 10) {
                    if((px+py+i)%4!=0)
                        rect(c,px.toFloat(),py.toFloat(),4f,6f,
                            if((px/10+py/12)%3==0) 0xFF65ED63.toInt()
                            else 0xFF22DDE7.toInt())
                }
            x+=w-3
        }
        rect(c,113f,310f,213f,2f,0xFF16CAFF.toInt())
        for(i in 0..13) {
            val px=113f+i*16f
            rect(c,px,315f,7f,35f,if(i%2==0)0xFF14B8F6.toInt() else 0xFF17E4C3.toInt())
        }
        rect(c,113f,350f,213f,35f,0xFF535D90.toInt())
        for(i in 0..10) rect(c,116f+i*20,359f,14f,2f,0xFF818CB4.toInt())
    }
    fun battery(c:Canvas) {
        outline(c,294f,61f,25f,13f,WHITE,2f)
        rect(c,320f,65f,3f,5f,WHITE)
        for(i in 0..3)rect(c,298f+i*5f,64f,3f,7f,LIME)
    }
    fun sprite(c:Canvas,bmp:Bitmap?) {
        if(bmp==null)return
        val p=Paint().apply {isAntiAlias=false;isFilterBitmap=false}
        c.drawBitmap(bmp,Rect(0,0,bmp.width,bmp.height),
            RectF(184f,325f,256f,381f),p)
    }
}
''')

write("main/VbMenuScreen.kt",r'''package com.github.cfogrady.vitalwear.main

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.cfogrady.vitalwear.composable.pixel.VbPixelArt
import com.github.cfogrady.vitalwear.heartrate.SensorDiagnosticsActivity
import kotlin.math.abs
import kotlin.math.min

private data class VbAction(val label:String,val enabled:Boolean,val click:()->Unit)

/**
 * Actual game navigation drawn at reference 438x438 design coordinates.
 * 7 rows fit inside 230x357 portrait viewport; no giant Compose buttons.
 * Scales for all round watches. Tap the outer top/bottom rim to move selection;
 * tap a menu row to select, tap selected row again to launch.
 */
@Composable
fun VbMenuScreen(controller:MainScreenController) {
    val ctx=LocalContext.current
    val partner by controller.activePartner.collectAsStateWithLifecycle()
    val hasPet=partner!=null
    val awake=partner?.characterStats?.sleeping!=true
    val evolved=(partner?.speciesStats?.phase?:0)>1
    val actions=listOf(
        VbAction("STATUS",hasPet){controller.launchStatsMenuActivity()},
        VbAction("TRAINING",hasPet&&awake){controller.launchTrainingMenuActivity()},
        VbAction("ADVENTURE",hasPet&&awake&&evolved){controller.launchAdventureActivity()},
        VbAction("BATTLE",hasPet&&awake&&evolved){controller.launchBattleActivity()},
        // No inventory in the base engine; functional character screen instead of fake ITEMS.
        VbAction("DIGIMON",true){controller.launchCharacterSelectionActivity()},
        VbAction("CONNECT",true){controller.launchTransferActivity()},
        VbAction("SENSOR",true){ctx.startActivity(Intent(ctx,SensorDiagnosticsActivity::class.java))},
        VbAction("SETTINGS",true){controller.launchSettingsActivity()},
        VbAction(if(awake)"SLEEP" else "WAKE",hasPet){controller.toggleSleep()}
    )
    var selected by remember{mutableIntStateOf(0)}
    var first by remember{mutableIntStateOf(0)}
    var rotary by remember{mutableFloatStateOf(0f)}
    fun move(direction:Int) {
        selected=(selected+direction).coerceIn(0,actions.lastIndex)
        if(selected<first)first=selected
        if(selected>first+6)first=selected-6
    }
    val focus=remember{FocusRequester()}
    LaunchedEffect(Unit){focus.requestFocus()}
    Canvas(Modifier.fillMaxSize()
        .onRotaryScrollEvent{
            rotary+=it.verticalScrollPixels
            if(abs(rotary)>22f) {
                move(if(rotary>0)1 else -1)
                rotary=0f
            }
            true
        }.focusRequester(focus).focusable()
        .pointerInput(actions,selected,first) {
            detectTapGestures { point ->
                val scale=min(size.width,size.height)/VbPixelArt.DESIGN
                val x=(point.x-(size.width-VbPixelArt.DESIGN*scale)/2f)/scale
                val y=(point.y-(size.height-VbPixelArt.DESIGN*scale)/2f)/scale
                if(x<108f||x>334f) {
                    if(y<219f)move(-1) else move(1)
                } else if(y>=103f&&y<383f) {
                    val index=first+((y-103f)/40f).toInt()
                    if(index in actions.indices) {
                        if(index==selected) {
                            if(actions[index].enabled)actions[index].click()
                        }else {
                            selected=index
                        }
                    }
                } else if(y>=52f && y<103f) {
                    if(actions[selected].enabled)actions[selected].click()
                }
            }
        }
    ){
        drawIntoCanvas {
            val canvas=it.nativeCanvas
            val scale=min(size.width,size.height)/VbPixelArt.DESIGN
            canvas.save()
            canvas.translate((size.width-VbPixelArt.DESIGN*scale)/2f,
                (size.height-VbPixelArt.DESIGN*scale)/2f)
            canvas.scale(scale,scale)
            VbPixelArt.menu(canvas,actions.map{a->a.label},
                actions.map{a->a.enabled},selected,first)
            canvas.restore()
        }
    }
}
''')

write("character/PixelHome.kt",r'''package com.github.cfogrady.vitalwear.character

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.composable.pixel.VbPixelArt as Art
import kotlinx.coroutines.delay
import kotlin.math.min

/** Pixel city game-home, real clock/steps/vitals/BPM and live pet sprite.
 * The scene is native vector pixel artwork drawn on 438 reference canvas.
 */
@Composable
fun PixelHome(controller:PartnerScreenController,scene:Bitmap) {
    val scope=rememberCoroutineScope()
    val frames by remember(controller){controller.getCharacterBitmaps(scope)}
        .collectAsStateWithLifecycle()
    val steps by controller.dailyStepCount.collectAsStateWithLifecycle()
    val vitals by controller.vitals.collectAsStateWithLifecycle()
    val time by remember(controller){controller.getTimeFlow(scope)}
        .collectAsStateWithLifecycle()
    val app=LocalContext.current.applicationContext as VitalWearApp
    val bpm by app.heartRateService.lastHeartRate.collectAsState()
    var frame by remember{mutableIntStateOf(0)}
    LaunchedEffect(frames.size) {
        while(true){delay(460L);frame++}
    }
    val pet=if(frames.isNotEmpty())frames[frame%frames.size] else null
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas {
            val canvas=it.nativeCanvas
            val k=min(size.width,size.height)/Art.DESIGN
            canvas.save()
            canvas.translate((size.width-Art.DESIGN*k)/2f,
                (size.height-Art.DESIGN*k)/2f)
            canvas.scale(k,k)
            Art.city(canvas)
            Art.battery(canvas)
            val clock=time.hour.toString().padStart(2,'0')+":"+
                time.minute.toString().padStart(2,'0')
            Art.centered(canvas,clock,113f,5.45f,Art.WHITE)
            Art.rect(canvas,129f,177f,180f,36f,0xDE06152B.toInt())
            Art.outline(canvas,129f,177f,180f,36f,Art.CYAN,2f)
            Art.text(canvas,"VP",140f,187f,2.65f,Art.LIME)
            Art.text(canvas,vitals.toString().padStart(4,'0'),193f,185f,3f,Art.WHITE)
            Art.rect(canvas,129f,216f,180f,36f,0xDE06152B.toInt())
            Art.outline(canvas,129f,216f,180f,36f,Art.CYAN,2f)
            Art.text(canvas,"STEPS",139f,226f,2.4f,Art.WHITE)
            Art.text(canvas,steps.toString().padStart(4,'0'),234f,227f,2.4f,Art.WHITE)
            Art.rect(canvas,154f,258f,130f,35f,0xE5031327.toInt())
            Art.outline(canvas,154f,258f,130f,35f,Art.LIME,2f)
            Art.text(canvas,"BPM",165f,268f,2.6f,Art.PINK)
            Art.text(canvas,if(bpm>0)bpm.toString() else "--",239f,268f,2.7f,Art.WHITE)
            Art.sprite(canvas,pet)
            canvas.restore()
        }
    }
}
''')
for module in ("app", "companion"):
    gradle=root/module/"build.gradle.kts"
    data=gradle.read_text(encoding="utf-8-sig")
    if data.count("versionCode = 62")!=1 or data.count('versionName = "0.6.2-Readable"')!=1:
        raise RuntimeError("Unexpected version in "+module)
    gradle.write_text(
        data.replace("versionCode = 62","versionCode = 63")
            .replace('versionName = "0.6.2-Readable"','versionName = "0.6.3-Watch8-40mm"'),
        encoding="utf-8")
print("PIXEL-ACCURATE 438 WATCH UI AND VERSION 0.6.3 READY")
