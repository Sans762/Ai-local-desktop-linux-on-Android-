#!/usr/bin/env python3
"""Minimal, deliberately boring changes on exact cfogrady/VitalWear upstream.
No custom home, no pixel UI renderer, no forest face, no fake items.
Requires user's *own* compatible firmware/card image files, not a physical
Vital Bracelet. Imported proprietary assets are not bundled in this project.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
def edit(path,original,replacement,expected=1):
    p=root/path
    s=p.read_text(encoding="utf-8-sig")
    n=s.count(original)
    if n!=expected:raise RuntimeError(f"UPSTREAM CHANGED: {path} got {n} occurrences of {original[:75]!r}, expected {expected}")
    p.write_text(s.replace(original,replacement),encoding="utf-8")
    print("PATCH",path)
def overwrite(path,content):
    p=root/path
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(content,encoding="utf-8")
    print("WRITE",path)

# Keep the previously installed experimental applicationId and signing key,
# so an update can preserve the on-watch data if the device accepts the APK.
for module in ("app","companion"):
    rel=f"{module}/build.gradle.kts"
    edit(rel,'applicationId = "com.github.cfogrady.vitalwear"',
         'applicationId = "dev.mikeki.vitalwear.gw8"')
    edit(rel,'versionCode = libs.versions.projectVersionCode.get().toInt()','versionCode = 80')
    edit(rel,'versionName = libs.versions.projectVersion.get()','versionName = "0.8.0-Upstream"')
# Data Layer must keep its previous phone ID (not .debug); watch retains debug.
edit("companion/build.gradle.kts", 'applicationIdSuffix = ".debug"',
     '// Do not suffix companion, matching the prior paired installation')
# Upstream uses a third-party DIM reader. Bundle compiled local Java8-branch
# library in build rather than requiring an unavailable Maven publication.
for module in ("app","common","companion"):
    # Common owns runtime packaging. App & phone require compile symbols only;
    # packing the same local jar in all three creates a duplicate dex class.
    dependency=("implementation" if module=="common" else "compileOnly")
    edit(f"{module}/build.gradle.kts",'implementation(libs.dim.reader)',
         f'{dependency}(files("libs/VB-DIM-Reader.jar"))')
    
# Remove all physical-bracelet NFC validation from import route. We import
# user-supplied, parsable DIM/BEM image data directly to the paired watch.
rel="companion/src/main/java/com/github/cfogrady/vitalwear/card/ImportCardActivity.kt"
p=root/rel
s=p.read_text(encoding="utf-8-sig")
s=s.replace('''        UnlockCard,
''','')
s=s.replace('''    private lateinit var cardVerificationLauncher: ((Intent)->Unit)->Unit
''','')
s=s.replace('''    private lateinit var validatedCardManager: ValidatedCardManager
''','')
s=s.replace('''        validatedCardManager = (applicationContext as VitalWearCompanion).validatedCardManager
''','')
s=s.replace('''        cardVerificationLauncher = buildCardVerificationLauncher()
''','')
begin=s.index('''                ImportState.UnlockCard -> {''')
end=s.index('''                ImportState.ImportCard -> {''',begin)
s=s[:begin]+s[end:]
begin=s.index('''    private fun buildCardVerificationLauncher()''')
end=s.index('''    private fun loadCard()''',begin)
s=s[:begin]+s[end:]
before='''            if (validatedCardManager.isValidatedCard(card.header.dimId)) {
                importState.value = ImportState.ImportCard
            } else {
                importState.value = ImportState.UnlockCard
            }'''
if s.count(before)!=1:raise RuntimeError("Original card verification logic changed")
s=s.replace(before,'''            // Parse local image; there is no physical bracelet verification.
            importState.value = ImportState.ImportCard''')
# Fail visibly if watch is disconnected, rather than falsely saying success.
needle='''        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        for (node in nodes) {'''
if s.count(needle)!=1:raise RuntimeError("Original Wear Channel sender changed")
s=s.replace(needle,'''        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        check(nodes.isNotEmpty()) { "Galaxy Watch not connected. Pair it before importing." }
        for (node in nodes) {''')
# An exception must not kill the screen or falsely show import success.
s=s.replace('''        ImportCard,
        Success
''','''        ImportCard,
        Success,
        Error
''')
s=s.replace('''    private val importState = MutableStateFlow(ImportState.PickFile)''',
'''    private val importState = MutableStateFlow(ImportState.PickFile)
    private val importError = MutableStateFlow("")''')
start=s.index('''                ImportState.ImportCard -> {''')
end=s.index('''                ImportState.Success -> {''',start)
s=s[:start]+'''                ImportState.ImportCard -> {
                    LaunchedEffect(true) {
                        try {
                            withContext(Dispatchers.IO) { importCard() }
                            importState.value = ImportState.Success
                        } catch(e:Exception) {
                            Timber.e(e,"Local import failed")
                            importError.value=e.message ?: "Transfer failed"
                            importState.value=ImportState.Error
                        }
                    }
                    Loading(loadingText="Importing local card image") {}
                }
                ImportState.Error -> {
                    val error by importError.collectAsState()
                    Column(
                        modifier=Modifier.fillMaxSize(),
                        verticalArrangement=Arrangement.Center,
                        horizontalAlignment=Alignment.CenterHorizontally
                    ) {
                        Text("Import error: $error")
                        Button(onClick={finish()}) {Text("Close")}
                    }
                }
'''+s[end:]
# File reader failures now display errors rather than crashing
start=s.index('''    private fun loadCard() {''')
end=s.index('''    private suspend fun importCard()''',start)
s=s[:start]+'''    private fun loadCard() {
        try {
            val reader=DimReader()
            contentResolver.openInputStream(uri).use { stream ->
                require(stream!=null) {"Unable to open selected card file"}
                card=reader.readCard(stream,false)
                importState.value=ImportState.ImportCard
            }
        } catch(e:Exception) {
            Timber.e(e,"Local DIM/BEM parsing failed")
            importError.value=e.message ?: "Invalid DIM/BEM file"
            importState.value=ImportState.Error
        }
    }

'''+s[end:]
p.write_text(s,encoding="utf-8")
# The companion doesn't need physical NFC hardware for importing local cards.
edit("companion/src/main/AndroidManifest.xml",
     'android.hardware.nfc" android:required="true"',
     'android.hardware.nfc" android:required="false"')

# Heart rate already exists in upstream but its paired deferreds can hang
# indefinitely if Android doesn't emit an accuracy callback. One short
# measurement, then always unregister sensor, without fake heart rate data.
overwrite("app/src/main/java/com/github/cfogrady/vitalwear/heartrate/SingleHeartRateSensorListener.kt",r'''package com.github.cfogrady.vitalwear.heartrate

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.github.cfogrady.vitalwear.util.SensorThreadHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** One-shot optical HR: 10s timeout and guaranteed unregister.
 * No background continuous sensor lock or invented measurements.
 */
class SingleHeartRateSensorListener(
    private val sensorManager:SensorManager,
    private val handler:SensorThreadHandler
):SensorEventListener {
    private val reading=CompletableDeferred<HeartRateResult>()
    private var lastAccuracy=SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    suspend fun getValue():HeartRateResult {
        val sensor=sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            ?:return result(0,HeartRateResult.Companion.HeartRateError.UNAVAILABLE)
        return try {
            val registered=sensorManager.registerListener(
                this,sensor,SensorManager.SENSOR_DELAY_NORMAL,handler.handler)
            if(!registered) result(0,HeartRateResult.Companion.HeartRateError.UNAVAILABLE)
            else withTimeoutOrNull(10000L) {reading.await()} ?:
                result(0,HeartRateResult.Companion.HeartRateError.UNAVAILABLE)
        } catch(e:SecurityException) {
            Timber.w(e,"No permission for HR sensor")
            result(0,HeartRateResult.Companion.HeartRateError.UNAVAILABLE)
        } finally {
            sensorManager.unregisterListener(this)
        }
    }
    override fun onSensorChanged(event:SensorEvent?) {
        val bpm=event?.values?.lastOrNull()?.toInt() ?: return
        if(lastAccuracy==SensorManager.SENSOR_STATUS_NO_CONTACT) {
            reading.complete(result(0,HeartRateResult.Companion.HeartRateError.NO_CONTACT))
        } else if(bpm in 35..220) {
            reading.complete(result(bpm,HeartRateResult.Companion.HeartRateError.NONE))
        }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {
        lastAccuracy=accuracy
        if(accuracy==SensorManager.SENSOR_STATUS_NO_CONTACT) {
            reading.complete(result(0,HeartRateResult.Companion.HeartRateError.NO_CONTACT))
        }
    }
    private fun result(bpm:Int,error:HeartRateResult.Companion.HeartRateError)=
        HeartRateResult(bpm,error)
}
''')
rel="app/src/main/java/com/github/cfogrady/vitalwear/heartrate/HeartRateService.kt"
edit(rel,'''    private val _currentExerciseLevel = MutableStateFlow(0)''',
'''    private val _lastHeartRate = MutableStateFlow<Int?>(null)
    val lastHeartRate: StateFlow<Int?> = _lastHeartRate
    private var baseline=65
    private val _currentExerciseLevel = MutableStateFlow(0)''')
edit(rel,'''        return listener.getValue()''',
'''        val result=listener.getValue()
        if(result.heartRateError==HeartRateResult.Companion.HeartRateError.NONE &&
           result.heartRate in 35..220) {
            _lastHeartRate.value=result.heartRate
            if(result.heartRate<baseline) baseline=(baseline*4+result.heartRate)/5
        }
        return result''')
edit(rel,'''        val current = if(heartRateResult.heartRateError == HeartRateResult.Companion.HeartRateError.NONE) heartRateResult.heartRate else 65''',
'''        if(heartRateResult.heartRateError != HeartRateResult.Companion.HeartRateError.NONE)
            return lastLevel
        val current=heartRateResult.heartRate''')
edit(rel,'''        //TODO: Get actual
        return 65''',
'''        // Conservative initial baseline until a valid optical reading is received.
        return baseline''')
# Prevent fake VITAL points from a failed sensor read.
edit("app/src/main/java/com/github/cfogrady/vitalwear/character/mood/MoodService.kt",
'''            vitalService.processVitalsFromHeartRate(character, exerciseLevel.heartRate.heartRate, heartRateService.restingHeartRate())''',
'''            if(exerciseLevel.heartRate.heartRateError == HeartRateResult.Companion.HeartRateError.NONE) {
                vitalService.processVitalsFromHeartRate(
                    character,exerciseLevel.heartRate.heartRate,
                    heartRateService.restingHeartRate())
            }''')
# Original Home keeps firmware background and character sprites.
# Only append a small live measurement label (no continuous polling).
rel="app/src/main/java/com/github/cfogrady/vitalwear/character/PartnerScreen.kt"
edit(rel,'''import androidx.wear.compose.material.Text''',
'''import androidx.wear.compose.material.Text
import com.github.cfogrady.vitalwear.VitalWearApp''')
edit(rel,'''    val vitals by controller.vitals.collectAsStateWithLifecycle()''',
'''    val vitals by controller.vitals.collectAsStateWithLifecycle()
    val app=LocalContext.current.applicationContext as VitalWearApp
    val lastBpm by app.heartRateService.lastHeartRate.collectAsStateWithLifecycle()''')
edit(rel,'''            Box(modifier = Modifier.fillMaxWidth().height(emojiHeight), contentAlignment = Alignment.BottomEnd) {''',
'''            Text(text="HR "+(lastBpm?.toString() ?: "--")+" BPM",
                color=Color.White)
            Box(modifier = Modifier.fillMaxWidth().height(emojiHeight), contentAlignment = Alignment.BottomEnd) {''')

# Step counter stays event driven and on its existing background handler.
# Avoid SecurityException at app startup before runtime permission grant.
rel="app/src/main/java/com/github/cfogrady/vitalwear/steps/StepSensorService.kt"
edit(rel,'''        if(!sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, sensorThreadHandler.handler)) {
            Timber.e("Unable to register step sensor")
            return
        }''',
'''        try {
            if(!sensorManager.registerListener(this,stepSensor,SensorManager.SENSOR_DELAY_NORMAL,sensorThreadHandler.handler)) {
                Timber.e("Unable to register step sensor")
                return
            }
        } catch(e:SecurityException) {
            Timber.w(e,"ACTIVITY_RECOGNITION not yet granted")
            return
        }''')
# Original permission prompt already exists but starts step monitor before
# dialog. Retry it after the user grants permission; and when activity resumes.
rel="app/src/main/java/com/github/cfogrady/vitalwear/main/MainActivity.kt"
edit(rel,'''    override fun onStart() {
        super.onStart()''',
'''    override fun onStart() {
        super.onStart()
        if(hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            (application as VitalWearApp).stepService.startup()
        }''')
edit(rel,'''        val launcher = registerForActivityResult(multiplePermissionsContract, resultBehavior)''',
'''        val launcher = registerForActivityResult(multiplePermissionsContract) { permissions ->
            if(hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                (application as VitalWearApp).stepService.startup()
            }
            resultBehavior(permissions)
        }''')
# Avoid falsely requiring a wearable device with NFC for phone-side import.
# Keep the original firmware picker: user can supply compatible firmware file,
# but no longer needs a physical VB to authorize local DIM/BEM import.
# Preserve original animation and menu sprites rather than generating new UI.
# Inexact non-wakeup alarm reduces repeated CPU wakeups while stationary.
edit("app/src/main/java/com/github/cfogrady/vitalwear/character/VBUpdater.kt",
'''        alarmManager.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + period, period, moodUpdateIntent)''',
'''        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME, now+period,period,moodUpdateIntent)''')

print("READY: original VitalWear only; physical bracelet optional; sensor fixes applied")
