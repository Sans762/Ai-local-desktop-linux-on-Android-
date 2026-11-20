#!/usr/bin/env python3
"""Galaxy Watch 8 WFF4: full-screen game scenery, character from game,
customizable palette, partner zoom, clock size, HUD density & atmosphere.
No static demo Digimon is packed into the watchface.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image,ImageDraw
root=Path("VBClassicXLFace")
raw=root/"src/main/res/raw";raw.mkdir(parents=True,exist_ok=True)
drawable=root/"src/main/res/drawable";drawable.mkdir(parents=True,exist_ok=True)
values=root/"src/main/res/values";values.mkdir(parents=True,exist_ok=True)
(root/"build.gradle.kts").write_text("""plugins { id("com.android.application") version "8.7.3" }
android {
 namespace = "dev.mikeki.vbclassicxl"
 compileSdk = 36
 defaultConfig {
  applicationId = "dev.mikeki.vbclassicxl"
  minSdk = 36
  targetSdk = 36
  versionCode = 6
  versionName = "0.5.6"
 }
}
""",encoding="utf-8")
strings={
"app_name":"VB Game Live 0.5.5",
"accent_label":"Accent Color",
"pet_size_label":"Partner Size",
"stats_label":"HUD Detail",
"clock_label":"Clock Size",
"scene_label":"Scene Contrast",
}
for k,v in {"cyan":"VB Cyan","green":"VB Green","blue":"BE Blue","purple":"Violet","red":"Crimson","white":"Mono","gold":"Golden","mint":"Mint","orange":"Sunset","pink":"Rose"}.items():strings["color_"+k]=v
strings["opt_soft"]="Soft shade"
for k,v in {"small":"Small","medium":"Medium","large":"Large","all":"All stats","fitness":"Fitness","minimal":"Clean","low":"Clear scene","high":"High contrast","compact":"Compact","regular":"Regular","giant":"Extra Large"}.items():strings["opt_"+k]=v
from xml.sax.saxutils import escape
(values/"strings.xml").write_text("<resources>\n"+"\n".join(' <string name="'+k+'">'+escape(v)+'</string>' for k,v in strings.items())+"\n</resources>",encoding="utf-8")
game="dev.mikeki.vitalwear.gw8/com.github.cfogrady.vitalwear.complications."
def provider(name,kind):
 return '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY" primaryProvider="'+game+name+'" primaryProviderType="'+kind+'"/>'
def slot(id,x,y,w,h,name,kind,body,custom="false"):
 return f'''<ComplicationSlot slotId="{id}" supportedTypes="{kind} EMPTY" isCustomizable="{custom}" x="{x}" y="{y}" width="{w}" height="{h}">
  {provider(name,kind)}
  <BoundingOval x="0" y="0" width="{w}" height="{h}"/>
  <Complication type="{kind}">{body}</Complication>
 </ComplicationSlot>'''
background=slot(1,0,0,450,450,"GameBackgroundComplicationService","PHOTO_IMAGE",'''
 <PartImage x="0" y="0" width="450" height="450">
  <Image resource="[COMPLICATION.PHOTO_IMAGE]"/>
  <Variant mode="AMBIENT" target="alpha" value="0"/>
 </PartImage>
 ''')
def partner(id,x,y,w,h):
 return slot(id,x,y,w,h,"PartnerComplicationService","SMALL_IMAGE",f'''
 <PartImage x="0" y="0" width="{w}" height="{h}">
  <Image resource="[COMPLICATION.SMALL_IMAGE]"/>
  <Variant mode="AMBIENT" target="alpha" value="0"/>
 </PartImage>
 ''')
def stat(id,x,y,w,h,name,kind):
 return slot(id,x,y,w,h,name,kind,f'''
 <PartText x="0" y="0" width="{w}" height="{h}">
  <Text><Font family="SYNC_TO_DEVICE" size="25" color="#FFFFFFFF">
   <Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template>
  </Font></Text>
 </PartText>''',"TRUE")
theme=[
("cyan","#FF58DDE3","#FFF0FCFF"),("green","#FFA8E324","#FFFFFFFF"),
("blue","#FF4D8BFF","#FFEAF2FF"),("purple","#FFAC80FF","#FFFCF4FF"),
("red","#FFF86872","#FFFFFFFF"),("white","#FFE9E9E9","#FFFFFFFF"),
("gold","#FFF7BD43","#FFFFFFFF"),("mint","#FF65F7BD","#FFFFFFFF"),
("orange","#FFF78F4D","#FFFFFFFF"),("pink","#FFF68EC8","#FFFFFFFF")
]
color_options="\n".join(f'<ColorOption id="{i}" displayName="color_{name}" colors="{accent} {light}"/>' for i,(name,accent,light) in enumerate(theme))
settings=f'''<UserConfigurations>
 <ColorConfiguration id="accent" displayName="accent_label" defaultValue="0">{color_options}</ColorConfiguration>
 <ListConfiguration id="pet_size" displayName="pet_size_label" defaultValue="medium">
  <ListOption id="small" displayName="opt_small" complicationSlotIds="11"/>
  <ListOption id="medium" displayName="opt_medium" complicationSlotIds="12"/>
  <ListOption id="large" displayName="opt_large" complicationSlotIds="13"/>
 </ListConfiguration>
 <ListConfiguration id="stats" displayName="stats_label" defaultValue="all">
  <ListOption id="all" displayName="opt_all" complicationSlotIds="21 22 23"/>
  <ListOption id="fitness" displayName="opt_fitness" complicationSlotIds="21 22"/>
  <ListOption id="minimal" displayName="opt_minimal"/>
 </ListConfiguration>
 <ListConfiguration id="clock_style" displayName="clock_label" defaultValue="regular">
  <ListOption id="compact" displayName="opt_compact"/>
  <ListOption id="regular" displayName="opt_regular"/>
  <ListOption id="giant" displayName="opt_giant"/>
 </ListConfiguration>
 <ListConfiguration id="contrast" displayName="scene_label" defaultValue="medium">
  <ListOption id="low" displayName="opt_low"/>
  <ListOption id="medium" displayName="opt_soft"/>
  <ListOption id="high" displayName="opt_high"/>
 </ListConfiguration>
</UserConfigurations>'''
def clock(x,y,size):
 return f'''<PartText x="{x}" y="{y}" width="450" height="105">
 <Text><Font family="SYNC_TO_DEVICE" size="{size}" color="[CONFIGURATION.accent.1]">
 <Template>%s:%s<Parameter expression="[HOUR_0_23_Z]"/><Parameter expression="[MINUTE_Z]"/></Template>
 </Font></Text>
 </PartText>'''
xml=f'''<?xml version="1.0" encoding="utf-8"?>
<WatchFace width="450" height="450">
{settings}
<Scene>
  <PartDraw x="0" y="0" width="450" height="450">
   <Ellipse x="0" y="0" width="450" height="450"><Fill color="#FF07101B"/></Ellipse>
  </PartDraw>
  <!-- Full-frame background from the SAME selected scene inside the game. -->
  {background}
  <ListConfiguration id="contrast">
    <ListOption id="low"><PartDraw x="0" y="0" width="450" height="70">
      <Rectangle x="0" y="0" width="450" height="70"><Fill color="#33000000"/></Rectangle>
    </PartDraw></ListOption>
    <ListOption id="medium"><PartDraw x="0" y="0" width="450" height="108">
      <Rectangle x="0" y="0" width="450" height="108"><Fill color="#66000000"/></Rectangle>
    </PartDraw></ListOption>
    <ListOption id="high"><PartDraw x="0" y="0" width="450" height="146">
      <Rectangle x="0" y="0" width="450" height="146"><Fill color="#AA000000"/></Rectangle>
    </PartDraw></ListOption>
  </ListConfiguration>
  <!-- Partner: real imported card sprite, updated on swap/evolution. -->
  {partner(11,159,197,132,143)}
  {partner(12,122,177,206,207)}
  {partner(13,91,151,270,254)}
  <ListConfiguration id="clock_style">
    <ListOption id="compact">{clock(0,27,50)}</ListOption>
    <ListOption id="regular">{clock(0,22,66)}</ListOption>
    <ListOption id="giant">{clock(0,18,83)}</ListOption>
  </ListConfiguration>
  <!-- These are sourced from the game. No fake static numbers. -->
  {stat(21,52,350,110,42,"VitalsComplicationService","RANGED_VALUE")}
  {stat(22,166,350,110,42,"StepsComplicationService","SHORT_TEXT")}
  {stat(23,285,350,108,42,"HeartComplicationService","SHORT_TEXT")}
  <ListConfiguration id="stats">
    <ListOption id="all">
      <PartText x="52" y="397" width="110" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">VITALS</Font></Text></PartText>
      <PartText x="166" y="397" width="110" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">STEPS</Font></Text></PartText>
      <PartText x="285" y="397" width="108" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">BPM</Font></Text></PartText>
    </ListOption>
    <ListOption id="fitness">
      <PartText x="52" y="397" width="110" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">VITALS</Font></Text></PartText>
      <PartText x="166" y="397" width="110" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">STEPS</Font></Text></PartText>
    </ListOption>
    <ListOption id="minimal"></ListOption>
  </ListConfiguration>
</Scene>
</WatchFace>'''
ET.fromstring(xml)
(raw/"watchface.xml").write_text(xml,encoding="utf-8")
# Preview deliberately does not pretend a character has been imported; runtime shows real sprite.
im=Image.new("RGB",(450,450),"#0B253D");d=ImageDraw.Draw(im)
for y in range(450):
  r=int(4+(y/450)*14);g=int(62-(y/450)*28);b=int(96-(y/450)*24)
  d.line((0,y,449,y),fill=(r,g,b))
d.ellipse((12,12,438,438),outline=(83,220,230),width=5)
d.rectangle((60,285,390,343),fill=(24,73,82))
d.text((148,58),"10:08",fill=(255,255,255))
d.text((157,234),"GAME PARTNER",fill=(180,233,232))
d.text((70,377),"VITALS",fill=(95,229,224))
d.text((186,377),"STEPS",fill=(95,229,224))
d.text((308,377),"BPM",fill=(95,229,224))
im.save(drawable/"preview.png")
print("WFF face: game-scene background + dynamic character + 10 palettes / 3 pet sizes / 3 HUD modes / 3 clocks / 3 contrast styles.")
