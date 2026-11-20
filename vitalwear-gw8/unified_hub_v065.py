#!/usr/bin/env python3
"""One reusable 438px native-pixel menu/detail UI with real game-backed actions."""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
out=root/"app/src/main/java/com/github/cfogrady/vitalwear/main/VbHubActivity.kt"
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(r'''package com.github.cfogrady.vitalwear.main

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.core.content.ContextCompat
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.background.BackgroundManager
import com.github.cfogrady.vitalwear.background.BackgroundSelectionActivity
import com.github.cfogrady.vitalwear.battle.BattleActivity
import com.github.cfogrady.vitalwear.character.activity.CharacterSelectActivity
import com.github.cfogrady.vitalwear.common.character.CharacterSprites
import com.github.cfogrady.vitalwear.adventure.AdventureMenuActivity
import com.github.cfogrady.vitalwear.composable.pixel.VbPixelArt as Art
import com.github.cfogrady.vitalwear.heartrate.HeartRateResult
import com.github.cfogrady.vitalwear.steps.HealthStepsBridge
import com.github.cfogrady.vitalwear.training.TrainingActivity
import com.github.cfogrady.vitalwear.training.TrainingType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

class VbHubActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val section=intent.getStringExtra("SECTION") ?: "STATUS"
        setContent { VbHubContent(this,section){finish()} }
    }
}

data class VbActionEntry(val title:String,val available:Boolean=true,val onTap:()->Unit)

/** Seven target-size rows, neon-lime selection, top/bottom rim and rotary navigation. */
@Composable
fun VbChoicePage(
    title:String,entries:List<VbActionEntry>,onBack:()->Unit
) {
    val focus=remember {FocusRequester()}
    var selected by remember(title){mutableIntStateOf(0)}
    var first by remember(title){mutableIntStateOf(0)}
    var accumulated by remember {mutableFloatStateOf(0f)}
    fun move(step:Int) {
        selected=(selected+step).coerceIn(0,(entries.size-1).coerceAtLeast(0))
        if(selected<first)first=selected
        if(selected>first+6)first=selected-6
    }
    BackHandler(onBack=onBack)
    LaunchedEffect(Unit){focus.requestFocus()}
    Canvas(Modifier.fillMaxSize()
        .onRotaryScrollEvent{
            accumulated+=it.verticalScrollPixels
            if(abs(accumulated)>23f) {
                move(if(accumulated>0)1 else -1)
                accumulated=0f
            }
            true
        }
        .focusRequester(focus).focusable()
        .pointerInput(selected,first,entries) {
            detectTapGestures { pos ->
                val k=min(size.width,size.height)/Art.DESIGN
                val x=(pos.x-(size.width-Art.DESIGN*k)/2f)/k
                val y=(pos.y-(size.height-Art.DESIGN*k)/2f)/k
                if(x<107f||x>334f){
                    if(y<219f)move(-1) else move(1)
                }else if(y>=103f && y<383f){
                    val i=first+((y-103f)/40f).toInt()
                    if(i in entries.indices){
                        if(selected==i){
                            if(entries[i].available)entries[i].onTap()
                        }else selected=i
                    }
                }else if(y<103f && y>=52f){
                    if(selected in entries.indices&&entries[selected].available)
                        entries[selected].onTap()
                }else if(y>390f){onBack()}
            }
        }) {
        drawIntoCanvas {
            val c=it.nativeCanvas
            val k=min(size.width,size.height)/Art.DESIGN
            c.save()
            c.translate((size.width-Art.DESIGN*k)/2f,(size.height-Art.DESIGN*k)/2f)
            c.scale(k,k)
            Art.menu(c,entries.map{e->e.title},
                entries.map{e->e.available},selected,first,title)
            c.restore()
        }
    }
}

/** A single high-contrast information panel, not a tiny multi-column status table. */
@Composable
fun VbDetailPage(
    title:String,hero:String,rows:List<Pair<String,String>>,
    sprite:Bitmap?=null,onBack:()->Unit,onNext:(()->Unit)?=null
) {
    BackHandler(onBack=onBack)
    Canvas(Modifier.fillMaxSize().pointerInput(onBack,onNext){
        detectTapGestures { p ->
            val k=min(size.width,size.height)/Art.DESIGN
            val x=(p.x-(size.width-Art.DESIGN*k)/2f)/k
            if(x>219f && onNext!=null)onNext() else onBack()
        }
    }) {
        drawIntoCanvas {
            val c=it.nativeCanvas
            val k=min(size.width,size.height)/Art.DESIGN
            c.save()
            c.translate((size.width-Art.DESIGN*k)/2f,(size.height-Art.DESIGN*k)/2f)
            c.scale(k,k)
            Art.details(c,title,hero,rows,sprite,
                if(onNext==null)"TAP TO RETURN" else "LEFT BACK RIGHT NEXT")
            c.restore()
        }
    }
}

/** All menu labels navigate to actual functions/stats; never fabricate item stock. */
@Composable
fun VbHubContent(activity:ComponentActivity,section:String,onExit:()->Unit) {
    val app=activity.application as VitalWearApp
    val partner by app.characterManager.getCharacterFlow().collectAsState()
    val steps by app.stepService.dailySteps.collectAsState()
    val bpm by app.heartRateService.lastHeartRate.collectAsState()
    val prefs=remember {activity.getSharedPreferences("digivice_sfx",Context.MODE_PRIVATE)}
    var sound by remember {mutableStateOf(prefs.getBoolean("enabled",true))}
    var volume by remember {mutableFloatStateOf(prefs.getFloat("volume",0.65f))}
    var info by remember(section){mutableStateOf<String?>(null)}
    var notice by remember(section){mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun launch(type:Class<out android.app.Activity>) {
        activity.startActivity(Intent(activity,type))
    }
    fun training(t:TrainingType) {
        activity.startActivity(Intent(activity,TrainingActivity::class.java)
            .putExtra(TrainingActivity.TRAINING_TYPE,t))
    }
    fun background(battle:Boolean=false){
        val i=Intent(activity,BackgroundSelectionActivity::class.java)
        if(battle) i.putExtra(
            BackgroundSelectionActivity.BACKGROUND_TYPE,
            BackgroundManager.BackgroundType.Battle.ordinal)
        activity.startActivity(i)
    }
    fun item(name:String,action:()->Unit={}):VbActionEntry=VbActionEntry(name,true,action)
    val entries=when(section) {
        "STATUS" -> listOf(
            item("VITAL"){info="VITAL"},
            item("HEART RATE"){info="HEART RATE"},
            item("MILE"){info="MILE"},
            item("RETURN"){onExit()}
        )
        "DIGIMON" -> listOf(
            item("BASIC INFO"){info="BASIC INFO"},
            item("STATUS"){info="PARTNER STATS"},
            item("BATTLE RECORD"){info="BATTLE RECORD"},
            item("TROPHIES"){info="TROPHIES"},
            item("EVOLUTION"){info="EVOLUTION"},
            item("CHANGE"){launch(CharacterSelectActivity::class.java)},
            item("RETURN"){onExit()}
        )
        "TRAINING" -> listOf(
            item("RUNNING"){training(TrainingType.DASH)},
            item("SQUAT"){training(TrainingType.SQUAT)},
            item("PUNCH"){training(TrainingType.PUNCH)},
            item("SIT-UP"){training(TrainingType.CRUNCH)},
            item("RETURN"){onExit()}
        )
        "ADVENTURE" -> listOf(
            item("START"){launch(AdventureMenuActivity::class.java)},
            item("PROGRESS"){info="ADVENTURE PROGRESS"},
            item("RETURN"){onExit()}
        )
        "MISSION" -> listOf(
            item("NORMAL"){info="NORMAL MISSION"},
            item("HARD"){info="HARD MISSION"},
            item("SPECIAL"){info="SPECIAL MISSION"},
            item("ADVENTURE"){launch(AdventureMenuActivity::class.java)},
            item("RETURN"){onExit()}
        )
        "BATTLE" -> listOf(
            item("START"){launch(BattleActivity::class.java)},
            item("OPPONENT"){info="OPPONENT"},
            item("RECORD"){info="BATTLE RECORD"},
            item("RETURN"){onExit()}
        )
        "ITEMS" -> listOf(
            item("FEED"){info="FEED"},
            item("MEDICINE"){info="MEDICINE"},
            item("CARD"){info="CARD"},
            item("OTHER"){info="OTHER"},
            item("RETURN"){onExit()}
        )
        "SENSOR" -> listOf(
            item("HEART RATE"){info="HEART RATE"},
            item("STEPS"){info="MILE"},
            item("CALIBRATE"){
                scope.launch {
                    notice="MEASURING"
                    val v=app.heartRateService.calibrateRestingHeartRate()
                    notice=if(v.heartRateError==HeartRateResult.Companion.HeartRateError.NONE)
                        "BASE "+app.heartRateService.restingHeartRate() else "CALIBRATE FAILED"
                    info="SENSOR RESULT"
                }
            },
            item("RETRY STEPS"){
                val granted=ContextCompat.checkSelfPermission(
                    activity,Manifest.permission.ACTIVITY_RECOGNITION)==
                    PackageManager.PERMISSION_GRANTED
                if(granted){
                    app.stepService.startup()
                    HealthStepsBridge.register(activity)
                    notice="WALK 30 STEPS"
                }else notice="ALLOW ACTIVITY"
                info="SENSOR RESULT"
            },
            item("RETURN"){onExit()}
        )
        "SETTINGS" -> listOf(
            item("DISPLAY"){background()},
            item(if(sound)"SOUND ON" else "SOUND OFF"){
                sound=!sound
                prefs.edit().putBoolean("enabled",sound).apply()
            },
            item("VOLUME"){
                volume=if(volume<0.4f)0.7f else if(volume<0.9f)1f else 0.25f
                prefs.edit().putFloat("volume",volume).apply()
                notice=(volume*100).toInt().toString()+" PCT"
                info="VOLUME"
            },
            item("BATTLE BG"){background(true)},
            item("SENSOR"){launch(VbHubActivity::class.java)
                activity.startActivity(Intent(activity,VbHubActivity::class.java)
                    .putExtra("SECTION","SENSOR"))},
            item("SAVE"){
                app.saveService.saveAsync()
                info="SAVE"
            },
            item("ABOUT"){info="ABOUT"},
            item("RETURN"){onExit()}
        )
        else -> listOf(item("RETURN"){onExit()})
    }
    if(info==null) {
        VbChoicePage(section,entries,onExit)
    } else {
        val pet=partner
        val cs=pet?.characterStats
        val hero:String
        val rows:List<Pair<String,String>>
        val png=if(section=="DIGIMON")
            pet?.characterSprites?.sprites?.getOrNull(CharacterSprites.IDLE_1) else null
        val display=info!!
        when(display){
            "VITAL" -> {
                hero=(cs?.vitals?:0).toString()
                rows=listOf("VITALS" to hero,"MOOD" to (cs?.mood?:0).toString())
            }
            "HEART RATE" -> {
                hero=if(bpm>0)"$bpm BPM" else "NO READING"
                rows=listOf(
                    "CURRENT" to (if(bpm>0)"$bpm" else "--"),
                    "BASE" to app.heartRateService.restingHeartRate().toString(),
                    "STEPS" to steps.toString()
                )
            }
            "MILE" -> {
                hero=steps.toString()
                rows=listOf("STEPS" to steps.toString(),"DISTANCE" to "NOT CALIB")
            }
            "BASIC INFO" -> {
                hero=pet?.cardName()?.take(12)?:"NO PARTNER"
                rows=listOf(
                    "PHASE" to ((pet?.speciesStats?.phase?:-1)+1).toString(),
                    "AGE DAYS" to (cs?.ageInDays?:0).toString(),
                    "ATTRIBUTE" to (pet?.speciesStats?.attribute?.toString()?:"--")
                )
            }
            "PARTNER STATS" -> {
                hero="POWER"
                rows=listOf("BP" to (pet?.totalBp()?:0).toString(),
                    "HP" to (pet?.totalHp()?:0).toString(),
                    "AP" to (pet?.totalAp()?:0).toString(),
                    "PP" to (cs?.trainedPP?:0).toString())
            }
            "BATTLE RECORD" -> {
                hero=(cs?.totalWins?:0).toString()+" WINS"
                rows=listOf(
                    "BATTLES" to (cs?.totalBattles?:0).toString(),
                    "WINS" to (cs?.totalWins?:0).toString(),
                    "WIN RATE" to (cs?.currentPhaseWinRatio()?:0).toString()+"%"
                )
            }
            "TROPHIES" -> {
                hero=(cs?.totalTrophies?:0).toString()
                rows=listOf("TROPHIES" to hero,"TRAIN PP" to (cs?.trainedPP?:0).toString())
            }
            "EVOLUTION" -> {
                val secs=(cs?.timeUntilNextTransformation?:0L).coerceAtLeast(0)
                hero=if(pet?.hasPotentialTransformations()!=true) "FINAL FORM"
                    else (secs/3600).toString()+"H "+((secs%3600)/60).toString()+"M"
                val opt=pet?.transformationOptions?.firstOrNull()
                rows=if(opt==null)listOf("NEXT" to "NONE")
                else listOf("VITALS" to opt.requiredVitals.toString(),
                    "BATTLES" to opt.requiredBattles.toString(),
                    "WIN RATE" to opt.requiredWinRatio.toString()+"%",
                    "PP" to opt.requiredPp.toString())
            }
            "ADVENTURE PROGRESS" -> {
                hero="AREA "+((cs?.nextAdventureMissionStage?:0)+1)
                rows=listOf("STAGE" to (cs?.nextAdventureMissionStage?:0).toString(),
                    "STATUS" to "FROM SAVE")
            }
            "NORMAL MISSION","HARD MISSION","SPECIAL MISSION" -> {
                val goal=when(display){"NORMAL MISSION"->2000;"HARD MISSION"->5000;else->10000}
                hero=if(steps>=goal)"GOAL MET" else "$steps/$goal"
                rows=listOf("TYPE" to "CUSTOM WALK","STEPS" to steps.toString(),
                    "TARGET" to goal.toString(),"REWARD" to "NONE")
            }
            "OPPONENT" -> {
                hero="RANDOM"
                rows=listOf("BATTLE" to "ACTIVE PET","START" to "BACK MENU")
            }
            "FEED","MEDICINE","CARD","OTHER" -> {
                hero="NOT AVAILABLE"
                rows=listOf("ITEM" to display,"ENGINE" to "NO INVENTORY")
            }
            "SENSOR RESULT" -> {
                hero=notice.ifBlank { "NO READING" }
                rows=listOf("STEPS" to steps.toString(),"BPM" to
                    (if(bpm>0)bpm.toString() else "--"))
            }
            "VOLUME" -> {
                hero=notice
                rows=listOf("SOUND" to if(sound)"ON" else "OFF")
            }
            "SAVE" -> {
                hero="SAVE REQUEST"
                rows=listOf("STATUS" to "QUEUED")
            }
            "ABOUT" -> {
                hero="VB GAME"
                rows=listOf("VERSION" to "0.6.5","WATCH" to "40 MM",
                    "TRANSFER" to "VB HELPER")
            }
            else -> {
                hero="--"
                rows=emptyList()
            }
        }
        VbDetailPage(display,hero,rows,png,onBack={info=null})
    }
}
''',encoding="utf-8")
print("UNIFIED HUB REAL SCREENS WRITTEN",out)
