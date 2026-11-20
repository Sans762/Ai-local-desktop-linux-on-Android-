#!/usr/bin/env python3
"""VitalWear GW8 0.6.7: VB-style large UI, unified swipeable status, home fix.

Always applied after v0.6.6 video feedback; uses actual firmware/card background,
unchanged game/NFC logic and original in-game values. No fake item features.
"""
from pathlib import Path
import sys, math
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
src=root/"app/src/main/java/com/github/cfogrady/vitalwear"
def rewrite(rel,old,new,n=1):
    p=src/rel
    s=p.read_text(encoding="utf-8-sig")
    if s.count(old)!=n:raise RuntimeError(f"{rel} expected {n} of {old[:65]!r}; found {s.count(old)}")
    p.write_text(s.replace(old,new),encoding="utf-8")
    print("PATCH",rel)

# Expand portrait glass to 280x314 logical pixel units on a 438x438 watch,
# while retaining all four corners inside the circular active display.
p=src/"composable/pixel/VbPixelArt.kt"
s=p.read_text(encoding="utf-8-sig")
start=s.index("    fun shell(c:Canvas) {")
end=s.index("    private val icons=",start)
s=s[:start]+'''    fun shell(c:Canvas) {
        c.drawColor(0xFF030A16.toInt())
        rect(c,79f,62f,280f,314f,NAVY)
        outline(c,79f,62f,280f,314f,BLUE,3f)
        rect(c,100f,65f,89f,5f,LIME)
        rect(c,249f,65f,89f,5f,LIME)
        rect(c,100f,369f,89f,5f,LIME)
        rect(c,249f,369f,89f,5f,LIME)
    }
'''+s[end:]
start=s.index("    fun menu(c:Canvas,")
end=s.index("    /** Readable status/data panel",start)
s=s[:start]+'''    /** Larger, VB-style menu: seven real entries fit and remain clickable. */
    fun menu(c:Canvas,labels:List<String>,allowed:List<Boolean>,
             selected:Int,first:Int,heading:String="MENU") {
        shell(c)
        text(c,heading.take(12),100f,77f,
            if(heading.length>9)2.35f else if(heading.length>6)2.8f else 3.5f,WHITE)
        rect(c,99f,107f,240f,3f,CYAN)
        for(i in 0 until 7) {
            val idx=first+i
            if(idx>=labels.size)break
            val y=114f+i*35f
            val active=(idx==selected)
            val color=if(active)LIME else CYAN
            rect(c,95f,y,250f,32f,if(active)LIME else PANEL)
            outline(c,95f,y,250f,32f,color,2f)
            icon(c,labels[idx],106f,y+7f,2.2f,if(active)NAVY else WHITE)
            rect(c,133f,y+5f,2f,22f,if(active)NAVY else CYAN)
            val label=labels[idx]
            val scale=if(label.length>10)2.2f else if(label.length>8)2.5f else 2.9f
            text(c,label,144f,y+7f,scale,
                if(!allowed[idx]) {
                    if(active)0xFF466300.toInt() else 0xFF9FB5C3.toInt()
                } else if(active) NAVY else WHITE)
            if(active)rect(c,86f,y+13f,7f,7f,LIME)
        }
        rect(c,99f,362f,240f,2f,CYAN)
    }
'''+s[end:]
start=s.index("    fun details(c:Canvas,")
end=s.index("    fun battery(c:Canvas)",start)
s=s[:start]+'''    /** One data page; long values switch to a second baseline, never overlap. */
    fun details(c:Canvas,heading:String,hero:String,
                rows:List<Pair<String,String>>,pet:Bitmap?=null,
                footer:String="SWIPE TO CONTINUE") {
        shell(c)
        text(c,heading.take(16),99f,77f,
            if(heading.length>12)1.90f else if(heading.length>9)2.25f else 2.9f,WHITE)
        rect(c,98f,108f,242f,3f,CYAN)
        if(pet!=null) {
            val p=Paint().apply {isFilterBitmap=false;isAntiAlias=false}
            c.drawBitmap(pet,Rect(0,0,pet.width,pet.height),
                RectF(187f,120f,252f,172f),p)
        }
        val h=hero.uppercase().take(16)
        centered(c,h,if(pet==null)142f else 182f,
            if(h.length>11)2.4f else if(h.length>8)2.8f else 3.45f,LIME)
        val y0=if(pet==null)197f else 227f
        val take=if(pet==null)4 else 3
        rows.take(take).forEachIndexed { i, (k,v) ->
            val y=y0+i*39f
            rect(c,98f,y,242f,35f,PANEL)
            outline(c,98f,y,242f,35f,BLUE,2f)
            val label=k.uppercase().take(12)
            val value=v.uppercase().take(12)
            val right=330f-textWidth(value,2.1f)
            if(107f+textWidth(label,2f)+8f>right) {
                text(c,label,107f,y+3f,1.8f,CYAN)
                text(c,value,330f-textWidth(value,1.95f),y+18f,1.95f,WHITE)
            } else {
                text(c,label,107f,y+9f,2f,CYAN)
                text(c,value,right,y+9f,2.1f,WHITE)
            }
        }
        rect(c,99f,362f,240f,2f,CYAN)
        centered(c,footer.take(24),349f,1.45f,MUTED)
    }
'''+s[end:]
# Native backdrops are drawn in the enlarged portrait area, not the old narrow clip.
old='''        c.clipRect(113f,51f,326f,385f)
        c.drawBitmap(scene,
            Rect(0,0,scene.width,scene.height),
            RectF(113f,51f,326f,385f),p)'''
