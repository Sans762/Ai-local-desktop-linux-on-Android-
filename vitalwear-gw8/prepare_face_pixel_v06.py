#!/usr/bin/env python3
"""Add pixel-stat HUD panels to the real evolving Digimon watch face.
Values remain sourced from game's Complications (never sample values).
"""
from pathlib import Path
p=Path("vitalwear-gw8/make_face_custom_full.py")
s=p.read_text(encoding="utf-8-sig")
s=s.replace("versionCode = 6","versionCode = 7").replace('versionName = "0.5.6"','versionName = "0.6.0"')
needle='''  <!-- These are sourced from the game. No fake static numbers. -->
  {stat(21,52,350,110,42,"VitalsComplicationService","RANGED_VALUE")}'''
box='''  <!-- Pixel HUD chips; transparent live game scenery underneath. -->
  <PartDraw x="0" y="0" width="450" height="450">
    <Rectangle x="45" y="344" width="118" height="80"><Fill color="#D9091930"/></Rectangle>
    <Rectangle x="166" y="344" width="112" height="80"><Fill color="#D9091930"/></Rectangle>
    <Rectangle x="282" y="344" width="112" height="80"><Fill color="#D9091930"/></Rectangle>
    <Rectangle x="45" y="344" width="118" height="4"><Fill color="[CONFIGURATION.accent.0]"/></Rectangle>
    <Rectangle x="166" y="344" width="112" height="4"><Fill color="[CONFIGURATION.accent.0]"/></Rectangle>
    <Rectangle x="282" y="344" width="112" height="4"><Fill color="[CONFIGURATION.accent.0]"/></Rectangle>
  </PartDraw>
  <!-- These are sourced from the game. No fake static numbers. -->
  {stat(21,52,350,110,42,"VitalsComplicationService","RANGED_VALUE")}'''
if s.count(needle)!=1:raise RuntimeError("face scene marker not found")
s=s.replace(needle,box)
# Correct WFF4-only syntax: list-option complicationSlotIds is WFF5-only.
# A SINGLE partner image avoids simultaneous overlapping sprites.
import re
start=s.index(' <ListConfiguration id="pet_size"')
end=s.index('</ListConfiguration>',start)+len('</ListConfiguration>')
s=s[:start]+s[end:]
s=re.sub(r' complicationSlotIds="[^"]*"', '', s)
s=s.replace('  {partner(11,159,197,132,143)}\n', '')
s=s.replace('  {partner(13,91,151,270,254)}\n', '')
s=s.replace('"stats_label":"HUD Detail"','"stats_label":"HUD Labels"')
s=s.replace('"all":"All stats","fitness":"Fitness","minimal":"Clean"',
            '"all":"All Labels","fitness":"Fitness Labels","minimal":"No Labels"')

# WFF4 enum values are upper-case booleans for isCustomizable.
s=s.replace('custom="false"', 'custom="FALSE"').replace('custom="true"', 'custom="TRUE"')
# WFF4 ListOption only accepts limited child parts, not multi-label HUD blocks.
# Replace unsupported multi-label configuration with static real-value labels.
begin=s.index(' <ListConfiguration id="stats" displayName="stats_label"')
end=s.index('</ListConfiguration>',begin)+len('</ListConfiguration>')
s=s[:begin]+s[end:]
begin=s.index('  <ListConfiguration id="stats">\n')
end=s.index('</ListConfiguration>',begin)+len('</ListConfiguration>')
labels='''  <PartText x="52" y="397" width="110" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">VITALS</Font></Text></PartText>
  <PartText x="166" y="397" width="110" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">STEPS</Font></Text></PartText>
  <PartText x="285" y="397" width="108" height="28"><Text><Font family="SYNC_TO_DEVICE" size="17" color="[CONFIGURATION.accent.0]">BPM</Font></Text></PartText>'''
s=s[:begin]+labels+s[end:]

# correct already-dynamic photo and partner source; avoid fake data.
p.write_text(s,encoding="utf-8")
print("Updated WFF watch face 0.6.0 pixel HUD")
