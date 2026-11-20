#!/usr/bin/env python3
"""Rebuild the standalone 40mm watch face as a full-round pixel forest.
Uses authentic VitalWear complication providers for Digimon, vitals, steps, BPM.
Uses a generated original pixel landscape, not the user's firmware or demo pet.
Run AFTER make_face_custom_full.py and BEFORE WFF4 validator.
"""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import random,re,xml.etree.ElementTree as ET

root=Path("VBClassicXLFace")
raw=root/"src/main/res/raw/watchface.xml"
drawables=root/"src/main/res/drawable"
drawables.mkdir(parents=True,exist_ok=True)
rng=random.Random(1458)
W=450
im=Image.new("RGB",(W,W))
d=ImageDraw.Draw(im)
# Distinct pixel-grid landscape in ~5px steps, no gradients from antialiasing.
for y in range(W):
    band=y//5
    if y<154:
        rgb=(11,119+min(65,band*2),207+min(32,band))
    elif y<220:
        rgb=(33,158,123)
    elif y<310:
        rgb=(53,194,86)
    else:
        rgb=(34,157,65)
    d.line((0,y,449,y), fill=rgb)
# Clouds: blocky white/blue pixel clusters
for x,y in [(59,72),(142,46),(310,65),(376,112)]:
    for dx,dy,w,h,color in [(-19,6,53,8,"#C9F6FF"),(-7,0,33,8,"#E6FCFF"),
                             (2,-8,21,8,"#E8FCFF"),(-30,14,73,5,"#66C8FA")]:
        d.rectangle((x+dx,y+dy,x+dx+w,y+dy+h),fill=color)
# Distant mountain ranges
for ox,peak,level in [(-45,90,219),(70,116,222),(164,80,221),(277,103,225),(355,133,239)]:
    d.polygon([(ox,level),(ox+68,peak),(ox+143,level)],fill="#3C7EC1")
    d.polygon([(ox+68,peak),(ox+98,level),(ox+138,level)],fill="#1E588F")
    d.polygon([(ox+43,peak+36),(ox+68,peak),(ox+91,peak+34),(ox+73,peak+24),
               (ox+58,peak+30)],fill="#DDF7FB")
# Landscape back layers
d.polygon([(0,213),(45,192),(95,213),(148,190),(200,219),(258,190),(318,213),
           (374,184),(450,225),(450,300),(0,300)],fill="#177C58")
d.polygon([(0,242),(73,231),(146,248),(215,224),(314,252),(450,224),
           (450,388),(0,380)],fill="#41BB49")
# river from upper-right into lower-right
d.polygon([(423,235),(449,243),(449,450),(265,450),(290,422),
           (349,394),(383,351),(386,309),(399,277)],fill="#116CD1")
d.polygon([(425,258),(450,268),(450,450),(287,450),(323,414),
           (365,384),(395,343),(404,305)],fill="#21BAF0")
for i in range(26):
    x=rng.randrange(310,445);y=rng.randrange(327,445)
    d.rectangle((x,y,x+15,y+3),fill=rng.choice(["#B7F9FC","#1874F4","#58DBFF"]))
# path from left to center
d.polygon([(0,322),(45,309),(100,302),(150,292),(207,262),(248,251),
           (271,250),(252,269),(225,284),(180,308),(123,352),(59,414),
           (0,439)],fill="#9C7E3B")
d.polygon([(0,308),(45,298),(100,291),(158,282),(212,251),(252,245),
           (263,251),(233,269),(197,288),(145,324),(85,379),
           (12,450),(0,450)],fill="#F6CC70")
for x,y in [(110,320),(174,288),(225,258),(64,373),(40,406)]:
    d.rectangle((x,y,x+18,y+4),fill="#F7DE8D")
# clumps of grass
for k in range(90):
    x=rng.randrange(10,438);y=rng.randrange(229,445)
    if 300<x and y>315: continue
    for dx,dy in [(-6,3),(0,0),(4,2)]:
        d.rectangle((x+dx,y+dy,x+dx+3,y+dy+8),fill=rng.choice(["#0F943B","#19A535","#8FEA37"]))