new='''        c.clipRect(85f,72f,353f,366f)
        c.drawBitmap(scene,
            Rect(0,0,scene.width,scene.height),
            RectF(85f,72f,353f,366f),p)'''
if s.count(old)!=1:raise RuntimeError("background clip changed")
s=s.replace(old,new)
p.write_text(s,encoding="utf-8")
# When the user selects a background in Settings, use it and never draw City.
rewrite("character/PixelHome.kt",
        "if(usePixelCity) Art.city(canvas) else Art.selectedBackground(canvas,scene)",
        "Art.selectedBackground(canvas,scene)")
# Restore built-in firmware/card picker; remove our pixel-city entry entirely.
p=src/"background/BackgroundSelectionActivity.kt"
s=p.read_text(encoding="utf-8-sig")
start=s.index("        val cityPreview = Bitmap.createBitmap(438, 438, Bitmap.Config.ARGB_8888)")
end=s.index("        firmware.backgrounds.forEachIndexed",start)
s=s[:start]+s[end:]
s=s.replace("ArrayList<BackgroundEntry>(count + 1)","ArrayList<BackgroundEntry>(count)")
s=s.replace('''                    shown.city -> "CITY PIXEL"
''','')
s=s.replace('''                                    entry.city -> manager.selectPixelCityHome()
''','')
# Keep the original firmware/card source logic, without city branch.
p.write_text(s,encoding="utf-8")
print("RESTORED ORIGINAL SELECTABLE FIRMWARE/CARD HOME BACKGROUNDS")
# Menu selected item must match immediately after sleeping; the game persists
# the actual state via original controller.toggleSleep -> saveAsync.
p=src/"main/VbMenuScreen.kt"
s=p.read_text(encoding="utf-8-sig")
old='''    val awake=partner?.characterStats?.sleeping!=true'''
new='''    var isSleeping by remember(partner) {
        androidx.compose.runtime.mutableStateOf(partner?.characterStats?.sleeping==true)
    }
    val awake=!isSleeping'''
if s.count(old)!=1:raise RuntimeError("sleep state not found")
s=s.replace(old,new)
old='''VbAction(if(awake)"SLEEP" else "WAKE",hasPet){controller.toggleSleep()}'''
new='''VbAction(if(awake)"SLEEP" else "WAKE",hasPet){
            controller.toggleSleep()
            isSleeping=!isSleeping
        }'''
