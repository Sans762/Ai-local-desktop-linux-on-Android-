#!/usr/bin/env python3
"""Galaxy Watch 8: live game background for the WFF face.
Uses current VitalWear BackgroundManager bitmap, not separate stock art.
Run after add_game_face_data.py.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
app="app/src/main/java/com/github/cfogrady/vitalwear/"
def write(rel,val):
    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(val,encoding="utf-8");print("WRITE",rel)
def patch(rel,old,new):
    p=root/rel;s=p.read_text(encoding="utf-8-sig")
    if s.count(old)!=1:raise RuntimeError(f"Expected 1 match, got {s.count(old)} in {rel}, target={old[:70]!r}")
    p.write_text(s.replace(old,new),encoding="utf-8");print("PATCH",rel)
write(app+"complications/GameBackgroundComplicationService.kt",r'''package com.github.cfogrady.vitalwear.complications

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.main.MainActivity

/** PHOTO_IMAGE of the chosen in-game scene; no extra art or fake partner. */
class GameBackgroundComplicationService : ComplicationDataSourceService() {
    override fun getPreviewData(type:ComplicationType):ComplicationData? = createData()
    override fun onComplicationRequest(request:ComplicationRequest,listener:ComplicationRequestListener) {
        listener.onComplicationData(createData())
    }
    private fun createData():ComplicationData {
        val app=application as VitalWearApp
        val source=app.backgroundManager.selectedBackground.value
        val output=Bitmap.createBitmap(450,450,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(output)
        canvas.drawColor(Color.rgb(10,25,39))
        if(source!=null && source.width>0 && source.height>0) {
            val paint=Paint(Paint.FILTER_BITMAP_FLAG)
            paint.isFilterBitmap=false // retain native game's sharp pixel artwork
            val scale=maxOf(450f/source.width,450f/source.height)
            val scaledWidth=(source.width*scale).toInt()
            val scaledHeight=(source.height*scale).toInt()
            val left=(450-scaledWidth)/2
            val top=(450-scaledHeight)/2
            canvas.drawBitmap(source,Rect(0,0,source.width,source.height),
                Rect(left,top,left+scaledWidth,top+scaledHeight),paint)
        }
        // A shallow shadow lets time remain legible without destroying artwork.
        val shade=Paint(Paint.ANTI_ALIAS_FLAG)
        shade.shader=android.graphics.LinearGradient(0f,0f,0f,190f,
            intArrayOf(0xBB000000.toInt(),0x44000000,0x00000000),
            null,android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,450f,190f,shade)
        val tap=PendingIntent.getActivity(this,52,
            Intent(this,MainActivity::class.java).apply {
                flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return PhotoImageComplicationData.Builder(
            Icon.createWithBitmap(output),
            PlainComplicationText.Builder("VitalWear current game background").build()
        ).setTapAction(tap).build()
    }
}
''')
manifest="app/src/main/AndroidManifest.xml"
patch(manifest,
'''        <service android:name=".complications.StepsComplicationService"''',
'''        <service android:name=".complications.GameBackgroundComplicationService"
            android:label="Digivice Game Background"
            android:exported="true"
            android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER">
            <intent-filter>
                <action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"/>
            </intent-filter>
            <meta-data android:name="android.support.wearable.complications.SUPPORTED_TYPES" android:value="LARGE_IMAGE"/>
            <meta-data android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS" android:value="600"/>
        </service>
        <service android:name=".complications.StepsComplicationService"''')
# Push background and character refresh when game changes. This is not a fast animation loop.
p=app+"complications/ComplicationRefreshService.kt"
patch(p,
'''    fun refreshVitalsComplication() {''',
'''    fun refreshGameBackgroundComplication() {
        requestUpdate(GameBackgroundComplicationService::class.java)
    }
    fun refreshActivePartnerComplication() {
        requestUpdate(PartnerComplicationService::class.java)
    }

    fun refreshVitalsComplication() {
        // Character switching and evolution in CharacterManager trigger this:
        // refresh all partner-linked face data, not only its vital number.
        refreshActivePartnerComplication()
''')
# Start & return to the app updates the game scene and active partner.
p=app+"main/MainActivity.kt"
patch(p,
'''    override fun onResume() {
        super.onResume()
        restartSteps()
    }''',
'''    override fun onResume() {
        super.onResume()
        restartSteps()
        (application as VitalWearApp).complicationRefreshService.apply {
            refreshGameBackgroundComplication()
            refreshActivePartnerComplication()
        }
    }''')
# Snapshot refreshed by backgroundManager event, not a periodic sensor poll.
p=app+"VitalWearApp.kt"
patch(p,
'''        buildDependencies()
        // Preload before first menu transition''',
'''        buildDependencies()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Default).launch {
            backgroundManager.selectedBackground.collect {
                complicationRefreshService.refreshGameBackgroundComplication()
            }
        }
        // Preload before first menu transition''')
patch(p,
'''import kotlinx.coroutines.flow.MutableStateFlow''',
'''import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect''')
# Only update during active use on each sprite frame, not once per 500 ms when the face is not active.
patch(app+"complications/ComplicationRefreshService.kt",
'''handler.postDelayed(this, 500)''','''handler.postDelayed(this, 2200)''')
# Refresh the real partner sprite immediately after evolution and removal.
patch(app+"character/CharacterManagerImpl.kt",
'''        activeCharacterFlow.value = transformedCharacter
        vbUpdater.setupTransformationChecker(transformedCharacter)''',
'''        activeCharacterFlow.value = transformedCharacter
        complicationRefreshService.refreshActivePartnerComplication()
        vbUpdater.setupTransformationChecker(transformedCharacter)''')
patch(app+"character/CharacterManagerImpl.kt",
'''            deleteCharacter(activeCharacter.characterStats.id)
            activeCharacterFlow.update {''',
'''            deleteCharacter(activeCharacter.characterStats.id)
            complicationRefreshService.refreshActivePartnerComplication()
            activeCharacterFlow.update {''')
print("COMPLETE game background photo source + partner and scene update on game state changes")
