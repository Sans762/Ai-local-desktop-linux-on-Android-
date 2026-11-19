#!/usr/bin/env python3
from pathlib import Path
import sys
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
base="app/src/main/java/com/github/cfogrady/vitalwear/"
def replace(rel,old,new):
 p=root/(base+rel);s=p.read_text(encoding="utf-8-sig")
 n=s.count(old)
 if n!=1:raise RuntimeError(f"{rel}: expected one match, got {n}, for {old[:90]}")
 p.write_text(s.replace(old,new),encoding="utf-8");print("UI UPDATED",rel)

# Remove the artificial rectangle the user dislikes.
replace("main/MainScreen.kt",
'''        // Thin VB-like vertical display frame. Existing game sprites and actions unchanged.
        Canvas(Modifier.fillMaxSize()) {
            val frameWidth=size.width*0.73f
            val frameHeight=size.height*0.80f
            val left=(size.width-frameWidth)/2
            val top=(size.height-frameHeight)/2
            drawRoundRect(color=Color(0xFF62DBE4),
                topLeft=Offset(left,top),size=Size(frameWidth,frameHeight),
                cornerRadius=androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style=Stroke(width=1.6.dp.toPx()))
        }
''','''        // Remove synthetic cyan frame; retain full-bleed original game scene.
''')

# Real menu page labels and counters on top of the original icon menu.
replace("main/MainScreen.kt",
'''            }
        }
        // Remove synthetic cyan frame; retain full-bleed original game scene.''',
'''            }
        }
        if (menuPages.getOrNull(pagerState.currentPage) != PARTNER) {
            androidx.compose.foundation.layout.Row(
                modifier=Modifier.align(Alignment.TopCenter).padding(top=24.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0xDF102633),
                        androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    ).padding(horizontal=14.dp,vertical=6.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Text(text=menuPages[pagerState.currentPage].name,
                    color=androidx.compose.ui.graphics.Color(0xFF8FF5CD),
                    fontWeight=FontWeight.Bold,fontSize=2.0.em)
                Text(text="  " + (pagerState.currentPage+1) + "/" + menuPages.size,
                    color=androidx.compose.ui.graphics.Color.White,fontSize=1.45.em)
            }
        }
        // Remove synthetic cyan frame; retain full-bleed original game scene.''')
replace("main/MainScreen.kt",
'''import androidx.compose.foundation.layout.size''',
'''import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape''')
# Change actual Partner screen: retain game scene+sprites, update layout and stat chips.
replace("character/PartnerScreen.kt",
'''import androidx.compose.foundation.layout.fillMaxWidth''',
'''import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp''')
replace("character/PartnerScreen.kt",
'''            Text(text="${formatNumber(now.hour, 2)}:${formatNumber(now.minute, 2)}", fontWeight = FontWeight.Bold, fontSize = 4.em)
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                bitmapScaler.ScaledBitmap(bitmap = firmware.vitalsIcon, contentDescription = "Vitals Icon")
                Text(text = formatNumber(vitals, 4), color = Color.White)
            }
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                bitmapScaler.ScaledBitmap(bitmap = firmware.stepsIcon, contentDescription = "Steps Icon")
                Text(text = formatNumber(dailyStepCount, 5), color = Color.White)
            }''',
'''            Text(text="${formatNumber(now.hour, 2)}:${formatNumber(now.minute, 2)}",
                fontWeight=FontWeight.Bold,fontSize=4.5.em,color=Color.White)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),
                verticalAlignment=Alignment.CenterVertically,
                modifier=Modifier.padding(top=3.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,
                    modifier=Modifier.background(Color(0xDD17293A),RoundedCornerShape(7.dp))
                        .padding(horizontal=5.dp,vertical=3.dp)) {
                    bitmapScaler.ScaledBitmap(bitmap=firmware.vitalsIcon,contentDescription="Vitals")
                    Text(text=formatNumber(vitals,4),color=Color(0xFF9AF5C2),
                        fontWeight=FontWeight.Bold)
                }
                Row(verticalAlignment=Alignment.CenterVertically,
                    modifier=Modifier.background(Color(0xDD17293A),RoundedCornerShape(7.dp))
                        .padding(horizontal=5.dp,vertical=3.dp)) {
                    bitmapScaler.ScaledBitmap(bitmap=firmware.stepsIcon,contentDescription="Steps")
                    Text(text=formatNumber(dailyStepCount,5),color=Color.White,
                        fontWeight=FontWeight.Bold)
                }
            }''')
print("REAL UI PATCH READY")