# rounded-ish pixel trees
def tree(x,y,s=1):
    d.rectangle((x-6*s,y-12*s,x+7*s,y+38*s),fill="#724728")
    d.rectangle((x+3*s,y-10*s,x+7*s,y+24*s),fill="#A7723A")
    chunks=[(-28,-18,20,12),(-14,-34,28,13),(13,-28,21,11),
            (-28,-4,23,16),(-4,-20,31,17),(17,-7,16,15)]
    for dx,dy,ww,hh in chunks:
        col=rng.choice(["#2EBB39","#6DE746","#15A83B","#ABEC4A"])
        d.rectangle((x+dx*s,y+dy*s,x+(dx+ww)*s,y+(dy+hh)*s),fill=col)
        d.rectangle((x+(dx+3)*s,y+(dy+3)*s,x+(dx+9)*s,y+(dy+7)*s),fill="#D1F64C")
for t in [(-1,170,2),(60,185,2),(344,183,2),(425,209,2),(24,268,1),(375,274,1)]:
    tree(*t)
# flowers and stones around path
for x,y in [(74,279),(119,257),(169,349),(252,298),(288,286),(127,401),(61,408)]:
    d.rectangle((x,y,x+5,y+5),fill="#FFFFFF")
    d.rectangle((x+3,y-3,x+6,y+9),fill="#F1F7FC")
    d.rectangle((x+2,y+2,x+5,y+5),fill="#FFCB2B")
for x,y in [(54,392),(245,344),(289,268),(82,240)]:
    d.polygon([(x-12,y+7),(x-6,y-8),(x+8,y-11),(x+17,y+5)],fill="#A9B2B7")
    d.polygon([(x-6,y-8),(x+8,y-11),(x+4,y-1)],fill="#CFD8DC")
# Markers and preview live within an otherwise valid forest scene
im.save(drawables/"forest_scene.png",optimize=True)

s=raw.read_text(encoding="utf-8-sig")
# Remove game full-screen photograph: this is a SEPARATE forest-themed face,
# not another screenshot of the application's configurable home screen.
s,n=re.subn(r'<ComplicationSlot slotId="1"[\s\S]*?</ComplicationSlot>', '', s,count=1)
if n!=1:raise RuntimeError("Could not locate scene photo complication")
needle='''  <!-- Full-frame background from the SAME selected scene inside the game. -->
  '''
if needle in s:s=s.replace(needle,"  <!-- Forest artwork is independent of the game Home scene. -->\n  ")
else:print("Background comment changed, proceeding")
# Insert artwork directly after first clear-screen PartDraw group.
start=s.find("<Scene>")
if start<0:raise RuntimeError("No Scene")
first_end=s.find("</PartDraw>",start)
if first_end<0:raise RuntimeError("No initial PartDraw")
pos=first_end+len("</PartDraw>")
s=s[:pos]+'''
  <PartImage x="0" y="0" width="450" height="450">
    <Image resource="forest_scene"/>
    <Variant mode="AMBIENT" target="alpha" value="0"/>
  </PartImage>
'''+s[pos:]
# Clock stays at top but split into high contrast over landscape.
# Existing 0.6.0 HUD panels at y344 remain, but replace older 3-column bars
# with two upper chips and three lower chips sized for round 438px display.
start=s.index('  <!-- Pixel HUD chips;')
end=s.index('  <!-- These are sourced from the game.',start)
huds='''  <!-- High-contrast forest pixel HUD, within central circle safe area. -->
  <PartDraw x="0" y="0" width="450" height="450">
    <Rectangle x="58" y="286" width="116" height="66"><Fill color="#DA041B32"/></Rectangle>
    <Rectangle x="278" y="286" width="116" height="66"><Fill color="#DA041B32"/></Rectangle>
    <Rectangle x="57" y="283" width="117" height="4"><Fill color="#FF5EE2FA"/></Rectangle>
    <Rectangle x="278" y="283" width="116" height="4"><Fill color="#FFFF6C9E"/></Rectangle>
    <Rectangle x="75" y="359" width="96" height="62"><Fill color="#E6071A31"/></Rectangle>
    <Rectangle x="174" y="359" width="103" height="62"><Fill color="#E6071A31"/></Rectangle>
    <Rectangle x="280" y="359" width="96" height="62"><Fill color="#E6071A31"/></Rectangle>
    <Rectangle x="75" y="356" width="96" height="4"><Fill color="#FFF0BE47"/></Rectangle>
    <Rectangle x="174" y="356" width="103" height="4"><Fill color="#FF9BF267"/></Rectangle>
    <Rectangle x="280" y="356" width="96" height="4"><Fill color="#FFC69CFA"/></Rectangle>
  </PartDraw>
'''
s=s[:start]+huds+s[end:]
# Make partner image less enormous in its middle-screen position
m=re.search(r'<ComplicationSlot slotId="12"[\s\S]*?</ComplicationSlot>',s)
if m:
    part=m.group(0)
    part=part.replace('x="122" y="177" width="206" height="207"',
                      'x="150" y="194" width="148" height="140"',1)
    part=part.replace('<BoundingOval x="0" y="0" width="206" height="207"/>',
                      '<BoundingOval x="0" y="0" width="148" height="140"/>')
    part=part.replace('<PartImage x="0" y="0" width="206" height="207">',
                      '<PartImage x="0" y="0" width="148" height="140">')
    s=s[:m.start()]+part+s[m.end():]
