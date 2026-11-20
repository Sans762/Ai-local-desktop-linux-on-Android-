#!/usr/bin/env python3
"""Status/Digimon single swipe-through pager: uses original game values."""
from pathlib import Path
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")/"app/src/main/java/com/github/cfogrady/vitalwear/main/VbHubActivity.kt"
s=p.read_text(encoding="utf-8-sig")
imp="import androidx.compose.foundation.Canvas"
if s.count(imp)!=1:raise RuntimeError("imports changed")
s=s.replace(imp,"import androidx.compose.foundation.pager.VerticalPager\nimport androidx.compose.foundation.pager.rememberPagerState\n"+imp)
marker='''/** All menu labels navigate to actual functions/stats; never fabricate item stock. */
@Composable
fun VbHubContent'''
if s.count(marker)!=1:raise RuntimeError("VbHubContent marker changed")
new=r'''/** VB manual-style STATUS and DIGIMON are now ONE continuous swipe path.
 * Press Back to the previous page, swipe vertically to read the next page.
 */
@Composable
private fun VbStatusTimeline(activity:ComponentActivity,onExit:()->Unit) {
    val app=activity.application as VitalWearApp
    val pet by app.characterManager.getCharacterFlow().collectAsState()
    val steps by app.stepService.dailySteps.collectAsState()
    val bpm by app.heartRateService.lastHeartRate.collectAsState()
    val pages=listOf("VITAL","HEART RATE","MILE","BASIC INFO",
        "DIGIMON STATUS","BATTLE RECORD","TROPHIES","EVOLUTION")
    val state=rememberPagerState(pageCount={pages.size})
    val scope=rememberCoroutineScope()
    VerticalPager(state=state,modifier=Modifier.fillMaxSize()) { page ->
        val cs=pet?.characterStats
        val sprite=if(page>=3)
            pet?.characterSprites?.sprites?.getOrNull(CharacterSprites.IDLE_1)
        else null
        val hero:String
        val rows:List<Pair<String,String>>
        when(page) {
            0 -> {
                hero=(cs?.vitals?:0).toString()
                rows=listOf("VITAL" to hero,"MOOD" to (cs?.mood?:0).toString())
            }
            1 -> {
                hero=if(bpm>0) "$bpm BPM" else "NO READING"
                rows=listOf("CURRENT" to (if(bpm>0) "$bpm BPM" else "--"),
                    "BASE" to app.heartRateService.restingHeartRate().toString()+" BPM")
            }
            2 -> {
                // Use grounded watch steps, not an invented distance conversion.
                hero=steps.toString()
                rows=listOf("STEPS" to steps.toString(),"SOURCE" to "WATCH")
            }
            3 -> {
                hero=pet?.cardName()?.take(14) ?: "NO PARTNER"
                rows=listOf("PHASE" to ((pet?.speciesStats?.phase?:-1)+1).toString(),
                    "AGE DAYS" to (cs?.ageInDays?:0).toString(),
                    "ATTRIBUTE" to (pet?.speciesStats?.attribute?.toString()?:"--"))
            }
            4 -> {
                hero="POWER"
                rows=listOf("BP" to (pet?.totalBp()?:0).toString(),
                    "HP" to (pet?.totalHp()?:0).toString(),
                    "AP" to (pet?.totalAp()?:0).toString(),
                    "PP" to (cs?.trainedPP?:0).toString())
            }
            5 -> {
                hero=(cs?.totalWins?:0).toString()+" WINS"
                rows=listOf("BATTLES" to (cs?.totalBattles?:0).toString(),
                    "WINS" to (cs?.totalWins?:0).toString(),
                    "WIN RATE" to (cs?.currentPhaseWinRatio()?:0).toString()+"%")
            }
            6 -> {
                hero=(cs?.totalTrophies?:0).toString()
                rows=listOf("TROPHIES" to hero,
                    "TRAIN PP" to (cs?.trainedPP?:0).toString())
            }
            else -> {
                val secs=(cs?.timeUntilNextTransformation?:0L).coerceAtLeast(0)
                hero=if(pet?.hasPotentialTransformations()!=true) "FINAL FORM"
                    else (secs/3600).toString()+"H "+((secs%3600)/60).toString()+"M"
                val option=pet?.transformationOptions?.firstOrNull()
                rows=if(option==null)listOf("NEXT" to "NONE") else
                    listOf("VITAL" to option.requiredVitals.toString(),
                        "BATTLES" to option.requiredBattles.toString(),
                        "WIN RATE" to option.requiredWinRatio.toString()+"%",
                        "PP" to option.requiredPp.toString())
            }
        }
        VbDetailPage(title=pages[page],hero=hero,rows=rows,sprite=sprite,
            onBack={
                if(page==0)onExit()
                else scope.launch{state.animateScrollToPage(page-1)}
                Unit
            },
            onNext=if(page==pages.lastIndex) null else ({
                scope.launch{state.animateScrollToPage(page+1)}
                Unit
            }))
    }
}

/** All menu labels navigate to actual functions/stats; never fabricate item stock. */
@Composable
fun VbHubContent'''
s=s.replace(marker,new)
needle='''fun VbHubContent(activity:ComponentActivity,section:String,onExit:()->Unit) {
    val app=activity.application as VitalWearApp'''
if s.count(needle)!=1:raise RuntimeError("Hub entry changed")
s=s.replace(needle,'''fun VbHubContent(activity:ComponentActivity,section:String,onExit:()->Unit) {
    if(section=="STATUS" || section=="DIGIMON") {
        VbStatusTimeline(activity,onExit)
        return
    }
    val app=activity.application as VitalWearApp''')
# No inventory and no duplicate Digimon Info menu.
for first,last in [
    ('        "ITEMS" -> listOf(', '        "SENSOR" -> listOf('),
    ('        "DIGIMON" -> listOf(', '        "TRAINING" -> listOf('),
]:
    start=s.index(first)
    end=s.index(last,start)
    s=s[:start]+s[end:]
key='''        "SETTINGS" -> listOf(
            item("BACKGROUND"){background()},'''
if s.count(key)!=1:raise RuntimeError("Settings background label changed")
s=s.replace(key,key+'''
            item("PARTNER"){launch(CharacterSelectActivity::class.java)},''')
s=s.replace('''            "FEED","MEDICINE","CARD","OTHER" -> {
                hero="NOT AVAILABLE"
                rows=listOf("ITEM" to display,"ENGINE" to "NO INVENTORY")
            }
''','')
s=s.replace('"VERSION" to "0.6.5"','"VERSION" to "0.6.7"')
p.write_text(s,encoding="utf-8")
print("SWIPEABLE 8-PAGE STATUS/DIGIMON READY")
