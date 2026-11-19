#!/usr/bin/env python3
"""Enable Samsung touch-bezel rotary paging in all existing vertical pager menus.
Does not intercept battle/training input; tapping existing buttons still works.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base="app/src/main/java/com/github/cfogrady/vitalwear/"
helper=root/(base+"composable/util/BezelPager.kt")
helper.parent.mkdir(parents=True,exist_ok=True)
helper.write_text(r'''package com.github.cfogrady.vitalwear.composable.util

import androidx.compose.foundation.focusable
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Touch-bezel / rotary: one page per deliberate swipe (not per sensor tick). */
@Composable
fun Modifier.vbBezelPager(state:PagerState):Modifier {
    val scope=rememberCoroutineScope()
    val focus=remember { FocusRequester() }
    var accumulator by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(focus) { focus.requestFocus() }
    return this.onRotaryScrollEvent { event ->
        accumulator+=event.verticalScrollPixels
        if(abs(accumulator)>=24f) {
            val direction=if(accumulator>0f)1 else -1
            accumulator=0f
            val target=(state.currentPage+direction).coerceIn(0,(state.pageCount-1).coerceAtLeast(0))
            if(target!=state.currentPage) {
                scope.launch { state.animateScrollToPage(target) }
            }
        }
        true
    }.focusRequester(focus).focusable()
}
''',encoding="utf-8")
print("WROTE BezelPager helper")
files={
"stats/StatsMenuActivity.kt":"VerticalPager(state = pagerState)",
"training/TrainingMenuActivity.kt":"VerticalPager(state = pagerState)",
"adventure/AdventureMenuScreen.kt":"VerticalPager(state = pagerState)",
"character/activity/CharacterSelection.kt":"VerticalPager(state = pagerState)",
"background/BackgroundSelectionActivity.kt":"VerticalPager(state = pagerState)",
"settings/SettingsComposableFactory.kt":"VerticalPager(modifier = Modifier.fillMaxSize(), state = pagerState)",
}
for rel,old in files.items():
 p=root/(base+rel)
 s=p.read_text(encoding="utf-8-sig")
 count=s.count(old)
 if count<1:raise RuntimeError(f"Couldn't find pager in {p}")
 # all these files already import Modifier, and have composable scope.
 s=s.replace("import androidx.compose.ui.Modifier","import androidx.compose.ui.Modifier\nimport com.github.cfogrady.vitalwear.composable.util.vbBezelPager",1)
 replacement="VerticalPager(modifier = Modifier.fillMaxSize().vbBezelPager(pagerState), state = pagerState)"
 s=s.replace(old,replacement)
 p.write_text(s,encoding="utf-8")
 print("BEZEL ENABLED",rel,"pagers",count)
print("All existing vertical pager menus configured for bezel; watch must have touch bezel enabled.")