if s.count(old)!=1:raise RuntimeError("sleep action changed")
s=s.replace(old,new)
# Remove dead Items and the separate Digimon-info hub. Partner swapping stays
# available in Settings > PARTNER, and Digimon INFO is in STATUS swipe pages.
s=s.replace('''        VbAction("ITEMS",true){openHub(ctx,"ITEMS")},
''','')
s=s.replace('''        VbAction("DIGIMON",true){openHub(ctx,"DIGIMON")},
''','')
# Original VB has Mission, not an invented item bag.
s=s.replace('''        VbAction("SENSOR",true){openHub(ctx,"SENSOR")},
''','''        VbAction("SENSOR",true){openHub(ctx,"SENSOR")},
        VbAction("MISSION",true){openHub(ctx,"MISSION")},
''')
# Reorder mission between BATTLE and CONNECT (7th visible row is SENSOR).
s=s.replace('''        VbAction("BATTLE",hasPet&&awake&&evolved){openHub(ctx,"BATTLE")},
        VbAction("CONNECT",true)''',
'''        VbAction("BATTLE",hasPet&&awake&&evolved){openHub(ctx,"BATTLE")},
        VbAction("MISSION",true){openHub(ctx,"MISSION")},
        VbAction("CONNECT",true)''')
# Remove duplicate MISSION line, retaining one.
first=s.index('VbAction("MISSION",true){openHub(ctx,"MISSION")}')
second=s.find('VbAction("MISSION",true){openHub(ctx,"MISSION")}',first+1)
if second>=0:
    s=s[:second]+s[second:].replace('''        VbAction("MISSION",true){openHub(ctx,"MISSION")},
''','',1)
p.write_text(s,encoding="utf-8")
# Home should always be visible and tapping it returns to the menu.
p=src/"main/MainScreen.kt"
s=p.read_text(encoding="utf-8-sig")
if "import androidx.compose.foundation.clickable" not in s:
    s=s.replace("import androidx.compose.foundation.focusable",
                "import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.focusable")
old='''    LaunchedEffect(Unit){focus.requestFocus()}'''
new='''    LaunchedEffect(Unit){focus.requestFocus()}
    // A background chosen from settings must return the watch to its Home.
    LaunchedEffect(scene) {pager.scrollToPage(0)}'''
if s.count(old)!=1:raise RuntimeError("main focus location changed")
s=s.replace(old,new)
old='''        if(page==0) PixelHome(controller.partnerScreenController,scene)
        else VbMenuScreen(controller)'''
new='''        if(page==0) {
            Box(Modifier.fillMaxSize().clickable {
                scope.launch {pager.animateScrollToPage(1)}
            }) {PixelHome(controller.partnerScreenController,scene)}
        } else VbMenuScreen(controller)'''
if s.count(old)!=1:raise RuntimeError("main home route changed")
s=s.replace(old,new)
p.write_text(s,encoding="utf-8")
# Preserve home/menus even when there is no loaded partner, and do not
# reset to menu on every app start.
print("RESTORED HOME + DIRECT TAP TO MENU + SLEEP/WAKE UI")
# Bump game + phone together. No change to standalone forest watch face.
for module in ("app","companion"):
    p=root/module/"build.gradle.kts"
    s=p.read_text(encoding="utf-8-sig")
    if s.count("versionCode = 65")!=1 or s.count('versionName = "0.6.5-UI-Unified"')!=1:
        raise RuntimeError(f"Unexpected version {module}")
    p.write_text(s.replace("versionCode = 65","versionCode = 67")
               .replace('versionName = "0.6.5-UI-Unified"',
                        'versionName = "0.6.7-VB-Classic"'),encoding="utf-8")
assert math.hypot(140,157)<219
print("WATCH8 40MM UI/HOME/SLEEP/PICKER UPGRADE APPLIED")
