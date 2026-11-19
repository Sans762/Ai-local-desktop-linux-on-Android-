#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear").resolve()
a="app/src/main/java/com/github/cfogrady/vitalwear/"
def write(path,content):
 p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding="utf-8");print("WRITE",path)
def edit(path,before,after,n=1):
 p=root/path;src=p.read_text(encoding="utf-8-sig");ct=src.count(before)
 if ct!=n: raise RuntimeError(path+" expected "+str(n)+" matches, got "+str(ct)+" for "+before[:70])
 p.write_text(src.replace(before,after),encoding="utf-8");print("PATCH",path,before[:55])

write(a+"steps/StepSensorService.kt",r'''package com.github.cfogrady.vitalwear.steps

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.github.cfogrady.vitalwear.SaveService
import com.github.cfogrady.vitalwear.util.SensorThreadHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Native boot-cumulative counter + Wear Health Services daily counter, deduplicated. */
class StepSensorService(
    private val sensorManager: SensorManager,
    private val sensorThreadHandler: SensorThreadHandler,
    private val stepChangeListeners: List<StepChangeListener>,
    private val stepState: StepState,
    private val stepIOService: StepIOService,
    private val saveService: SaveService,
    private val dateTimeFetcher: () -> LocalDateTime = LocalDateTime::now
): StepService, SensorEventListener {
    override val dailySteps: StateFlow<Int> = stepState.dailySteps
    private val _recentStepTime=MutableStateFlow(LocalDateTime.now().minusMinutes(10))
    override val timeFrom10StepsAgo: StateFlow<LocalDateTime> = _recentStepTime
    private val _sensorStatus=MutableStateFlow("Permission required")
    val sensorStatus: StateFlow<String> = _sensorStatus
    private var registered=false
    private var initialized=false
    private var currentDay:LocalDate?=null
    private var nativeBaseline=0
    private var lastNative:Int?=null

    @Synchronized fun startup() {
        if (registered) return
        try {
            val sensor=sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            if (sensor==null) {
                _sensorStatus.value="Health Services only"
                return
            }
            registered=sensorManager.registerListener(
                this,sensor,SensorManager.SENSOR_DELAY_NORMAL,sensorThreadHandler.handler)
            _sensorStatus.value=if (registered) "Native step sensor active" else "Native step sensor unavailable"
        } catch(e:SecurityException) {
            _sensorStatus.value="Physical Activity permission missing"
            Timber.w(e,"Step sensor requires permission")
        } catch(e:Exception) {
            _sensorStatus.value="Step error: "+e.javaClass.simpleName
            Timber.w(e,"Step sensor startup error")
        }
    }

    fun stepSensorEnabled()=initialized

    @Synchronized fun onHealthDailySteps(steps:Long) {
        if(steps<0) return
        ensureDay(dateTimeFetcher().toLocalDate())
        _sensorStatus.value="Wear Health Services active"
        acceptDaily(steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun ensureDay(today:LocalDate) {
        if(!initialized) {
            val saved=stepIOService.stepPreferences()
            stepState.dailySteps.value=if(saved.dateOfLastSave==today) saved.dailySteps.coerceAtLeast(0) else 0
            initialized=true
            stepState.stepSensorEnabled=true
            currentDay=today
            stepState.dateOfLastRead=today
        } else if(currentDay!=today) {
            currentDay=today
            stepState.dateOfLastRead=today
            stepState.dailySteps.value=0
            nativeBaseline=0
            lastNative=null
            saveService.saveAsync()
        }
    }

    private fun acceptDaily(count:Int) {
        val previous=stepState.dailySteps.value
        if(count<=previous)return
        stepState.dailySteps.value=count
        var changed=false
        for(listener in stepChangeListeners) {
            if(listener.processStepChanges(previous,count))changed=true
        }
        _recentStepTime.value=dateTimeFetcher().minusSeconds(10)
        // Save current count even for batches smaller than 50 steps.
        saveService.saveAsync()
        Timber.i("Daily steps new="+count+" delta="+(count-previous)+" vitalsChanged="+changed)
    }

    override fun onSensorChanged(event:SensorEvent?) {
        if(event==null || event.values.isEmpty())return
        synchronized(this) {
            ensureDay(dateTimeFetcher().toLocalDate())
            if(event.sensor.type==Sensor.TYPE_STEP_COUNTER) {
                val counter=event.values[0].toInt()
                if(counter<0)return
                val previous=lastNative
                if(previous==null || counter<previous) {
                    // Rebase without granting phantom steps after reboot/restart.
                    nativeBaseline=counter-stepState.dailySteps.value
                    lastNative=counter
                    return
                }
                lastNative=counter
                acceptDaily((counter-nativeBaseline).coerceAtLeast(0))
            } else if(event.sensor.type==Sensor.TYPE_STEP_DETECTOR) {
                acceptDaily(stepState.dailySteps.value+1)
            }
        }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    override fun hasRecentSteps(now:LocalDateTime):Boolean {
        val diff=ChronoUnit.SECONDS.between(timeFrom10StepsAgo.value,now)
        return diff in 0..120
    }
}
''')