# Move game stats (value slots) to the right chips. Preserve providers and IDs.
def shift_slot(xml,slot_id,x,y,w,h):
    pattern=r'<ComplicationSlot slotId="'+str(slot_id)+r'"[\s\S]*?</ComplicationSlot>'
    found=re.search(pattern,xml)
    if not found:raise RuntimeError("Missing stat slot "+str(slot_id))
    block=found.group(0)
    block=re.sub(r' x="\d+" y="\d+" width="\d+" height="\d+"',
        f' x="{x}" y="{y}" width="{w}" height="{h}"',block,count=1)
    block=re.sub(r'<BoundingOval x="0" y="0" width="\d+" height="\d+"',
        f'<BoundingOval x="0" y="0" width="{w}" height="{h}"',block,count=1)
    block=re.sub(r'<PartText x="0" y="0" width="\d+" height="\d+"',
        f'<PartText x="0" y="0" width="{w}" height="{h}"',block,count=1)
    return xml[:found.start()]+block+xml[found.end():]
s=shift_slot(s,21,174,370,103,42) # Vitals
s=shift_slot(s,22,75,370,96,42)  # Steps
s=shift_slot(s,23,278,300,116,42) # BPM
# Existing label text under stats is removed; replace by exact chip labels.
patt=r'<PartText x="52" y="397"[\s\S]*?<PartText x="285" y="397"[\s\S]*?</PartText>'
s,count=re.subn(patt,'',s,count=1)
# May be trailing other labels from the original WFF: strip any at y397
s=re.sub(r'<PartText x="\d+" y="397"[\s\S]*?</PartText>','',s)
labels='''  <PartText x="89" y="399" width="80" height="27">
    <Text><Font family="SYNC_TO_DEVICE" size="16" color="#FFFFE6A0">STEPS</Font></Text></PartText>
  <PartText x="186" y="399" width="92" height="27">
    <Text><Font family="SYNC_TO_DEVICE" size="16" color="#FFBCFF9B">VITALS</Font></Text></PartText>
  <PartText x="311" y="328" width="70" height="25">
    <Text><Font family="SYNC_TO_DEVICE" size="16" color="#FFFF92BE">BPM</Font></Text></PartText>
  <PartText x="83" y="328" width="85" height="25">
    <Text><Font family="SYNC_TO_DEVICE" size="16" color="#FFA2F1FF">ACTIVE</Font></Text></PartText>
  <PartText x="290" y="399" width="86" height="27">
    <Text><Font family="SYNC_TO_DEVICE" size="16" color="#FFE3BCFF">FIGHTS</Font></Text></PartText>
'''
s=s.replace("</Scene>",labels+"\n</Scene>")
# Update empty active/fights chips to show real data via new game providers.
service="dev.mikeki.vitalwear.gw8/com.github.cfogrady.vitalwear.complications."
def data_slot(slot_id,x,y,w,h,name):
    return f'''  <ComplicationSlot slotId="{slot_id}" supportedTypes="SHORT_TEXT EMPTY"
      isCustomizable="TRUE" x="{x}" y="{y}" width="{w}" height="{h}">
    <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"
      primaryProvider="{service+name}" primaryProviderType="SHORT_TEXT"/>
    <BoundingOval x="0" y="0" width="{w}" height="{h}"/>
    <Complication type="SHORT_TEXT"><PartText x="0" y="0" width="{w}" height="{h}">
      <Text><Font family="SYNC_TO_DEVICE" size="25" color="#FFFFFFFF">
        <Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template>
      </Font></Text></PartText></Complication>
  </ComplicationSlot>
'''
s=s.replace("</Scene>",data_slot(24,280,370,96,42,"BattlesComplicationService")+
    data_slot(25,58,300,116,42,"ActivityComplicationService")+"</Scene>")
