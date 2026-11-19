#!/usr/bin/env python3
"""Genuine VitalWear game-data -> WFF watch face Complication providers.
Watch face taps open the actual game. No placeholder pixel mascots.
"""
from pathlib import Path
import sys
r=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear").resolve()
a="app/src/main/java/com/github/cfogrady/vitalwear/"
def write(p,s):
 q=r/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s,encoding="utf-8");print("WRITE",p)
def edit(p,x,y):
 q=r/p;s=q.read_text(encoding="utf-8-sig")
 if s.count(x)!=1:raise RuntimeError(f"{p}: expected 1 match got {s.count(x)} for {x[:45]}")
 q.write_text(s.replace(x,y),encoding="utf-8");print("PATCH",p)
base=r'''package com.github.cfogrady.vitalwear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.main.MainActivity

class NAMEComplicationService:ComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = data()
    override fun onComplicationRequest(request:ComplicationRequest,listener:ComplicationRequestListener) {
        listener.onComplicationData(data())
    }
    private fun data():ComplicationData {
        val app=application as VitalWearApp
        val tap=PendingIntent.getActivity(this, 0,
            Intent(this,MainActivity::class.java).apply {
                flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val value = VALUE
        val display=PlainComplicationText.Builder(value).build()
        val desc=PlainComplicationText.Builder(DESCRIPTION).build()
        return ShortTextComplicationData.Builder(display,desc)
            .setTapAction(tap).build()
    }
}
'''
write(a+"complications/StepsComplicationService.kt",base.replace("NAME","Steps").replace("VALUE","app.stepService.dailySteps.value.toString()").replace("DESCRIPTION",'"Digimon daily steps"'))
write(a+"complications/HeartComplicationService.kt",base.replace("NAME","Heart").replace("VALUE","app.heartRateService.lastHeartRate.value.let { if(it>0) it.toString() else \"--\" }").replace("DESCRIPTION",'"Heart BPM (most recent reading)"'))

# Existing partner bitmap comes straight from active character's actual imported sprites.
# Remove FLAG_ONE_SHOT which was preventing repeated face tap -> game.
edit(a+"complications/PartnerComplicationService.kt",
'''        val goToAppIntent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, goToAppIntent,
            PendingIntent.FLAG_CANCEL_CURRENT
                .or(PendingIntent.FLAG_IMMUTABLE)
                .or(PendingIntent.FLAG_ONE_SHOT))''',
'''        val goToAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, goToAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)''')
edit(a+"complications/VitalsComplicationService.kt",
'''        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, goToAppIntent, PendingIntent.FLAG_CANCEL_CURRENT.or(
            PendingIntent.FLAG_ONE_SHOT).or(PendingIntent.FLAG_IMMUTABLE))''',
'''        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, goToAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)''')
# Register two extra real-game data sources.
m="app/src/main/AndroidManifest.xml"
service='''        <service android:name=".complications.StepsComplicationService"
            android:label="Digivice Steps"
            android:exported="true"
            android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER">
            <intent-filter>
                <action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"/>
            </intent-filter>
            <meta-data android:name="android.support.wearable.complications.SUPPORTED_TYPES" android:value="SHORT_TEXT"/>
            <meta-data android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS" android:value="300"/>
        </service>
        <service android:name=".complications.HeartComplicationService"
            android:label="Digivice Heart"
            android:exported="true"
            android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER">
            <intent-filter>
                <action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"/>
            </intent-filter>
            <meta-data android:name="android.support.wearable.complications.SUPPORTED_TYPES" android:value="SHORT_TEXT"/>
            <meta-data android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS" android:value="300"/>
        </service>
'''
edit(m,
'''        <service
            android:name=".complications.PartnerComplicationService"''',
service+'''        <service
            android:name=".complications.PartnerComplicationService"''')
# Periodically refresh vitals and steps, with throttle to avoid screen/battery churn.
edit(a+"complications/ComplicationRefreshService.kt",
'''    fun refreshVitalsComplication() {''',
'''    fun refreshStepsComplication() {
        requestUpdate(StepsComplicationService::class.java)
    }
    fun refreshHeartComplication() {
        requestUpdate(HeartComplicationService::class.java)
    }
    private fun requestUpdate(cls:Class<*>) {
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                applicationContext,ComponentName(applicationContext,cls)
            ).requestUpdateAll()
        }.onFailure { Timber.w(it,"Complication update failed") }
    }

    fun refreshVitalsComplication() {''')
# By default step callback is no-op for existing tests, app supplies actual refresh closure.
edit(a+"steps/StepSensorService.kt",
'''    private val saveService: SaveService,
    private val dateTimeFetcher''',
'''    private val saveService: SaveService,
    private val onStepUpdate: ()->Unit = {},
    private val dateTimeFetcher''')
edit(a+"steps/StepSensorService.kt",
'''    private var lastNative:Int?=null''',
'''    private var lastNative:Int?=null
    private var lastStepFaceRefreshMs:Long=0L''')
edit(a+"steps/StepSensorService.kt",
'''        saveService.saveAsync()
        Timber.i("Daily steps new="''',
'''        saveService.saveAsync()
        val tick=android.os.SystemClock.elapsedRealtime()
        if(tick-lastStepFaceRefreshMs>60_000L || previous==0) {
            lastStepFaceRefreshMs=tick
            onStepUpdate()
        }
        Timber.i("Daily steps new="''')
edit(a+"VitalWearApp.kt",
'''stepService = StepSensorService(sensorManager, sensorThreadHandler, Lists.newArrayList(vitalService), stepState, stepIOService, saveService)''',
'''stepService = StepSensorService(sensorManager, sensorThreadHandler, Lists.newArrayList(vitalService), stepState, stepIOService, saveService,
            { complicationRefreshService.refreshStepsComplication() })''')
# HR complication refresh when actual, valid BPM is measured.
heart=a+"heartrate/HeartRateService.kt"
edit(heart,'''    private val prefs=context.applicationContext.getSharedPreferences("digivice_heart",Context.MODE_PRIVATE)''',
'''    private val prefs=context.applicationContext.getSharedPreferences("digivice_heart",Context.MODE_PRIVATE)
    private val appContext=context.applicationContext
    private fun refreshHeartFace() {
        runCatching {
            androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                appContext,
                android.content.ComponentName(appContext,
                    com.github.cfogrady.vitalwear.complications.HeartComplicationService::class.java)
            ).requestUpdateAll()
        }
    }''')
edit(heart,'''            _lastHeartRate.value=result.heartRate
            if(result.heartRate in 40..120)''',
'''            _lastHeartRate.value=result.heartRate
            refreshHeartFace()
            if(result.heartRate in 40..120)''')
edit(heart,'''        _lastHeartRate.value=result.heartRate
        val delta=result.heartRate-restingHeartRate()''',
'''        _lastHeartRate.value=result.heartRate
        refreshHeartFace()
        val delta=result.heartRate-restingHeartRate()''')
print("READY game-backed complications, repeated tap and face refresh")