write(a+"steps/HealthStepsBridge.kt",r'''package com.github.cfogrady.vitalwear.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.github.cfogrady.vitalwear.VitalWearApp
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

object HealthStepsBridge {
    private val registered=AtomicBoolean(false)
    fun register(context:Context) {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED)return
        if(!registered.compareAndSet(false,true))return
        try {
            val client=HealthServices.getClient(context).passiveMonitoringClient
            val config=PassiveListenerConfig.builder()
                .setDataTypes(setOf(DataType.STEPS_DAILY)).build()
            val task=client.setPassiveListenerServiceAsync(PassiveStepsService::class.java,config)
            task.addListener({
                try { task.get(); Timber.i("Health Services STEPS_DAILY registered") }
                catch(e:Exception) {
                    registered.set(false)
                    Timber.w(e,"Health Services steps registration failed")
                }
            },java.util.concurrent.Executor { it.run() })
        } catch(e:Exception) {
            registered.set(false)
            Timber.w(e,"No Health Services passive steps")
        }
    }
}
class PassiveStepsService:PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints:DataPointContainer) {
        val latest=dataPoints.getData(DataType.STEPS_DAILY)
            .maxByOrNull { it.endDurationFromBoot }?.value ?: return
        (application as? VitalWearApp)?.stepService?.onHealthDailySteps(latest)
    }
}
''')
edit("app/build.gradle.kts",'    implementation(libs.androidx.lifecycle.service)',
'    implementation(libs.androidx.lifecycle.service)\n    implementation("androidx.health:health-services-client:1.1.0")')
edit("app/src/main/AndroidManifest.xml",
'    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION"/>',
'    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION"/>\n    <uses-permission android:name="android.permission.health.READ_HEART_RATE"/>')
edit("app/src/main/AndroidManifest.xml",
'    <uses-permission android:name="android.permission.BODY_SENSORS" />',
'    <uses-permission android:name="android.permission.BODY_SENSORS" android:maxSdkVersion="35" />')
edit("app/src/main/AndroidManifest.xml",
'    <uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" />',
'    <uses-permission android:name="android.permission.BODY_SENSORS_BACKGROUND" android:maxSdkVersion="35" />')
edit("app/src/main/AndroidManifest.xml",
'        <service android:name=".training.TrainingForegroundService"',
'        <service android:name=".steps.PassiveStepsService" android:exported="true" android:permission="com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING" />\n        <activity android:name=".heartrate.SensorDiagnosticsActivity" android:exported="false" />\n        <service android:name=".training.TrainingForegroundService"')

edit(a+"main/MainActivity.kt",
'import com.github.cfogrady.vitalwear.SaveDataRepository',
'import com.github.cfogrady.vitalwear.SaveDataRepository\nimport com.github.cfogrady.vitalwear.steps.HealthStepsBridge')
edit(a+"main/MainActivity.kt",
'''    override fun onStart() {
        super.onStart()
        val characterManager''',
'''    override fun onStart() {
        super.onStart()
        restartSteps()
        val characterManager''')
