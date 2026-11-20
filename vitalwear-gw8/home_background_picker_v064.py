#!/usr/bin/env python3
"""v0.6.4 - Home background selection actually affects new PixelHome.

Provides Settings > BACKGROUND page 1 CITY PIXEL, then native VB firmware
backgrounds (4) and imported DIM/BEM card backgrounds (if present). Preserves
sprite/HUD overlay, chosen scene across app restarts, and NFC/game state.
Never packages the user's firmware binary.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base=root/"app/src/main/java/com/github/cfogrady/vitalwear"

def patch(rel, old, new, count=1):
    path=base/rel
    src=path.read_text(encoding="utf-8-sig")
    found=src.count(old)
    if found!=count:
        raise RuntimeError(f"{rel}: expected {count} occurrence(s) of {old[:90]!r}, got {found}")
    path.write_text(src.replace(old,new),encoding="utf-8")
    print("PATCH",rel,old[:52])

# The stock manager already persists normal/card background choices.
# Add just one persisted switch for the game's optional Pixel City scene.
patch("background/BackgroundManager.kt",
'''        const val BACKGROUND_IDX = "BACKGROUND_IDX"''',
'''        const val BACKGROUND_IDX = "BACKGROUND_IDX"
        const val HOME_CITY_MODE = "PIXEL_HOME_CITY_MODE"''')
patch("background/BackgroundManager.kt",
'''    val selectedBackground: StateFlow<Bitmap?> = _selectedBackground''',
'''    val selectedBackground: StateFlow<Bitmap?> = _selectedBackground

    // Kept separate from the currently selected VB/card background, so changing
    // from City to a card and back never overwrites a user's scene selection.
    private val _usePixelCity = MutableStateFlow(
        sharedPreferences.getBoolean(HOME_CITY_MODE, true)
    )
    val usePixelCity: StateFlow<Boolean> = _usePixelCity

    fun selectPixelCityHome() {
        sharedPreferences.edit().putBoolean(HOME_CITY_MODE, true).apply()
        _usePixelCity.value = true
    }''')
patch("background/BackgroundManager.kt",
'''                BackgroundType.Normal -> _selectedBackground.value = background''',
'''                BackgroundType.Normal -> {
                    _selectedBackground.value = background
                    preferences.putBoolean(HOME_CITY_MODE, false)
                    _usePixelCity.value = false
                }''')
patch("background/BackgroundManager.kt",
'''            BackgroundType.Normal -> _selectedBackground.value = bitmap''',
'''            BackgroundType.Normal -> {
                _selectedBackground.value = bitmap
                preferences.putBoolean(HOME_CITY_MODE, false)
                _usePixelCity.value = false
            }''')

# Implement the missing rendering branch; before this patch scene was totally
# ignored because pixel home always called Art.city(canvas).
patch("composable/pixel/VbPixelArt.kt",
'''    fun battery(c:Canvas) {''',
'''    fun selectedBackground(c:Canvas, scene:Bitmap) {
        shell(c)
        val p=Paint().apply {isAntiAlias=false;isFilterBitmap=false}
        c.save()
        c.clipRect(113f,51f,326f,385f)
        c.drawBitmap(scene,
            Rect(0,0,scene.width,scene.height),
            RectF(113f,51f,326f,385f),p)
        c.restore()
    }
    fun battery(c:Canvas) {''')
patch("character/PixelHome.kt",
'''    val bpm by app.heartRateService.lastHeartRate.collectAsState()''',
'''    val bpm by app.heartRateService.lastHeartRate.collectAsState()
    val usePixelCity by app.backgroundManager.usePixelCity.collectAsStateWithLifecycle()''')
patch("character/PixelHome.kt",
'''            Art.city(canvas)
            Art.battery(canvas)''',
'''            if(usePixelCity) Art.city(canvas) else Art.selectedBackground(canvas,scene)
            Art.battery(canvas)''')

# The original VitalWear background picker works, but it did not offer City.
# It now shows a first page for City and retains existing firmware/card pages.
# Additionally give selection a readable title and an explicit touch hint.
picker=base/"background/BackgroundSelectionActivity.kt"
s=picker.read_text(encoding="utf-8-sig")
old='''    class BackgroundEntry(val firmware: Boolean, val cardName: String, val index: Int, val bitmap: MutableStateFlow<Bitmap?>)'''
new='''    class BackgroundEntry(
        val firmware: Boolean,
        val cardName: String,
        val index: Int,
        val bitmap: MutableStateFlow<Bitmap?>,
        val city: Boolean = false
    )'''
if s.count(old)!=1:raise RuntimeError("BackgroundEntry source changed")
s=s.replace(old,new)
old='''        val backgroundsArray = ArrayList<BackgroundEntry>(count)
        firmware.backgrounds.forEachIndexed'''
new='''        val backgroundsArray = ArrayList<BackgroundEntry>(count + 1)
        val cityPreview = Bitmap.createBitmap(438, 438, Bitmap.Config.ARGB_8888)
        com.github.cfogrady.vitalwear.composable.pixel.VbPixelArt.city(
            android.graphics.Canvas(cityPreview)
        )
        backgroundsArray.add(BackgroundEntry(
            false, "", -1, MutableStateFlow(cityPreview), city = true
        ))
        firmware.backgrounds.forEachIndexed'''
if s.count(old)!=1:raise RuntimeError("Background picker list source changed")
s=s.replace(old,new)
start=s.index("    @OptIn(ExperimentalFoundationApi::class)\n    @Composable\n    fun BackgroundSelector(")
end=s.index("    private fun getCards(",start)
replacement='''    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun BackgroundSelector(
        backgroundType: BackgroundManager.BackgroundType,
        backgroundPages: ArrayList<BackgroundEntry>,
        cardSpritesIO: CardSpritesIO,
        vitalBoxFactory: VitalBoxFactory
    ) {
        val pages = remember(backgroundType,backgroundPages) {
            // City is a home-only selection, never a battle background.
            if(backgroundType==BackgroundManager.BackgroundType.Normal) backgroundPages
            else ArrayList(backgroundPages.filter { !it.city })
        }
        val pagerState = rememberPagerState(pageCount = { pages.size })
        val cardsBeingLoaded = remember { mutableSetOf<String>() }
        val manager=(application as VitalWearApp).backgroundManager
        com.github.cfogrady.vitalwear.composable.pixel.PixelShell {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal=5.dp, vertical=12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                com.github.cfogrady.vitalwear.composable.pixel.PixelText(
                    "BACKGROUND",height=12.dp,
                    color=com.github.cfogrady.vitalwear.composable.pixel.PixelPalette.cyan
                )
                val shown=pages.getOrNull(pagerState.currentPage)
                val label=when {
                    shown==null -> "LOADING"
                    shown.city -> "CITY PIXEL"
                    shown.firmware -> "VB SCENE "+(shown.index+1)
                    else -> "CARD SCENE "+(shown.index+1)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(5.dp))
                com.github.cfogrady.vitalwear.composable.pixel.PixelText(
                    label,height=10.dp,
                    color=com.github.cfogrady.vitalwear.composable.pixel.PixelPalette.lime
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                VerticalPager(
                    state=pagerState,
                    modifier=Modifier.weight(1f).fillMaxWidth()
                ) { idx ->
                    val entry=pages[idx]
                    LaunchedEffect(idx) {
                        // Imported BEM cards have six background frames. Preload the
                        // next card as in the original picker; do not bundle the data.
                        if(idx+1<pages.size &&
                            pages[idx+1].bitmap.value==null &&
                            cardsBeingLoaded.add(pages[idx+1].cardName)) {
                            val bg=cardSpritesIO.loadCardBackgrounds(
                                applicationContext,pages[idx+1].cardName
                            )
                            bg.forEachIndexed{n,bitmap ->
                                if(idx+1+n<pages.size)
                                    pages[idx+1+n].bitmap.value=bitmap
                            }
                        }
                    }
                    val bitmap by entry.bitmap.collectAsState()
                    if(bitmap==null) {
                        Loading{}
                    } else {
                        androidx.compose.foundation.Image(
                            bitmap=bitmap!!.asImageBitmap(),
                            contentDescription=label,
                            modifier=Modifier.fillMaxSize().clickable {
                                when {
                                    entry.city -> manager.selectPixelCityHome()
                                    entry.firmware -> manager.setFirmwareBackground(
                                        backgroundType,entry.index)
                                    else -> manager.setCardBackground(
                                        backgroundType,entry.cardName,entry.index,bitmap!!)
                                }
                                finish()
                            },
                            contentScale=androidx.compose.ui.layout.ContentScale.FillBounds,
                            filterQuality=androidx.compose.ui.graphics.FilterQuality.None
                        )
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(5.dp))
                com.github.cfogrady.vitalwear.composable.pixel.PixelText(
                    "SWIPE TO SELECT",height=8.dp,
                    color=com.github.cfogrady.vitalwear.composable.pixel.PixelPalette.white
                )
                com.github.cfogrady.vitalwear.composable.pixel.PixelText(
                    "TAP TO APPLY",height=8.dp,
                    color=com.github.cfogrady.vitalwear.composable.pixel.PixelPalette.lime
                )
            }
        }
    }

'''
s=s[:start]+replacement+s[end:]
# Import the composable helpers used by this replacement.
s=s.replace('import androidx.compose.foundation.clickable\n',
'''import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
''')
# weight is a scoped modifier, no top-level import exists.
s=s.replace('import androidx.compose.foundation.layout.weight\n','')
picker.write_text(s,encoding="utf-8")
print("REPLACED",picker)

for module in ("app","companion"):
    p=root/module/"build.gradle.kts"
    t=p.read_text(encoding="utf-8-sig")
    if t.count("versionCode = 63")!=1 or t.count('versionName = "0.6.3-Watch8-40mm"')!=1:
        raise RuntimeError("Unexpected version in "+module)
    p.write_text(t.replace("versionCode = 63","versionCode = 64")
        .replace('versionName = "0.6.3-Watch8-40mm"',
                 'versionName = "0.6.4-HomeScenes"'),encoding="utf-8")
print("0.6.4 PERSISTENT VB BACKGROUND PICKER READY")
