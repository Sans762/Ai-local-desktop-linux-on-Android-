#!/usr/bin/env python3
"""Unified Vital Bracelet menu UI across app screens, no fake game state.
Applied after home_background_picker_v064.py. Avoids changing VBHelper NFC.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
src=root/"app/src/main/java/com/github/cfogrady/vitalwear"

def replace(rel,old,new,n=1):
    p=src/rel
    t=p.read_text(encoding="utf-8-sig")
    if t.count(old)!=n: raise RuntimeError(f"{rel}: wanted {n} of {old[:70]!r}, got {t.count(old)}")
    p.write_text(t.replace(old,new),encoding="utf-8")
    print("EDIT",rel,old[:35])
def write(rel,body):
    p=src/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding="utf-8")
    print("WRITE",rel)

replace("composable/pixel/VbPixelArt.kt",
'''    fun menu(c:Canvas,labels:List<String>,allowed:List<Boolean>,selected:Int,first:Int) {''',
'''    fun menu(c:Canvas,labels:List<String>,allowed:List<Boolean>,selected:Int,first:Int,heading:String="MENU") {''')
replace("composable/pixel/VbPixelArt.kt",
'''        text(c,"MENU",119f,65f,3.4f,WHITE)''',
'''        text(c,heading.take(12),119f,65f,
            if(heading.length>9)2.25f else if(heading.length>6)2.75f else 3.4f,WHITE)''')
replace("composable/pixel/VbPixelArt.kt",
'''    fun battery(c:Canvas) {''',
'''    /** Readable status/data panel reusing EXACT same portrait game frame. */
    fun details(c:Canvas,heading:String,hero:String,rows:List<Pair<String,String>>,
                pet:Bitmap?=null, footer:String="BACK / NEXT") {
        shell(c)
        text(c,heading.take(13),117f,65f,
            if(heading.length>9)2.15f else 2.7f,WHITE)
        rect(c,115f,95f,208f,3f,CYAN)
        if(pet!=null) {
            val p=Paint().apply{isFilterBitmap=false;isAntiAlias=false}
            c.drawBitmap(pet,Rect(0,0,pet.width,pet.height),
                RectF(187f,111f,249f,172f),p)
        }
        val heroText=hero.uppercase().take(15)
        centered(c,heroText,if(pet==null)133f else 178f,
            when {
                heroText.length>11 -> 2.15f
                heroText.length>7 -> 2.65f
                else -> 3.3f
            },LIME)
        val rowY=if(pet==null)201f else 234f
        val usable=if(pet==null)4 else 3
        rows.take(usable).forEachIndexed { index,item ->
            val y=rowY+index*39f
            rect(c,116f,y,205f,35f,PANEL)
            outline(c,116f,y,205f,35f,BLUE,2f)
            text(c,item.first.uppercase().take(10),124f,y+10f,1.95f,CYAN)
            val value=item.second.uppercase().take(9)
            text(c,value,315f-textWidth(value,2.05f),y+9f,2.05f,WHITE)
        }
        rect(c,115f,383f,208f,3f,CYAN)
        centered(c,footer,367f,1.35f,MUTED)
    }
    fun battery(c:Canvas) {''')
# Main screen should never drop Home if no active pet (e.g. during background or sync).
main=src/"main/MainScreen.kt"
st=main.read_text(encoding="utf-8-sig")
pairs=[
('''    val pager=rememberPagerState(pageCount={if(hasPet)2 else 1})''',
 '''    val pager=rememberPagerState(pageCount={2})'''),
('''        if(hasPet && page==0) PixelHome(controller.partnerScreenController,scene)
        else VbMenuScreen(controller)''',
 '''        if(page==0) PixelHome(controller.partnerScreenController,scene)
        else VbMenuScreen(controller)''')
]
for old,new in pairs:
    if st.count(old)!=1: raise RuntimeError("MainScreen changed "+old)
    st=st.replace(old,new)
main.write_text(st,encoding="utf-8")
# Main menu: match approved 7 rows on first page, with additional real entries on scroll.
men=src/"main/VbMenuScreen.kt"
t=men.read_text(encoding="utf-8-sig")
l=t.index("    val actions=listOf(")
r=t.index("    var selected by",l)
new='''    val actions=listOf(
        VbAction("STATUS",hasPet){openHub(ctx,"STATUS")},
        VbAction("TRAINING",hasPet&&awake){openHub(ctx,"TRAINING")},
        VbAction("ADVENTURE",hasPet&&awake&&evolved){openHub(ctx,"ADVENTURE")},
        VbAction("BATTLE",hasPet&&awake&&evolved){openHub(ctx,"BATTLE")},
        VbAction("ITEMS",true){openHub(ctx,"ITEMS")},
        VbAction("CONNECT",true){controller.launchTransferActivity()},
        VbAction("SENSOR",true){openHub(ctx,"SENSOR")},
        VbAction("DIGIMON",true){openHub(ctx,"DIGIMON")},
        VbAction("MISSION",true){openHub(ctx,"MISSION")},
        VbAction("SETTINGS",true){openHub(ctx,"SETTINGS")},
        VbAction(if(awake)"SLEEP" else "WAKE",hasPet){controller.toggleSleep()}
    )
'''
t=t[:l]+new+t[r:]
marker='''private data class VbAction(val label:String,val enabled:Boolean,val click:()->Unit)'''
if t.count(marker)!=1:raise RuntimeError("no action marker")
t=t.replace(marker,marker+'''

private fun openHub(ctx:android.content.Context,section:String) {
    ctx.startActivity(Intent(ctx,VbHubActivity::class.java).putExtra("SECTION",section))
}''')
men.write_text(t,encoding="utf-8")
# The same unified Status/Training/Settings UI is also reached by original app entry points.
write("stats/StatsMenuActivity.kt",r'''package com.github.cfogrady.vitalwear.stats
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.cfogrady.vitalwear.main.VbHubContent

class StatsMenuActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VbHubContent(this,"STATUS"){finish()} }
    }
}
''')
write("training/TrainingMenuActivity.kt",r'''package com.github.cfogrady.vitalwear.training
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.cfogrady.vitalwear.main.VbHubContent

class TrainingMenuActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VbHubContent(this,"TRAINING"){finish()} }
    }
}
''')
write("settings/SettingsActivity.kt",r'''package com.github.cfogrady.vitalwear.settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.cfogrady.vitalwear.main.VbHubContent

class SettingsActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VbHubContent(this,"SETTINGS"){finish()} }
    }
}
''')
man=root/"app/src/main/AndroidManifest.xml"
ms=man.read_text(encoding="utf-8-sig")
old='''        <activity android:name=".adventure.AdventureMenuActivity"'''
if ms.count(old)!=1:raise RuntimeError("manifest entry changed")
ms=ms.replace(old,'''        <activity android:name=".main.VbHubActivity"
            android:taskAffinity="" android:exported="false" />
'''+old)
man.write_text(ms,encoding="utf-8")
print("COMMON VB MENU NAVIGATION AND MANIFEST READY")