edit(a+"main/MainActivity.kt",
'    private fun buildActivityLaunchers(): ActivityLaunchers {',
'''    override fun onResume() {
        super.onResume()
        restartSteps()
    }
    private fun restartSteps() {
        if(hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            (application as VitalWearApp).stepService.startup()
            HealthStepsBridge.register(this)
        }
    }

    private fun buildActivityLaunchers(): ActivityLaunchers {''')
edit(a+"main/MainActivity.kt",
'''                if(deniedPermissions.isNotEmpty()) {
                    Toast.makeText(this, "Denied permissions may cause bugs", Toast.LENGTH_SHORT).show()''',
'''                restartSteps()
                if(deniedPermissions.isNotEmpty()) {
                    Toast.makeText(this, "Denied permissions may cause bugs", Toast.LENGTH_SHORT).show()''')
edit(a+"main/MainActivity.kt",
'''        if (!hasPermission(Manifest.permission.BODY_SENSORS)) {
            missingPermissions.add(Manifest.permission.BODY_SENSORS)
        }''',
'''        val heartPermission=if(Build.VERSION.SDK_INT>=36)
            "android.permission.health.READ_HEART_RATE"
        else Manifest.permission.BODY_SENSORS
        if(!hasPermission(heartPermission))missingPermissions.add(heartPermission)''')
edit(a+"main/MainActivity.kt",
'''        if(Build.VERSION.SDK_INT >= 33) {
            if (!hasPermission(Manifest.permission.BODY_SENSORS_BACKGROUND)) {''',
'''        if(Build.VERSION.SDK_INT in 33..35) {
            if (!hasPermission(Manifest.permission.BODY_SENSORS_BACKGROUND)) {''')

edit(a+"ApplicationBootManager.kt",
'import com.github.cfogrady.vitalwear.steps.StepSensorService',
'import com.github.cfogrady.vitalwear.steps.StepSensorService\nimport com.github.cfogrady.vitalwear.steps.HealthStepsBridge')
edit(a+"ApplicationBootManager.kt",
'                    runCatching { stepService.startup() }',
'''                    runCatching {
                        stepService.startup()
                        HealthStepsBridge.register(context)
                    }''')

