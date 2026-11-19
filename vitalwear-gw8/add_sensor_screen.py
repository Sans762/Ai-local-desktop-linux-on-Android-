#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear").resolve()
a="app/src/main/java/com/github/cfogrady/vitalwear/"
def e(path,x,y):
 p=root/path;s=p.read_text(encoding="utf-8-sig")
 assert s.count(x)==1,(path,s.count(x),x[:75])
 p.write_text(s.replace(x,y),encoding="utf-8");print("EDIT",path,x[:38])
p=root/(a+"heartrate/SensorDiagnosticsActivity.kt");p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(r'''package com.github.cfogrady.vitalwear.heartrate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.steps.HealthStepsBridge
import kotlinx.coroutines.launch

/** Game diagnostics; measurements only while the app has sensor permission. */
class SensorDiagnosticsActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Diagnostics() }
    }
    @Composable fun Diagnostics() {
        val app=application as VitalWearApp
        val steps by app.stepService.dailySteps.collectAsState()
        val source by app.stepService.sensorStatus.collectAsState()
        val bpm by app.heartRateService.lastHeartRate.collectAsState()
        val scope=rememberCoroutineScope()
        var notice by remember { mutableStateOf("Wear the watch snugly") }
        val allowed=ContextCompat.checkSelfPermission(this,Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED
        Column(
            modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center
        ) {
            Text("DIGIVICE SENSOR CHECK")
            Text("Steps today: "+steps)
            Text(source)
            Text("Heart: "+(if(bpm>0) bpm.toString() else "--")+" BPM")
            Text("Resting: "+app.heartRateService.restingHeartRate()+" BPM")
            Text(notice)
            Button(onClick={
                if(!allowed) {
                    notice="Allow Physical Activity in app permissions"
                } else {
                    app.stepService.startup()
                    HealthStepsBridge.register(this@SensorDiagnosticsActivity)
                    notice="Walk 30-50 steps, then check the counter"
                }
            }) { Text("Retry Steps") }
            Button(onClick={
                scope.launch {
                    notice="Measuring BPM..."
                    val r=app.heartRateService.getExerciseLevel(0).heartRate
                    notice=if(r.heartRateError==HeartRateResult.Companion.HeartRateError.NONE)
                        "Measured "+r.heartRate+" BPM"
                    else "BPM error: "+r.heartRateError
                }
            }) { Text("Measure Heart") }
            Button(onClick={
                scope.launch {
                    notice="Calibrating while resting..."
                    val r=app.heartRateService.calibrateRestingHeartRate()
                    notice=if(r.heartRateError==HeartRateResult.Companion.HeartRateError.NONE)
                        "Resting BPM: "+app.heartRateService.restingHeartRate()
                    else "Calibration error: "+r.heartRateError
                }
            }) { Text("Set Resting BPM") }
        }
    }
}
''',encoding="utf-8")
print("WRITE",p)
e(a+"settings/SettingsActivityLauncher.kt",
'''    val backgroundSelection: ((Intent) -> Unit) -> Unit''',
'''    val backgroundSelection: ((Intent) -> Unit) -> Unit,
    val sensorDiagnostics: () -> Unit''')
e(a+"settings/SettingsActivity.kt",
'import com.github.cfogrady.vitalwear.VitalWearApp',
'import com.github.cfogrady.vitalwear.VitalWearApp\nimport com.github.cfogrady.vitalwear.heartrate.SensorDiagnosticsActivity')
e(a+"settings/SettingsActivity.kt",
'''            activityHelper.getActivityLauncherWithResultHandling(BackgroundSelectionActivity::class.java) {

            },
''',
'''            activityHelper.getActivityLauncherWithResultHandling(BackgroundSelectionActivity::class.java) {

            },
            { startActivity(Intent(this, SensorDiagnosticsActivity::class.java)) },
''')
e(a+"settings/SettingsComposableFactory.kt",
'''                    SettingsMenuOption.Save -> {''',
'''                    SettingsMenuOption.Sensors -> {
                        SettingsPageContainer(modifier = Modifier.clickable {
                            activityLauncher.sensorDiagnostics()
                        }) {
                            Text(text = "STEPS + BPM", fontWeight = FontWeight.Bold, fontSize = 2.0.em)
                        }
                    }
                    SettingsMenuOption.Save -> {''')
e(a+"settings/SettingsComposableFactory.kt",
'''        Save
    }''',
'''        Save,
        Sensors
    }''')
e(a+"settings/SettingsComposableFactory.kt",
'''            SettingsMenuOption.ToggleLogging,
            SettingsMenuOption.Save)''',
'''            SettingsMenuOption.ToggleLogging,
            SettingsMenuOption.Sensors,
            SettingsMenuOption.Save)''')
print("DIAGNOSTICS OK")
