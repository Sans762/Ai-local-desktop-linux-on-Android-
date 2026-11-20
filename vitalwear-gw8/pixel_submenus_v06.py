#!/usr/bin/env python3
"""Uniform pixel UI for Settings / sensors / character actions.
Applied after add_sensor_screen.py, add_vb_ux_sound.py and pixel_ui_v06.py.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base="app/src/main/java/com/github/cfogrady/vitalwear/"
def write(path,src):
    p=root/path;p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(src,encoding="utf-8")
    print("PIXEL SUBMENU",path)

write(base+"settings/SettingsComposableFactory.kt",r'''package com.github.cfogrady.vitalwear.settings

import android.content.Context
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.cfogrady.vitalwear.R
import com.github.cfogrady.vitalwear.SaveService
import com.github.cfogrady.vitalwear.background.BackgroundManager
import com.github.cfogrady.vitalwear.background.BackgroundSelectionActivity
import com.github.cfogrady.vitalwear.composable.util.BitmapScaler
import com.github.cfogrady.vitalwear.composable.util.VitalBoxFactory
import com.github.cfogrady.vitalwear.composable.pixel.PixelButton
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText
import com.github.cfogrady.vitalwear.log.LogSettings
import com.github.cfogrady.vitalwear.sound.PetSfx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** One visual language for settings, sound, BPM, and battle backgrounds. */
class SettingsComposableFactory(
    private val backgroundManager:BackgroundManager,
    private val vitalBoxFactory:VitalBoxFactory,
    private val bitmapScaler:BitmapScaler,
    private val logSettings:LogSettings,
    private val saveService:SaveService
) {
    private data class Action(val title:String,val perform:()->Unit)

    @Composable fun SettingsMenu(activityLauncher:SettingsActivityLauncher) {
        val ctx=LocalContext.current
        val prefs=remember(ctx) {
            ctx.getSharedPreferences("digivice_sfx",Context.MODE_PRIVATE)
        }
        var sound by remember{mutableStateOf(prefs.getBoolean("enabled",true))}
        var quiet by remember{mutableStateOf(prefs.getBoolean("quiet_hours",false))}
        var volume by remember{mutableFloatStateOf(prefs.getFloat("volume",0.65f))}
        var logging by remember{mutableStateOf(logSettings.loggingEnabled())}
        val scope=rememberCoroutineScope()
        val actions=listOf(
            Action("BACKGROUND"){activityLauncher.backgroundSelection.invoke{}},
            Action("BATTLE CARD BG"){backgroundManager.setBattleBackgroundPartner()},
            Action("BATTLE ENEMY BG"){backgroundManager.setBattleBackgroundOpponent()},
            Action("BATTLE CUSTOM BG"){
                activityLauncher.backgroundSelection {
                    it.putExtra(
                        BackgroundSelectionActivity.BACKGROUND_TYPE,
                        BackgroundManager.BackgroundType.Battle.ordinal
                    )
                }
            },
            Action(if(sound)"SOUND ON" else "SOUND OFF"){
                sound=!sound
                prefs.edit().putBoolean("enabled",sound).apply()
            },
            Action("VOLUME "+(volume*100).toInt()+"%"){
                volume=if(volume<0.35f)0.65f else if(volume<0.8f)1f else 0.25f
                prefs.edit().putFloat("volume",volume).apply()
                PetSfx.play(ctx,R.raw.digivice_menu)
            },
            Action("TEST SOUND"){PetSfx.play(ctx,R.raw.digivice_win)},
            Action(if(quiet)"NIGHT MUTE ON" else "NIGHT MUTE OFF"){
                quiet=!quiet
                prefs.edit().putBoolean("quiet_hours",quiet).apply()
            },
            Action("STEPS + HEART"){activityLauncher.sensorDiagnostics()},
            Action(if(logging)"DISABLE LOGS" else "ENABLE LOGS"){
                logging=logSettings.toggleLogging()
            },
            Action("SAVE GAME"){
                scope.launch(Dispatchers.IO) {
                    saveService.save()
                    withContext(Dispatchers.Main){activityLauncher.toast.invoke("Saved")}
                }
            }
        )
        val list=rememberLazyListState()
        val focus=remember{FocusRequester()}
        var selected by remember{mutableIntStateOf(0)}
        var rotation by remember{mutableFloatStateOf(0f)}
        LaunchedEffect(Unit){focus.requestFocus()}
        PixelShell {
            Column(Modifier.fillMaxSize()
                .onRotaryScrollEvent {
                    rotation+=it.verticalScrollPixels
                    if(abs(rotation)>22f) {
                        selected=(selected+if(rotation>0)1 else -1)
                            .coerceIn(0,actions.lastIndex)
                        rotation=0f
                        scope.launch{list.animateScrollToItem(selected)}
                    }
                    true
                }
                .focusRequester(focus).focusable()
                .padding(horizontal=9.dp,vertical=16.dp)) {
                PixelText("SETTINGS",height=15.dp,color=PixelPalette.cyan)
                Spacer(Modifier.height(9.dp))
                LazyColumn(state=list,
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    itemsIndexed(actions) { index,action ->
                        PixelButton(action.title,selected=selected==index,
                            onClick={selected=index;action.perform()})
                    }
                }
            }
        }
    }

    enum class SettingsMenuOption {
        Background,BattleBackground,ToggleLogging,Sensors,Sound,Save
    }
    fun buildSettingMenuPages():List<SettingsMenuOption> = listOf(
        SettingsMenuOption.Background,SettingsMenuOption.BattleBackground,
        SettingsMenuOption.ToggleLogging,SettingsMenuOption.Sensors,
        SettingsMenuOption.Sound,SettingsMenuOption.Save
    )
}
''')

write(base+"heartrate/SensorDiagnosticsActivity.kt",r'''package com.github.cfogrady.vitalwear.heartrate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.steps.HealthStepsBridge
import com.github.cfogrady.vitalwear.composable.pixel.PixelButton
import com.github.cfogrady.vitalwear.composable.pixel.PixelPalette
import com.github.cfogrady.vitalwear.composable.pixel.PixelShell
import com.github.cfogrady.vitalwear.composable.pixel.PixelText
import kotlinx.coroutines.launch

class SensorDiagnosticsActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent{Diagnostics()}
    }
    @Composable private fun Diagnostics() {
        val app=application as VitalWearApp
        val steps by app.stepService.dailySteps.collectAsState()
        val sensor by app.stepService.sensorStatus.collectAsState()
        val bpm by app.heartRateService.lastHeartRate.collectAsState()
        val scope=rememberCoroutineScope()
        var result by remember{mutableStateOf("WEAR WATCH TIGHT")}
        val granted=ContextCompat.checkSelfPermission(this,Manifest.permission.ACTIVITY_RECOGNITION)==
            PackageManager.PERMISSION_GRANTED
        PixelShell {
            Column(Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal=7.dp,vertical=16.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                PixelText("SENSOR",height=14.dp,color=PixelPalette.cyan)
                PixelText("STEPS:"+steps,height=10.dp,color=PixelPalette.lime)
                PixelText("BPM:"+(if(bpm>0)bpm.toString() else "--"),height=10.dp)
                PixelText("REST:"+app.heartRateService.restingHeartRate(),height=9.dp)
                PixelButton("RETRY STEPS",onClick={
                    if(!granted) result="ALLOW ACTIVITY"
                    else {
                        app.stepService.startup()
                        HealthStepsBridge.register(this@SensorDiagnosticsActivity)
                        result="WALK 30 STEPS"
                    }
                })
                PixelButton("MEASURE BPM",onClick={
                    scope.launch {
                        result="MEASURING"
                        val r=app.heartRateService.getExerciseLevel(0).heartRate
                        result=if(r.heartRateError==HeartRateResult.Companion.HeartRateError.NONE)
                            "HR "+r.heartRate else "NO HR SIGNAL"
                    }
                })
                PixelButton("SET REST BPM",onClick={
                    scope.launch {
                        val r=app.heartRateService.calibrateRestingHeartRate()
                        result=if(r.heartRateError==HeartRateResult.Companion.HeartRateError.NONE)
                            "BASE "+app.heartRateService.restingHeartRate()
                        else "CALIBRATE FAIL"
                    }
                })
                PixelText(result,height=8.dp,color=PixelPalette.lime)
                PixelText(sensor.take(24),height=7.dp,color=PixelPalette.muted)
            }
        }
    }
}
''')

char=root/(base+"character/activity/CharacterSelection.kt")
s=char.read_text(encoding="utf-8-sig")
begin=s.index('    if(showMenu) {',s.index('private fun PreviewCharacter('))
end=s.index('    } else {',begin)
replacement='''    if(showMenu) {
        com.github.cfogrady.vitalwear.composable.pixel.PixelShell {
            Column(modifier=Modifier.fillMaxSize().padding(horizontal=10.dp),
                horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.SpaceEvenly) {
                com.github.cfogrady.vitalwear.composable.pixel.PixelButton(
                    "SELECT",onClick={controller.swapToCharacter(character)})
                com.github.cfogrady.vitalwear.composable.pixel.PixelButton(
                    "SUPPORT",onClick={onSetSupport();showMenu=false})
                com.github.cfogrady.vitalwear.composable.pixel.PixelButton(
                    "DELETE",onClick={onDelete();showMenu=false})
            }
        }
'''
s=s[:begin]+replacement+s[end:]
char.write_text(s,encoding="utf-8")
print("PIXEL CHARACTER ACTIONS",char)
print("CONSISTENT SETTINGS / SENSOR / CHARACTER CONTROLS READY")
