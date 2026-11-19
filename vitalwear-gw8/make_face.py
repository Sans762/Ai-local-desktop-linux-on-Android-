#!/usr/bin/env python3
"""Generate a stand-alone WFF v4 VB Classic XL face with original pixel mascot art.
Animation is a decorative fallback; set the Partner complication for the actual
character's static image. Does not alter the main VitalWear game.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import xml.etree.ElementTree as ET
root=Path("VBClassicXLFace")
(root/"src/main/res/raw").mkdir(parents=True,exist_ok=True)
(root/"src/main/res/drawable").mkdir(parents=True,exist_ok=True)
(root/"src/main/res/xml").mkdir(parents=True,exist_ok=True)
(root/"src/main/res/values").mkdir(parents=True,exist_ok=True)
(root/"settings.gradle.kts").write_text("""pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "VBClassicXLFace"
""",encoding="utf-8")
(root/"build.gradle.kts").write_text("""plugins { id("com.android.application") version "8.7.3" }
android {
  namespace = "dev.mikeki.vbclassicxl"
  compileSdk = 36
  defaultConfig {
    applicationId = "dev.mikeki.vbclassicxl"
    minSdk = 36
    targetSdk = 36
    versionCode = 1
    versionName = "0.5.0-beta"
  }
}
""",encoding="utf-8")
(root/"src/main/AndroidManifest.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-feature android:name="android.hardware.type.watch" android:required="true"/>
  <application android:label="VB Classic XL" android:hasCode="false">
    <property android:name="com.google.wear.watchface.format.version" android:value="4"/>
    <meta-data android:name="com.google.android.wearable.standalone" android:value="true"/>
  </application>
</manifest>
""",encoding="utf-8")
(root/"src/main/res/xml/watch_face_info.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo>
  <Preview value="@drawable/preview" />
  <Category value="CATEGORY_EMPTY" />
  <AvailableInRetail value="false" />
  <MultipleInstancesAllowed value="true" />
  <Editable value="true" />
</WatchFaceInfo>""",encoding="utf-8")
(root/"src/main/res/values/strings.xml").write_text('<resources><string name="app_name">VB Classic XL</string></resources>',encoding="utf-8")

xml="""<?xml version="1.0" encoding="utf-8"?>
<WatchFace width="450" height="450">
 <Scene>
  <PartDraw x="0" y="0" width="450" height="450">
   <Ellipse x="0" y="0" width="450" height="450"><Fill color="#090F1A"/></Ellipse>
   <Ellipse x="23" y="23" width="404" height="404"><Fill color="#185067"/></Ellipse>
   <Ellipse x="33" y="33" width="384" height="384"><Fill color="#071623"/></Ellipse>
   <Ellipse x="80" y="132" width="290" height="245"><Fill color="#122D3D"/></Ellipse>
  </PartDraw>
  <PartText x="0" y="44" width="450" height="86">
   <Text><Font family="SYNC_TO_DEVICE" size="59" color="#E5F6FF">
    <Template>%s:%s<Parameter expression="[HOUR_0_23_Z]"/><Parameter expression="[MINUTE_Z]"/></Template>
   </Font></Text>
  </PartText>
  <!-- Original animated pixel mascot stays within the central game screen. -->
  <PartAnimatedImage x="102" y="127" width="246" height="230">
   <Variant mode="AMBIENT" target="alpha" value="0"/>
   <AnimationController play="ON_VISIBLE"/>
   <AnimatedImage resource="pet_walk" format="AGIF"/>
   <Thumbnail resource="pet_idle"/>
  </PartAnimatedImage>
  <!-- Replace the default mascot with the real game's Partner complication.
       WFF cannot directly load arbitrary sprites from imported DIM files. -->
  <ComplicationSlot slotId="1" supportedTypes="SMALL_IMAGE EMPTY" x="124" y="158" width="202" height="184">
    <BoundingOval x="0" y="0" width="202" height="184"/>
    <Complication type="SMALL_IMAGE">
      <PartImage x="0" y="0" width="202" height="184">
        <Image resource="[COMPLICATION.SMALL_IMAGE]"/>
      </PartImage>
    </Complication>
  </ComplicationSlot>
  <PartText x="0" y="375" width="450" height="42">
   <Text><Font family="SYNC_TO_DEVICE" size="24" color="#6DE6E8">VB CLASSIC  XL</Font></Text>
  </PartText>
 </Scene>
</WatchFace>
"""
p=root/"src/main/res/raw/watchface.xml"
p.write_text(xml,encoding="utf-8")
ET.fromstring(xml)

# 7x pixel art body; original digital mascot (not a copyrighted Digimon sprite).
W,H=246,230
def frame(i):
    im=Image.new("RGBA",(W,H),(0,0,0,0)); d=ImageDraw.Draw(im)
    x=34 + (i if i<9 else 16-i)*10
    y=42 + ([0,4,0,-3]*4)[i]
    dark="#203041"; yellow="#F6C857"; cream="#FFE5A1"; teal="#6BE6DB"
    def rect(x0,y0,x1,y1,color):
        d.rectangle((x+x0,y+y0,x+x1,y+y1),fill=color)
    # tail and antenna
    rect(-15,74,14,96,dark);rect(-7,78,10,90,teal)
    rect(51,-7,63,23,dark);rect(55,-1,59,19,teal)
    # body
    rect(5,32,115,135,dark)
    rect(12,39,108,128,yellow)
    rect(21,100,97,122,cream)
    # ears
    rect(2,10,29,55,dark);rect(88,10,115,55,dark)
    rect(10,18,23,44,yellow);rect(94,18,107,44,yellow)
    # face and blink
    if i not in (3,11):
        rect(33,65,47,83,dark);rect(71,65,85,83,dark)
        rect(39,67,43,71,"#F9FBFF");rect(77,67,81,71,"#F9FBFF")
    else:
        rect(33,77,47,79,dark);rect(71,77,85,79,dark)
    rect(55,89,63,93,dark)
    # walking feet
    if i%2:
        rect(22,136,43,151,dark);rect(83,130,105,145,dark)
    else:
        rect(22,130,43,145,dark);rect(83,136,105,151,dark)
    # Zoom the character to fill most of the central VB-style game panel,
    # then translate horizontally inside the 246 x 230 animation canvas.
    crop=im.crop((x-18,y-18,x+133,y+156))
    zoom=crop.resize((190,208),Image.Resampling.NEAREST)
    canvas=Image.new("RGBA",(W,H),(18,45,61,255))
    offset=6+(i if i<9 else 16-i)*6
    canvas.alpha_composite(zoom,(offset,10))
    return canvas.convert("P",palette=Image.Palette.ADAPTIVE,colors=64)
frames=[frame(i) for i in range(16)]
frames[0].save(root/"src/main/res/drawable/pet_walk.gif",save_all=True,append_images=frames[1:],duration=210,loop=0,disposal=2,optimize=False)
frames[0].convert("RGBA").save(root/"src/main/res/drawable/pet_idle.png")

preview=Image.new("RGB",(450,450),"#071623")
D=ImageDraw.Draw(preview)
D.ellipse((12,12,438,438),outline="#185067",width=20)
D.rectangle((75,140,375,370),fill="#122D3D")
D.text((150,55),"10:08",fill="#E5F6FF",stroke_width=0)
sprite=frames[0].convert("RGBA")
preview.paste(sprite,(102,130),sprite)
D.text((160,386),"VB CLASSIC XL",fill="#6DE6E8")
preview.save(root/"src/main/res/drawable/preview.png")
print("Created WFF v4 project and animated fallback mascot (16 frames)")
