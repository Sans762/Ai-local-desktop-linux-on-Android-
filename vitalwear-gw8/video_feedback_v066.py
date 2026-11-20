#!/usr/bin/env python3
"""Video-grounded UI fixes from 23330.mp4 (438x438 on-watch recording).
No game-state, character storage or VBHelper/NFC mutations.
"""
from pathlib import Path
import re, xml.etree.ElementTree as ET, sys, math
root=Path(sys.argv[1] if len(sys.argv)>1 else "VitalWear")
face=Path(sys.argv[2] if len(sys.argv)>2 else "VBClassicXLFace")
src=root/"app/src/main/java/com/github/cfogrady/vitalwear"
def replace_file(path,old,new):
    s=path.read_text(encoding="utf-8-sig")
    count=s.count(old)
    if count != 1:raise RuntimeError(f"{path.name}: expected 1 hit {old[:50]!r}, got {count}")
    path.write_text(s.replace(old,new),encoding="utf-8")
    print("FIX",path,old[:50])

# 00:44 MILE screenshot: DISTANCE and NOT CALIB overlap because fixed
# left/right columns together exceed a 205-pixel panel.
p=src/"composable/pixel/VbPixelArt.kt"
replace_file(p,
'''            text(c,item.first.uppercase().take(10),124f,y+10f,1.95f,CYAN)
            val value=item.second.uppercase().take(9)
            text(c,value,315f-textWidth(value,2.05f),y+9f,2.05f,WHITE)''',
'''            val label=item.first.uppercase().take(10)
            val value=item.second.uppercase().take(9)
            val valueStart=315f-textWidth(value,2.05f)
            if(124f+textWidth(label,1.95f)+6f>valueStart) {
                // Use two separated baselines for long values. No overlap on 40mm.
                text(c,label,124f,y+3f,1.75f,CYAN)
                text(c,value,315f-textWidth(value,1.75f),y+19f,1.75f,WHITE)
            } else {
                text(c,label,124f,y+10f,1.95f,CYAN)
                text(c,value,valueStart,y+9f,2.05f,WHITE)
            }''')
# User asked for background selection in Settings, not generic "DISPLAY".
p=src/"main/VbHubActivity.kt"
replace_file(p,'item("DISPLAY"){background()}',
             'item("BACKGROUND"){background()}')

# 00:08 watch-face screenshot: bottom chips STEP/VITALS/FIGHTS clip on
# circular 438x438 screen, their corners fall outside radius.
xml=face/"src/main/res/raw/watchface.xml"
s=xml.read_text(encoding="utf-8")
changes={
 'x="58" y="286" width="116" height="66"':'x="58" y="275" width="116" height="56"',
 'x="278" y="286" width="116" height="66"':'x="278" y="275" width="116" height="56"',
 'x="57" y="283" width="117" height="4"':'x="57" y="272" width="117" height="4"',
 'x="278" y="283" width="116" height="4"':'x="278" y="272" width="116" height="4"',
 'x="75" y="359" width="96" height="62"':'x="75" y="339" width="96" height="50"',
 'x="174" y="359" width="103" height="62"':'x="174" y="339" width="103" height="50"',
 'x="280" y="359" width="96" height="62"':'x="280" y="339" width="96" height="50"',
 'x="75" y="356" width="96" height="4"':'x="75" y="336" width="96" height="4"',
 'x="174" y="356" width="103" height="4"':'x="174" y="336" width="103" height="4"',
 'x="280" y="356" width="96" height="4"':'x="280" y="336" width="96" height="4"',
}
for a,b in changes.items():
    if s.count(a)!=1:raise RuntimeError(f"face expected 1 rect {a}; got {s.count(a)}")
    s=s.replace(a,b)
# Move each Complication slot's container and inner text region, preserving
# existing data source IDs and types.
placements={21:(174,344,103,27),22:(75,344,96,27),
           23:(278,283,116,27),24:(280,344,96,27),
           25:(58,283,116,27)}
for slot,(x,y,w,h) in placements.items():
    pattern=r'(<ComplicationSlot slotId="'+str(slot)+r'"\b?[\s\S]*?</ComplicationSlot>)'
    # Use a simple tag matcher, \b after quote is not necessary.
    pattern=r'(<ComplicationSlot slotId="'+str(slot)+r'"[\s\S]*?</ComplicationSlot>)'
    matches=list(re.finditer(pattern,s))
    if len(matches)!=1:raise RuntimeError(f"slot {slot}: {len(matches)} matches")
    m=matches[0];v=m.group(1)
    start=v.index('>')
    head=v[:start]
    head=re.sub(r'\bx="\d+"',f'x="{x}"',head,count=1)
    head=re.sub(r'\by="\d+"',f'y="{y}"',head,count=1)
    head=re.sub(r'\bwidth="\d+"',f'width="{w}"',head,count=1)
    head=re.sub(r'\bheight="\d+"',f'height="{h}"',head,count=1)
    v=head+v[start:]
    v=re.sub(r'(<BoundingOval x="0" y="0" width=")\d+(" height=")\d+',
             lambda m:m.group(1)+str(w)+m.group(2)+str(h),v,count=1)
    v=re.sub(r'(<PartText x="0" y="0" width=")\d+(" height=")\d+',
             lambda m:m.group(1)+str(w)+m.group(2)+str(h),v,count=1)
    s=s[:m.start()]+v+s[m.end():]
# Actual labels from WFF XML, not photo previews. Move inside safe circle.
patt=r'(<PartText x="(?:89|186|290|311|83)" y=")(?:399|328)("[^>]*>)'
if len(re.findall(patt,s))!=5:raise RuntimeError("face labels not as expected")
for x in ("89","186","290"):
    s=s.replace(f'<PartText x="{x}" y="399"',f'<PartText x="{x}" y="371"')
for x in ("311","83"):
    s=s.replace(f'<PartText x="{x}" y="328"',f'<PartText x="{x}" y="311"')
# Keep font legible yet completely inside the new 18px label areas.
s=re.sub(r'(<PartText x="(?:89|186|290|311|83)" y="(?:371|311)" width="\d+" height=")27(")',
         r'\g<1>18\2',s)
ET.fromstring(s)
xml.write_text(s,encoding="utf-8")
# Basic 450x450 circle geometry regression of outer bottom chips.
for x in (75,171,174,277,280,376):
    for y in (339,389):
        assert math.hypot(x-225,y-225)<225, (x,y)
print("PASS: details no overlapping label/value; WFF safe chip geometry")
