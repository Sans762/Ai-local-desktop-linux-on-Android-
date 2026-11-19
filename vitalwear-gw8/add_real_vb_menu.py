#!/usr/bin/env python3
"""Replace the old single-icon carousel with a REAL tappable VB-styled main menu.
Uses existing working game screens. Leaves original partner scene and sprites intact.
"""
from pathlib import Path
import sys
r=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base="app/src/main/java/com/github/cfogrady/vitalwear/main/"
enum=r/(base+"MenuOption.kt")
s=enum.read_text(encoding="utf-8-sig")
assert s.count("    PARTNER,")==1
enum.write_text(s.replace("    PARTNER,", "    PARTNER,\n    MENU,"),encoding="utf-8")
print("MENU ENUM")
main=r/(base+"MainScreen.kt")
s=main.read_text(encoding="utf-8-sig")
assert s.count("import com.github.cfogrady.vitalwear.main.MenuOption.PARTNER")==1
s=s.replace("import com.github.cfogrady.vitalwear.main.MenuOption.PARTNER",
            "import com.github.cfogrady.vitalwear.main.MenuOption.PARTNER\nimport com.github.cfogrady.vitalwear.main.MenuOption.MENU")
assert s.count("                STATS -> {")==1
s=s.replace("                STATS -> {",'''                MENU -> {
                    VbMenuScreen(controller)
                }
                STATS -> {''')
start=s.index("fun buildMenuPages(")
end=s.index("@Preview(",start)
s=s[:start]+'''fun buildMenuPages(hasActivePartner:Boolean,phase:Int,sleeping:Boolean):ArrayList<MenuOption> {
    // The former one-icon-per-page menu did not match VB hardware.
    // Show the live pet home, then ONE practical button menu with all working features.
    val pages=ArrayList<MenuOption>()
    if(hasActivePartner) pages.add(PARTNER)
    pages.add(MENU)
    return pages
}

'''+s[end:]
begin=s.find("        if (menuPages.getOrNull(pagerState.currentPage) != PARTNER) {")
if begin>=0:
    end=s.find("        // Remove synthetic cyan frame",begin)
    if end<0: raise RuntimeError("Cannot locate legacy header end")
    s=s[:begin]+s[end:]
main.write_text(s,encoding="utf-8")
print("MAIN SCREEN NOW 2 PAGES: HOME + REAL MENU")

p=r/(base+"VbMenuScreen.kt")
p.write_text(r'''package com.github.cfogrady.vitalwear.main

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Text
import com.github.cfogrady.vitalwear.heartrate.SensorDiagnosticsActivity
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class VbMenuEntry(
    val label:String,
    val symbol:String,
    val available:Boolean=true,
    val open:()->Unit
)

/** Actual in-game menu. Every enabled entry opens the game's existing function. */
@Composable
fun VbMenuScreen(controller:MainScreenController) {
    val ctx=LocalContext.current
    val partner by controller.activePartner.collectAsStateWithLifecycle()
    val stage=partner?.speciesStats?.phase ?: 0
    val awake=partner?.characterStats?.sleeping!=true
    val hasPet=partner!=null
    val entries=listOf(
        VbMenuEntry("STATUS","◉",hasPet) { controller.launchStatsMenuActivity() },
        VbMenuEntry("TRAINING","▰",hasPet&&awake) { controller.launchTrainingMenuActivity() },
        VbMenuEntry("ADVENTURE","△",hasPet&&awake&&stage>1) { controller.launchAdventureActivity() },
        VbMenuEntry("BATTLE","⚔",hasPet&&awake&&stage>1) { controller.launchBattleActivity() },
        VbMenuEntry("CHARACTER","◆") { controller.launchCharacterSelectionActivity() },
        VbMenuEntry("CONNECT","◌") { controller.launchTransferActivity() },
        VbMenuEntry("SENSOR","♥") {
            ctx.startActivity(Intent(ctx,SensorDiagnosticsActivity::class.java))
        },
        VbMenuEntry("SETTINGS","⚙") { controller.launchSettingsActivity() },
        VbMenuEntry(if(awake)"SLEEP" else "WAKE","☾",hasPet) { controller.toggleSleep() }
    )
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val focus=remember { FocusRequester() }
    var current by remember { mutableIntStateOf(0) }
    var travel by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize()
            .background(Color(0xFF041321))
            .onRotaryScrollEvent {
                travel+=it.verticalScrollPixels
                if(abs(travel)>=23f) {
                    current=(current+if(travel>0)1 else -1).coerceIn(0,entries.lastIndex)
                    travel=0f
                    scope.launch { list.animateScrollToItem(current) }
                }
                true
            }
            .focusRequester(focus).focusable()
            .padding(start=18.dp,end=18.dp,top=16.dp,bottom=10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("MENU",fontSize=19.sp,fontWeight=FontWeight.Black,color=Color(0xFFD9F5FF))
            Text("  //  DIGIVICE",fontSize=10.sp,color=Color(0xFF1FC5FF))
        }
        LazyColumn(state=list,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            itemsIndexed(entries) { index,entry ->
                val active=current==index
                val bg=if(active)Color(0xFFB4F520) else Color(0xFF081C32)
                val color=if(!entry.available)Color(0xFF516276)
                    else if(active)Color(0xFF07190A) else Color(0xFFC7EEFF)
                Row(
                    Modifier.fillMaxWidth()
                        .height(42.dp)
                        .border(1.dp,if(active)Color(0xFFBDFF33) else Color(0xFF268ED3),RoundedCornerShape(7.dp))
                        .background(bg,RoundedCornerShape(7.dp))
                        .clickable(enabled=entry.available) {
                            current=index
                            entry.open()
                        }
                        .padding(horizontal=12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Text(entry.symbol,fontSize=19.sp,color=color,
                        modifier=Modifier.padding(end=14.dp))
                    Text(entry.label,fontSize=15.sp,fontWeight=FontWeight.Bold,color=color)
                }
            }
        }
    }
}
''',encoding="utf-8")
print("WROTE REAL TAPPABLE VB MENU SCREEN")
