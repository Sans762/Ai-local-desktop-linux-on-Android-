#!/usr/bin/env python3
"""Produce a genuinely restricted Android build, with no Accessibility component.

This variant retains offline AI, Linux, and consent-based view-only screen sharing.
It is deliberately NOT a full remote-input controller.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path("app/src/main")
manifest = root / "AndroidManifest.xml"
android_ns = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", android_ns)
tree = ET.parse(manifest)
xml = tree.getroot()
application = xml.find("application")
assert application is not None
name_attr = "{" + android_ns + "}name"
sensitive = [
    x for x in list(application)
    if x.tag == "service"
    and x.attrib.get(name_attr) == ".AutomationAccessibilityService"
]
assert len(sensitive) == 1, "Expected exactly one Accessibility Service; inspect source"
for service in sensitive:
    application.remove(service)
application.set("{" + android_ns + "}label", "Local AI Native · Safe")
tree.write(manifest, encoding="utf-8", xml_declaration=True)
assert b"BIND_ACCESSIBILITY_SERVICE" not in manifest.read_bytes()

gradle = Path("app/build.gradle.kts")
g = gradle.read_text()
old_package = 'applicationId = "com.localai.controller"'
assert g.count(old_package) == 1, "App package definition changed"
g = g.replace(old_package, 'applicationId = "com.localai.controller.safe"')
g = g.replace('versionName = "0.4.0-native-linux-ai"', 'versionName = "0.4.1-safe-ai-linux"')
gradle.write_text(g)

main = root / "java/com/localai/controller/MainActivity.kt"
s = main.read_text()
start = '        cardBlock(content) {\n            addView(label("04  /  ควบคุมหน้าจอ Android"'
end = '        cardBlock(content) {\n            addView(label("05  /  Remote Desktop"'
assert s.count(start) == 1 and s.count(end) == 1
a = s.index(start)
b = s.index(end, a)
s = s[:a] + '''        cardBlock(content) {
            addView(label("04  /  การควบคุมอุปกรณ์", 12f, true, mint))
            spacer(this, 9)
            addView(label("รุ่น Safe ไม่ขอสิทธิ์ Accessibility จึงดูหน้าจอได้อย่างเดียว ไม่สามารถคลิก พิมพ์ หรือสั่งงานแอปอื่นจากระยะไกล", 13f, false, sub))
            store.setAllowUiControl(false)
            RemoteSession.allowInput = false
        }
''' + s[b:]
remote_start = '                val remoteInput = CheckBox(this@MainActivity).apply {'
remote_end = '                addView(remoteInput)'
assert s.count(remote_start) == 1 and s.count(remote_end) == 1
a = s.index(remote_start)
b = s.index(remote_end, a) + len(remote_end)
s = s[:a] + '''                addView(label("โหมดดูอย่างเดียว · Remote Input ปิดใช้งานในรุ่นนี้", 12f, false, sub))''' + s[b:]
main.write_text(s)

server = root / "java/com/localai/controller/RemoteHttpServer.kt"
s = server.read_text()
from_status = '.put("control", RemoteSession.allowInput && AppStore(context).allowUiControl() && AutomationAccessibilityService.current != null)'
assert s.count(from_status) == 1
s = s.replace(from_status, '.put("control", false)')
input_case = '                    "POST" to "/action" -> {'
assert s.count(input_case) == 1
s = s.replace(input_case,
    input_case + '\n                        fail(sock, 403, "REMOTE_INPUT_NOT_AVAILABLE_IN_SAFE_BUILD"); return')
server.write_text(s)
print("SAFE VARIANT READY: no Accessibility service; remote input explicitly disabled")
