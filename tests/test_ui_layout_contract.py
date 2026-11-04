#!/usr/bin/env python3
"""Static regression checks for Android + Linux Workspace UI.
These do not replace real-device layout screenshots or WebView tests.
"""
from html.parser import HTMLParser
from pathlib import Path
import re
import subprocess
import tempfile

root = Path("app/src/main")
assets = root / "assets"
native = (root / "java/com/localai/controller/MainActivity.kt").read_text()
css = (assets / "app.css").read_text()
js = (assets / "app.js").read_text()
html = (assets / "index.html").read_text()
desktop = (assets / "desktop.html").read_text()
manifest = (root / "AndroidManifest.xml").read_text()

class Collector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.duplicates = set()
        self.current_tag = ""
        self.module_script = []
        self.in_module = False
    def handle_starttag(self, tag, attrs):
        props = dict(attrs)
        id_ = props.get("id")
        if id_:
            if id_ in self.ids:
                self.duplicates.add(id_)
            self.ids.add(id_)
        if tag == "script" and props.get("type") == "module":
            self.in_module = True
    def handle_endtag(self, tag):
        if tag == "script":
            self.in_module = False
    def handle_data(self, data):
        if self.in_module:
            self.module_script.append(data)

app = Collector(); app.feed(html)
vnc = Collector(); vnc.feed(desktop)
assert not app.duplicates and not vnc.duplicates, "Duplicate UI IDs found"
assert "width=device-width" in html and "width=device-width" in desktop
assert "วันนี้ให้ช่วยอะไรดี?" in html, "Chat welcome copy should be friendly and short"
assert "advanced-console" in html, "Manual Linux terminal must be collapsed for new users"
assert 'id="chatDesktopPanel"' not in html, "Do not embed VNC controls in chat"
assert 'id="desktopToggle"' not in html, "Chat must be conversation-only"
assert 'id="monitorDetails"' not in html, "Monitoring belongs in the dedicated system screen"
assert ".topbar,.tabs{display:none!important}" in css, "No duplicate Android/WebView headers on phones"
assert "if (!textarea.value.trim()) textarea.value = content;" in js, "Failed chat must preserve draft"
assert "private fun showWebFailure(view: WebView?)" in native, "Localhost WebView failure needs native retry"
assert "request?.isForMainFrame == true" in native, "Avoid overlays from subresource errors"
assert "else desktop.reload()" in native, "Reopening Linux Desktop should refresh disconnected viewer"
assert "private fun awaitAiReady(remainingChecks: Int)" in native, "AI startup should retry until ready"
assert "awaitAiReady(20)" in native and "AiForegroundService.lastError = \"\"" in native
assert "}, 900)" not in native, "Do not assume AI is ready after a fixed short delay"
assert "Beginner web UI:" in css, "Small-screen monitor and chat spacing not customized"
assert "viewport-fit=cover" in html and "viewport-fit=cover" in desktop
assert "--app-height" in css and "visualViewport" in js, "Keyboard viewport fix missing"
assert "@media (max-width:430px)" in css and "@media (max-width:330px)" in css
assert "min-height:360px" not in desktop, "VNC viewport has a hard min-height"
assert "min-height:0" in desktop and "--viewer-height" in desktop
assert "compactScreen()" in native and "LinearLayout.LayoutParams(0, -2, 1f)" in native
assert 'private fun showHome()' in native
assert 'showTab("home")' in native
for label in ['หน้าแรก','แชท','Linux','เครื่องมือ','ตั้งค่า']:
    assert label in native, f"Missing compact navigation label: {label}"
assert 'desktopTab.background = if (target == "desktop")' in native
assert 'bottom.addView(button, LinearLayout.LayoutParams(0, -2, 1f)' in native
assert 'if (desktopRunning) showTab("desktop")' in native
assert 'else openSetting(if (linuxReady) "desktop" else "linux")' in native
assert "NATIVE WORKSPACE  •  V0.4" not in native
assert 'addCategoryMenu(this)' in native, "Missing settings category menu"
assert 'addSetupCheck(this' in native, "Missing guided checklist"
assert '"ความพร้อม: $completed/4"' in native, "Missing setup progress feedback"
assert native.count('categoryCard(content,') == 6, "Expected six compact setting panels across five categories"
assert native.count('if (settingsCategory == "ai")') >= 1
assert native.count('if (settingsCategory == "linux")') >= 1
for color in ('linuxGreen', 'aiPurple', 'desktopOrange', 'privacyBlue', 'dataPink'):
    assert color in native, f"Missing color category {color}"
