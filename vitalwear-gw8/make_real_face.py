#!/usr/bin/env python3
"""Build WFF4 game-backed VitalWear face. No demo monster or placeholder sprite.
Central image is sourced exclusively from the installed WATCH VitalWear game.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image,ImageDraw,ImageFont
root=Path("VBClassicXLFace")
raw=root/"src/main/res/raw"; raw.mkdir(parents=True,exist_ok=True)
drawable=root/"src/main/res/drawable"; drawable.mkdir(parents=True,exist_ok=True)
values=root/"src/main/res/values";values.mkdir(parents=True,exist_ok=True)
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
   versionCode = 3
   versionName = "0.5.3"
 }
}
""",encoding="utf-8")
(root/"src/main/AndroidManifest.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
 <uses-feature android:name="android.hardware.type.watch" android:required="true"/>
 <application android:label="VitalWear Game Face" android:hasCode="false">
  <property android:name="com.google.wear.watchface.format.version" android:value="4"/>
  <meta-data android:name="com.google.android.wearable.standalone" android:value="true"/>
 </application>
</manifest>
""",encoding="utf-8")
(root/"src/main/res/xml").mkdir(parents=True,exist_ok=True)
(root/"src/main/res/xml/watch_face_info.xml").write_text("""<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo>
 <Preview value="@drawable/preview"/>
 <Category value="CATEGORY_EMPTY"/>
 <AvailableInRetail value="false"/>
 <MultipleInstancesAllowed value="true"/>
 <Editable value="true"/>
</WatchFaceInfo>
""",encoding="utf-8")
(values/"strings.xml").write_text('''<resources>
 <string name="app_name">VitalWear Game Face</string>
 <string name="theme_label">VB Frame Color</string>
 <string name="theme_cyan">Classic Cyan</string>
 <string name="theme_blue">BE Blue</string>
 <string name="theme_green">Digital Green</string>
 <string name="theme_red">Digivice Red</string>
 <string name="theme_purple">Violet</string>
 <string name="theme_white">Mono LCD</string>
</resources>''',encoding="utf-8")
game="dev.mikeki.vitalwear.gw8/com.github.cfogrady.vitalwear.complications."
def slot(slotId,code,x,y,w,h,service,typ,desc,custom="FALSE"):
 return f'''<ComplicationSlot slotId="{slotId}" supportedTypes="{typ} EMPTY" isCustomizable="{custom}" x="{x}" y="{y}" width="{w}" height="{h}">
   <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"
       primaryProvider="{game}{service}" primaryProviderType="{typ}"/>
   <BoundingOval x="0" y="0" width="{w}" height="{h}"/>
   {code}
 </ComplicationSlot>'''
partner=slot(1,'''<Complication type="SMALL_IMAGE">
      <PartImage x="91" y="126" width="267" height="224">
        <Image resource="[COMPLICATION.SMALL_IMAGE]"/>
      </PartImage>
   </Complication>''',0,0,450,450,"PartnerComplicationService","SMALL_IMAGE","Partner",custom="FALSE")
def textslot(i,x,service,typ):
 # Each complication supplies a PendingIntent that launches the actual game.
 return slot(i,f'''<Complication type="{typ}">
    <PartText x="0" y="0" width="130" height="45">
     <Text><Font family="SYNC_TO_DEVICE" size="27" color="[CONFIGURATION.accent.1]">
      <Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template>
     </Font></Text>
    </PartText>
   </Complication>''',x,365,130,45,service,typ,service,custom="TRUE")
vitals=textslot(2,30,"VitalsComplicationService","RANGED_VALUE")
steps=textslot(3,158,"StepsComplicationService","SHORT_TEXT")
heart=textslot(4,292,"HeartComplicationService","SHORT_TEXT")
xml=f'''<?xml version="1.0" encoding="utf-8"?>
<WatchFace width="450" height="450">
 <UserConfigurations>
  <ColorConfiguration id="accent" displayName="theme_label" defaultValue="0">
   <ColorOption id="0" displayName="theme_cyan" colors="#FF55D7E8 #FFFFFFFF"/>
   <ColorOption id="1" displayName="theme_blue" colors="#FF416AFF #FFDAE4FF"/>
   <ColorOption id="2" displayName="theme_green" colors="#FF62E681 #FFE4FFE7"/>
   <ColorOption id="3" displayName="theme_red" colors="#FFFF6070 #FFFFE4E4"/>
   <ColorOption id="4" displayName="theme_purple" colors="#FFB58BFF #FFF1E8FF"/>
   <ColorOption id="5" displayName="theme_white" colors="#FFE5E5E5 #FFFFFFFF"/>
  </ColorConfiguration>
 </UserConfigurations>
 <Scene>
  <PartDraw x="0" y="0" width="450" height="450">
   <Ellipse x="0" y="0" width="450" height="450"><Fill color="#FF060B14"/></Ellipse>
   <Ellipse x="14" y="14" width="422" height="422"><Fill color="[CONFIGURATION.accent.0]"/></Ellipse>
   <Ellipse x="20" y="20" width="410" height="410"><Fill color="#FF0A1320"/></Ellipse>
   <Rectangle x="83" y="121" width="284" height="234"><Fill color="[CONFIGURATION.accent.0]"/></Rectangle>
   <Rectangle x="89" y="125" width="272" height="225"><Fill color="#FF10232D"/></Rectangle>
  </PartDraw>
  <PartText x="0" y="35" width="450" height="75">
   <Text><Font family="SYNC_TO_DEVICE" size="57" color="[CONFIGURATION.accent.1]">
    <Template>%s:%s<Parameter expression="[HOUR_0_23_Z]"/><Parameter expression="[MINUTE_Z]"/></Template>
   </Font></Text>
  </PartText>
  <!-- The partner comes ONLY from the actual installed VitalWear game and is tappable. -->
  {partner}
  {vitals}
  {steps}
  {heart}
  <PartText x="30" y="414" width="130" height="27">
   <Text><Font family="SYNC_TO_DEVICE" size="18" color="[CONFIGURATION.accent.0]">VITAL</Font></Text>
  </PartText>
  <PartText x="158" y="414" width="130" height="27">
   <Text><Font family="SYNC_TO_DEVICE" size="18" color="[CONFIGURATION.accent.0]">STEPS</Font></Text>
  </PartText>
  <PartText x="292" y="414" width="130" height="27">
   <Text><Font family="SYNC_TO_DEVICE" size="18" color="[CONFIGURATION.accent.0]">BPM</Font></Text>
  </PartText>
 </Scene>
</WatchFace>'''
ET.fromstring(xml)
(raw/"watchface.xml").write_text(xml,encoding="utf-8")
# Preview shows NO fake pet. Actual sprite is injected at runtime via game complication.
img=Image.new("RGB",(450,450),"#060B14");d=ImageDraw.Draw(img)
d.ellipse((14,14,436,436),fill="#55D7E8")
d.ellipse((21,21,429,429),fill="#0A1320")
d.rectangle((83,121,367,355),fill="#55D7E8")
d.rectangle((89,127,361,349),fill="#10232D")
d.text((148,48),"10:08",fill="#FFFFFF")
d.text((138,215),"GAME PARTNER",fill="#A1BFCE")
d.text((38,377),"VITAL",fill="#55D7E8")
d.text((167,377),"STEPS",fill="#55D7E8")
d.text((310,377),"BPM",fill="#55D7E8")
img.save(drawable/"preview.png")
print("WFF4 game-connected watch face built: 6 themes; game partner/vitals/steps/BPM data sources")