write(a+"heartrate/SingleHeartRateSensorListener.kt",r'''package com.github.cfogrady.vitalwear.heartrate

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.github.cfogrady.vitalwear.util.SensorThreadHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

class SingleHeartRateSensorListener(
    private val sensorManager:SensorManager,
    sensorThreadHandler:SensorThreadHandler
):SensorEventListener {
    private val reading=CompletableDeferred<Int>()
    @Volatile private var accuracy=HeartRateResult.Companion.HeartRateError.NONE
    private var registered=false
    init {
        val sensor=sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if(sensor==null) {
            accuracy=HeartRateResult.Companion.HeartRateError.UNAVAILABLE
            reading.complete(0)
        } else try {
            registered=sensorManager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL,sensorThreadHandler.handler)
            if(!registered) {
                accuracy=HeartRateResult.Companion.HeartRateError.UNAVAILABLE
                reading.complete(0)
            }
        } catch(e:SecurityException) {
            Timber.w(e,"Heart permission denied")
            accuracy=HeartRateResult.Companion.HeartRateError.UNAVAILABLE
            reading.complete(0)
        }
    }
    suspend fun getValue():HeartRateResult {
        val bpm=try { withTimeoutOrNull(15000L) { reading.await() } ?: 0 }
                finally { if(registered) sensorManager.unregisterListener(this) }
        val reason=when {
            accuracy!=HeartRateResult.Companion.HeartRateError.NONE -> accuracy
            bpm !in 35..230 -> HeartRateResult.Companion.HeartRateError.UNAVAILABLE
            else -> HeartRateResult.Companion.HeartRateError.NONE
        }
        return HeartRateResult(if(reason==HeartRateResult.Companion.HeartRateError.NONE) bpm else 0, reason)
    }
    override fun onSensorChanged(event:SensorEvent?) {
        val bpm=event?.values?.firstOrNull()?.toInt() ?: 0
        if(bpm in 35..230 && !reading.isCompleted)reading.complete(bpm)
    }
    override fun onAccuracyChanged(sensor:Sensor?,value:Int) {
        accuracy=when(value) {
            SensorManager.SENSOR_STATUS_NO_CONTACT -> HeartRateResult.Companion.HeartRateError.NO_CONTACT
            SensorManager.SENSOR_STATUS_UNRELIABLE -> HeartRateResult.Companion.HeartRateError.UNRELIABLE
            else -> HeartRateResult.Companion.HeartRateError.NONE
        }
    }
}
''')
write(a+"heartrate/HeartRateService.kt",r'''package com.github.cfogrady.vitalwear.heartrate

import android.content.Context
import android.hardware.SensorManager
import com.github.cfogrady.vitalwear.util.SensorThreadHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/** On-watch PPG gaming measurements, not a medical instrument. */
class HeartRateService(
    private val sensorManager:SensorManager,
    private val sensorThreadHandler:SensorThreadHandler,
    context:Context
) {
    private val prefs=context.applicationContext.getSharedPreferences("digivice_heart",Context.MODE_PRIVATE)
    private val _currentExerciseLevel=MutableStateFlow(0)
    val currentExerciseLevel:StateFlow<Int> = _currentExerciseLevel
    private val _lastHeartRate=MutableStateFlow(0)
    val lastHeartRate:StateFlow<Int> = _lastHeartRate
    fun restingHeartRate():Int=prefs.getInt("resting_bpm",65)
    private suspend fun read()=SingleHeartRateSensorListener(sensorManager,sensorThreadHandler).getValue()
    suspend fun calibrateRestingHeartRate():HeartRateResult {
        val result=read()
        if(result.heartRateError==HeartRateResult.Companion.HeartRateError.NONE) {
            _lastHeartRate.value=result.heartRate
            if(result.heartRate in 40..120) {
                prefs.edit().putInt("resting_bpm",result.heartRate).apply()
            }
        }
        return result
    }
    class ExerciseLevel(val level:Int,val heartRate:HeartRateResult)
    suspend fun getExerciseLevel(lastLevel:Int):ExerciseLevel {
        val result=read()
        if(result.heartRateError!=HeartRateResult.Companion.HeartRateError.NONE) {
            _currentExerciseLevel.value=0
            Timber.w("Heart rate unavailable: "+result.heartRateError)
            return ExerciseLevel(0,result)
        }
        _lastHeartRate.value=result.heartRate
        val delta=result.heartRate-restingHeartRate()
        val level=when {
            delta>40 -> 3
            delta>10 -> 2
            lastLevel>=2 && delta>0 -> 1
            else -> 0
        }
        _currentExerciseLevel.value=level
        return ExerciseLevel(level,result)
    }
}
''')
edit(a+"VitalWearApp.kt",
'heartRateService = HeartRateService(sensorManager, sensorThreadHandler)',
'heartRateService = HeartRateService(sensorManager, sensorThreadHandler, applicationContext)')
edit(a+"character/mood/MoodService.kt",
'            vitalService.processVitalsFromHeartRate(character, exerciseLevel.heartRate.heartRate, heartRateService.restingHeartRate())',
'''            if(exerciseLevel.heartRate.heartRateError == HeartRateResult.Companion.HeartRateError.NONE) {
                vitalService.processVitalsFromHeartRate(character, exerciseLevel.heartRate.heartRate, heartRateService.restingHeartRate())
                updateFromExerciseLevel(character, exerciseLevel.level, now)
                lastLevel = exerciseLevel.level
            }''')
edit(a+"character/mood/MoodService.kt",
'''            updateFromExerciseLevel(character, exerciseLevel.level, now)
            lastLevel = exerciseLevel.level
            logMoodUpdate''',
'''            logMoodUpdate''')
print("PATCH OK: Health + native steps, permission retry and real BPM")