assert 'advancedSettingsVisible' in native, "Advanced controls must not overwhelm new users"
assert 'setupChecklistExpanded' in native, "Onboarding checklist must be collapsible"
assert 'aiOptionsExpanded' in native, "Optional AI models should not crowd the default screen"
assert 'dataAdvancedExpanded' in native, "Advanced data actions should not crowd the default screen"
assert 'else -> "▶  เริ่ม AI"' in native, "Home should present one main AI action"
assert 'ซ่อนรายการตั้งค่า' in native, "Checklist should stay collapsed by default"
assert 'arrayOf("▣  หน้าจอคอม"' in native

assert "AccessibilityService" not in manifest and "mediaProjection" not in manifest
assert "AppUpdateDownloadService" in manifest, "Missing in-app updater service"
assert "FileProvider" in manifest, "Missing restricted APK FileProvider"
assert "REQUEST_INSTALL_PACKAGES" in manifest, "User-approved update installer permission missing"
assert "android.permission.ACCESS_NETWORK_STATE" in manifest

required_app = {
    "login","dashboard","chatTab","deviceTab","aboutTab",
    "messages","message","send","linuxTask","linuxPlan","linuxPlanView",
    "linuxPlanCommands","linuxExecute","linuxCancel","linuxCommand",
    "linuxRun","linuxCheck","linuxOutput","linuxReady","model","ram",
}
assert "desktopToggle" not in js and "refreshMonitor()" not in js, "Removed chat controls must not leave JS handlers"
assert 'function isLinuxAction(message)' in js
assert 'async function planChatLinuxTask(task, indicator)' in js
assert "await request('/api/linux/plan', {task})" in js
assert "await request('/api/linux/execute', {planId:data.planId})" in js
assert 'อนุมัติให้ทำ' in js, "Linux commands need visible user approval"
assert 'ai.get' not in js
agent = (root / "java/com/localai/controller/LinuxAgent.kt").read_text()
assistant = (root / "java/com/localai/controller/NativeAgent.kt").read_text()
assert "xdotool getactivewindow getwindowname" in agent
assert "xdotool getmouselocation --shell" in agent
assert "readVisibleText()" in agent, "Visual OCR observation needs to be available"
assert "visionInstalled(context)" in agent
assert "guiVerified" in agent, "Do not claim pixel verification just from command exit"
assert "db.rememberLinuxSuccess(plan.summary)" in agent
assert 'private val han' in assistant
assert 'han.containsMatchIn(draft)' in assistant
assert 'replyLanguage(text)' in assistant
required_vnc = {"status","connect","disconnect","password","viewport","screen","cover"}
assert required_app <= app.ids, f"Missing app UI ids: {required_app - app.ids}"
assert required_vnc <= vnc.ids, f"Missing desktop UI ids: {required_vnc - vnc.ids}"
for match in re.findall(r"\$\('([^']+)'\)", js):
    assert match in app.ids, f"app.js references missing id: {match}"

assert 'id="manualOptions"' in desktop
assert 'id="authBar"' in desktop
assert 'id="linuxKeyboard"' in desktop
assert 'id="trackpad"' in desktop
assert 'id="keyboard"' in desktop
assert "el('authBar').hidden=true" in desktop
assert 'private fun requestDesktopAiPlan(' in native
assert 'private fun executeDesktopAiPlan(' in native
assert 'desktopLinuxAgent.propose(task)' in native
assert 'desktopLinuxAgent.execute(planId)' in native
assert 'อนุมัติและทำ' in native
assert len(vnc.module_script) == 1
with tempfile.TemporaryDirectory() as tmp:
    script_file = Path(tmp) / "desktop-viewer.mjs"
    script_file.write_text(vnc.module_script[0])
    subprocess.run(["node", "--check", str(script_file)], check=True)
subprocess.run(["node", "--check", str(assets / "app.js")], check=True)

# Real feature status and one-action home; never claim AI/Linux/PC ready without checks.
assert 'val modelReady = imports.modelFile() != null' in native
assert 'val linuxReady = LinuxRuntime(this).installed()' in native
assert 'val desktopReady = LinuxDesktopService.installed(this)' in native
assert 'val desktopRunning = LinuxDesktopService.running' in native
assert 'Linux ในแอปนี้อยู่บนมือถือ ไม่ใช่หน้าจอ Windows PC' in native

print("PASS: UI HTML IDs, native responsive navigation, mobile viewport, VNC fit, JS syntax")
