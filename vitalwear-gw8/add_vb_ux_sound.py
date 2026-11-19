#!/usr/bin/env python3
"""VitalWear BE UX: real partner UI frame, digital bezel, and reliable audio settings.
Applied after existing direct-import/sensor patches.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear").resolve()
a="app/src/main/java/com/github/cfogrady/vitalwear/"
def read(p):return (root/p).read_text(encoding="utf-8-sig")
def write(p,s):
 q=root/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s,encoding="utf-8");print("WRITE",p)
def change(p,b,c):
 s=read(p)
 if s.count(b)!=1:raise RuntimeError(p+" expected 1 occurrence, got "+str(s.count(b))+" for "+repr(b[:65]))
 write(p,s.replace(b,c))
# SoundPool asynchronous loading: queue first missed sound, play when ready.
sound=a+"sound/PetSfx.kt"
s=read(sound)
s=s.replace("    private var loaded = mutableSetOf<Int>()","    private var loaded = mutableSetOf<Int>()\n    private val queued=mutableSetOf<Int>()\n    private var lastContext: Context?=null")
s=s.replace('''            if (status == 0) synchronized(this) { loaded.add(soundId) }''',
'''            if(status==0) synchronized(this) {
                loaded.add(soundId)
                if(queued.remove(soundId)) {
                    val appContext=lastContext
                    if(appContext!=null) playLoaded(appContext,soundId)
                }
            }''')
s=s.replace('''        preload(context)
        val id=sampleIds[res] ?: return
        if (loaded.contains(id)) {
            val volume=prefs.getFloat("volume",0.35f).coerceIn(0f,1f)
            pool?.play(id, volume,volume,1,0,1f)
        }''',
'''        lastContext=context.applicationContext
        preload(context)
        val id=sampleIds[res] ?: return
        if(loaded.contains(id)) playLoaded(context,id)
        else queued.add(id)''')
# We use USAGE_MEDIA so Samsung wearable volume/mute behavior follows media volume; no bypassing mute.
s=s.replace("AudioAttributes.USAGE_ASSISTANCE_SONIFICATION","AudioAttributes.USAGE_MEDIA")
s=s.replace('''    }
}''','''    }
    @Synchronized private fun playLoaded(context:Context,id:Int) {
        val prefs=context.getSharedPreferences("digivice_sfx", Context.MODE_PRIVATE)
        if(!prefs.getBoolean("enabled",true)) return
        val hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if(prefs.getBoolean("quiet_hours",false) && (hour>=22 || hour<7))return
        val vol=prefs.getFloat("volume",0.65f).coerceIn(0f,1f)
        pool?.play(id,vol,vol,1,0,1f)
    }
}''')
# Don't blindly replace if already done in upstream
write(sound,s)
change(a+"VitalWearApp.kt",'''        buildDependencies()
        applicationContext.registerReceiver''',
'''        buildDependencies()
        // Preload before first menu transition so SoundPool async loads cannot eat first SFX.
        com.github.cfogrady.vitalwear.sound.PetSfx.preload(this)
        applicationContext.registerReceiver''')

# Sound controls inside the game's existing Settings vertical pager.
settings=a+"settings/SettingsComposableFactory.kt"
change(settings,
'''import androidx.compose.ui.unit.em''',
'''import androidx.compose.ui.unit.em
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.Button
import com.github.cfogrady.vitalwear.R
import com.github.cfogrady.vitalwear.sound.PetSfx''')
change(settings,'''                    SettingsMenuOption.Save -> {''',
'''                    SettingsMenuOption.Sound -> {
                        val ctx=LocalContext.current
                        val prefs=remember { ctx.getSharedPreferences("digivice_sfx", android.content.Context.MODE_PRIVATE) }
                        var soundOn by remember { mutableStateOf(prefs.getBoolean("enabled",true)) }
                        var level by remember { mutableStateOf(prefs.getFloat("volume",0.65f)) }
                        var quiet by remember { mutableStateOf(prefs.getBoolean("quiet_hours",false)) }
                        Column(modifier=Modifier.fillMaxSize(),
                            horizontalAlignment=Alignment.CenterHorizontally,
                            verticalArrangement=Arrangement.Center) {
                            Text("SOUND",fontWeight=FontWeight.Bold,fontSize=2.0.em)
                            Button(onClick={
                                soundOn=!soundOn
                                prefs.edit().putBoolean("enabled",soundOn).apply()
                            }) { Text(if(soundOn) "SFX: ON" else "SFX: OFF") }
                            Button(onClick={
                                level=if(level<0.4f)0.65f else if(level<0.8f)1.0f else 0.25f
                                prefs.edit().putFloat("volume",level).apply()
                                PetSfx.play(ctx,R.raw.digivice_menu)
                            }) { Text("VOL: "+(level*100).toInt()+"%") }
                            Button(onClick={
                                quiet=!quiet
                                prefs.edit().putBoolean("quiet_hours",quiet).apply()
                            }) { Text(if(quiet) "NIGHT MUTE: ON" else "NIGHT MUTE: OFF") }
                            Button(onClick={
                                PetSfx.play(ctx,R.raw.digivice_win)
                            }) { Text("TEST SOUND") }
                        }
                    }
                    SettingsMenuOption.Save -> {''')
change(settings,'''        Sensors
    }''','''        Sensors,
        Sound
    }''')
change(settings,'''            SettingsMenuOption.Sensors,
            SettingsMenuOption.Save)''',
'''            SettingsMenuOption.Sensors,
            SettingsMenuOption.Sound,
            SettingsMenuOption.Save)''')
# Optional VB-style frame overlay using the SAME game background/characters.
# Canvas overlay doesn't steal taps and doesn't alter original rectangular layout.
ui=a+"main/MainScreen.kt"
change(ui,'''import androidx.compose.foundation.layout.size''',
'''import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.cfogrady.vitalwear.sound.PetSfx''')
change(ui,'''    val coroutineScope = rememberCoroutineScope()
    val vbCharacter''',
'''    val coroutineScope = rememberCoroutineScope()
    val context=LocalContext.current
    val bezelFocus=remember { FocusRequester() }
    val vbCharacter''')
change(ui,'''        VerticalPager(state = pagerState) {page ->''',
'''        LaunchedEffect(Unit) { bezelFocus.requestFocus() }
        LaunchedEffect(pagerState.currentPage) {
            PetSfx.play(context,R.raw.digivice_menu)
        }
        // Touch bezel rotary input or crown scroll changes vertical pages.
        // Normal vertical finger swipes continue to work as before.
        VerticalPager(
            modifier=Modifier.fillMaxSize()
                .onRotaryScrollEvent { event ->
                    if(menuPages.isNotEmpty()) {
                        val step=if(event.verticalScrollPixels>0f)1 else -1
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(
                                (pagerState.currentPage+step).coerceIn(0,menuPages.lastIndex)
                            )
                        }
                    }
                    true
                }
                .focusRequester(bezelFocus).focusable(),
            state = pagerState) {page ->''')
change(ui,'''            }
        }
    }
}

fun buildMenuPages''',
'''            }
        }
        // Thin VB-like vertical display frame. Existing game sprites and actions unchanged.
        Canvas(Modifier.fillMaxSize()) {
            val frameWidth=size.width*0.73f
            val frameHeight=size.height*0.80f
            val left=(size.width-frameWidth)/2
            val top=(size.height-frameHeight)/2
            drawRoundRect(color=Color(0xFF62DBE4),
                topLeft=Offset(left,top),size=Size(frameWidth,frameHeight),
                cornerRadius=androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style=Stroke(width=1.6.dp.toPx()))
        }
    }
}

fun buildMenuPages''')
print("DONE audio, settings, digital bezel input and original vertical game frame")