ET.fromstring(s)
raw.write_text(s,encoding="utf-8")
# Pixel-art preview is a layout-only screenshot; never display demo game stats.
preview=im.copy()
dc=ImageDraw.Draw(preview)
dc.ellipse((10,10,440,440),outline="#173D60",width=6)
dc.rectangle((95,73,353,140),fill="#07172A")
dc.text((157,90),"10:08",fill="#FFFFFF",stroke_width=0)
dc.rectangle((58,286,174,352),fill="#06243C")
dc.rectangle((278,286,394,352),fill="#06243C")
for x0,x1 in [(75,171),(174,277),(280,376)]:
    dc.rectangle((x0,359,x1,421),fill="#071A31")
dc.text((102,325),"ACTIVE",fill="#B5FCFF")
dc.text((310,325),"BPM",fill="#FFD1DA")
dc.text((94,395),"STEPS",fill="#FFFFFF")
dc.text((193,395),"VITALS",fill="#FFFFFF")
dc.text((288,395),"FIGHTS",fill="#FFFFFF")
preview.save(drawables/"preview.png")

# Add live Complication providers to the watch app (only read existing stats).
app=root.parent/"VitalWear/app/src/main/java/com/github/cfogrady/vitalwear"
provider=app/"complications"
def new_provider(name,value,label):
    code=f'''package com.github.cfogrady.vitalwear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationRequestListener
import com.github.cfogrady.vitalwear.VitalWearApp
import com.github.cfogrady.vitalwear.main.MainActivity

class {name}ComplicationService:ComplicationDataSourceService() {{
    override fun getPreviewData(type:ComplicationType):ComplicationData?=data()
    override fun onComplicationRequest(request:ComplicationRequest,
        listener:ComplicationRequestListener) {{ listener.onComplicationData(data()) }}
    private fun data():ComplicationData {{
        val app=application as VitalWearApp
        val value=({value}).toString()
        val pending=PendingIntent.getActivity(this,0,
            Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(value).build(),
            PlainComplicationText.Builder("{label}").build()
        ).setTapAction(pending).build()
    }}
}}
'''
    (provider/(name+"ComplicationService.kt")).write_text(code,encoding="utf-8")
new_provider("Battles","app.characterManager.getCurrentCharacter()?.characterStats?.totalBattles ?: 0","Digimon battles")
new_provider("Activity","app.characterManager.getCurrentCharacter()?.characterStats?.activityLevel ?: 0","Digimon activity level")
manifest=root.parent/"VitalWear/app/src/main/AndroidManifest.xml"
txt=manifest.read_text(encoding="utf-8")
needle='''        <service
            android:name=".complications.PartnerComplicationService"'''
if txt.count(needle)!=1:raise RuntimeError("Complications manifest anchor changed")
tag=lambda name:f'''        <service android:name=".complications.{name}ComplicationService"
            android:label="Digimon {name}"
            android:exported="true"
            android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER">
          <intent-filter><action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST"/></intent-filter>
          <meta-data android:name="android.support.wearable.complications.SUPPORTED_TYPES" android:value="SHORT_TEXT"/>
          <meta-data android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS" android:value="300"/>
        </service>
'''
txt=txt.replace(needle,tag("Battles")+tag("Activity")+needle)
manifest.write_text(txt,encoding="utf-8")
# Face APK version is independent of app game version (and same app ID).
gradle=root/"build.gradle.kts"
g=gradle.read_text(encoding="utf-8")
g=g.replace("versionCode = 7","versionCode = 65").replace('versionName = "0.6.0"','versionName = "0.6.5-forest"')
gradle.write_text(g,encoding="utf-8")
print("REAL PIXEL FOREST WFF4 + LIVE BATTLE/ACTIVITY STATS COMPLETE")
