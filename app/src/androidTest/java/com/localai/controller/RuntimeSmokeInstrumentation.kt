package com.localai.controller

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device smoke tests of the shipped app, not mocks. Run only in a disposable
 * TEST installation. Never clears data, changes a user's password or imports a model.
 * adb shell am instrument -w -e desktop true <test-package>/<this class>
 */
class RuntimeSmokeInstrumentation : Instrumentation() {
    private var options = Bundle()
    private val checks = JSONArray()
    private lateinit var outputDir: File
    private lateinit var activity: Activity
    private val browserConsole = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    private fun record(name: String, operation: () -> String): Boolean {
        val start = SystemClock.elapsedRealtime()
        var passed = false
        val item = JSONObject().put("name", name)
        try {
            item.put("detail", operation().take(6000)).put("pass", true)
            passed = true
        } catch (failure: Throwable) {
            item.put("pass", false).put("detail",
                (failure.javaClass.simpleName + ": " + failure.message).take(6000))
            if (name.startsWith("novnc") && ::activity.isInitialized) {
                try {
                    item.put("console", JSONArray(browserConsole.takeLast(12)))
                    item.put("page", js("({url:location.href,status:document.getElementById('status')?.textContent,error:window.localaiViewerError," +
                        "canvas:!!document.querySelector('#screen canvas')})"))
                    screenshot("failure-" + name)
                } catch (_: Throwable) { }
            }
        }
        item.put("ms", SystemClock.elapsedRealtime() - start)
        checks.put(item)
        sendStatus(0, Bundle().apply { putString("stream", item.toString() + "\n") })
        return passed
    }

    override fun onStart() {
        try {
            check(targetContext.packageName.contains(".test.") ||
                targetContext.packageName.endsWith(".emulator")) {
                "Use a disposable TEST package; production data is not a test fixture"
            }
            outputDir = File(targetContext.filesDir, "runtime-smoke").apply { mkdirs() }
            if (options.getString("phase") == "recover") {
                record("checkpoint_survives_process_death") {
                    val previousPid = File(outputDir, "prepare-pid").readText()
                    check(previousPid != android.os.Process.myPid().toString())
                    val recovered = TaskCheckpointStore(File(outputDir, "process-recovery"), "chat").restore()!!
                    check(recovered.id == "cross-process-smoke" && recovered.recovered &&
                        recovered.evidence == "actual evidence before process exit" &&
                        recovered.request == "ทำงานค้างภาษาไทย" && !recovered.beginExecution())
                    "Recovered in a different Android PID; request/evidence intact; execution still requires fresh approval"
                }
                return finishReport()
            }
            record("android_environment") {
                "SDK=" + Build.VERSION.SDK_INT + "; ABI=" +
                    Build.SUPPORTED_ABIS.joinToString() + "; hardware=" + Build.HARDWARE
            }
            val home = record("launch_home") {
                activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitForIdleSync()
                await(20_000) { activity.window.decorView.hasWindowFocus() && homePixelsReady() }
                screenshot("home")
                check(activity.window.decorView.width > 0)
                "Native home rendered"
            }
            if (!home) return finishReport()
            if (options.getString("phase") == "viewer") {
                viewerProbe()
                return finishReport()
            }
            androidChecks()
            val linux = LinuxRuntime(targetContext)
            if (!record("extract_bundled_linux") {
                linux.install { }
                check(linux.available())
                "Bundled rootfs and native executables present"
            }) return finishReport()
            record("preserve_existing_rootfs") {
                val marker = File(targetContext.filesDir, "embedded_linux/alpine-rootfs/root/runtime-smoke-retention.txt")
                marker.writeText("DO_NOT_OVERWRITE")
                linux.install { }
                check(marker.readText() == "DO_NOT_OVERWRITE")
                linux.prepareDesktopTemp()
                val temp = File(targetContext.filesDir, "embedded_linux/alpine-rootfs/tmp")
                check(android.system.Os.stat(temp.path).st_mode and 4095 == 1023)
                "Reinstall retained the existing Linux file; private temp has mode 01777"
            }
            if (!record("execute_linux") {
                val result = linux.diagnostics()
                check(result.trimEnd().endsWith("[exit 0]") && "LINUX_OK" in result &&
                    "HOME_WRITABLE" in result) { result }
                result
            }) return finishReport()
            if (options.getString("desktop") != "true") return finishReport()
            if (!record("desktop_packages") {
                if (!LinuxDesktopService.installed(targetContext)) {
                    val old = LinuxDesktopService.status(targetContext)
                    targetContext.startForegroundService(Intent(targetContext,
                        LinuxDesktopService::class.java).setAction(LinuxDesktopService.ACTION_INSTALL))
                    await(15_000) { LinuxDesktopService.installing ||
                        LinuxDesktopService.status(targetContext) != old }
                    await(510_000) { !LinuxDesktopService.installing }
                }
                check(LinuxDesktopService.installed(targetContext)) {
                    LinuxDesktopService.status(targetContext) + "\n" + LinuxDesktopService.error(targetContext)
                }
                "Xvnc, Openbox and desktop packages installed"
            }) return finishReport()
            if (!record("start_desktop") { startDesktop(); LinuxDesktopService.status(targetContext) })
                return finishReport()
            record("x11_clients") {
                val result = linux.command("DISPLAY=:1 xdotool getdisplaygeometry; " +
                    "DISPLAY=:1 xdotool search --onlyvisible --class XTerm")
                check(result.trimEnd().endsWith("[exit 0]")) { result }
                result
            }
            record("desktop_shell_windows") {
                var result = ""
                await(15_000) {
                    result = linux.command("DISPLAY=:1 xdotool search --onlyvisible --class 'Pcmanfm|pcmanfm' && " +
                        "DISPLAY=:1 xdotool search --onlyvisible --class tint2", 3)
                    result.trimEnd().endsWith("[exit 0]")
                }
                result
            }
            record("desktop_controls") {
                runOnMainSync {
                    val field = MainActivity::class.java.getDeclaredField("desktopWebView")
                        .apply { isAccessible = true }
                    field.set(activity, createDebugViewer())
                    MainActivity::class.java.getDeclaredMethod("showTab", String::class.java)
                        .apply { isAccessible = true }.invoke(activity, "desktop")
                }
                Thread.sleep(500)
                // A fresh emulator shows Android's first-use immersive-mode explanation.
                uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Got it")
                    ?.firstOrNull { it.packageName?.toString() == "com.android.systemui" }
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                waitForIdleSync()
                check(findView("หยุด Linux Desktop")?.isShown == true)
                check(findView("หยุดงาน AI")?.isShown == true)
                "Both stop buttons visible in the full-screen desktop"
            }
            val viewer = record("novnc_framebuffer") {
                await(25_000) {
                    js("Boolean(location.href.startsWith('http://127.0.0.1:6080/') && " +
                        "document.readyState==='complete' && document.getElementById('connect') && !document.getElementById('connect').disabled)") == "true"
                }
                js("document.getElementById('password').value='731924';" +
                    "document.getElementById('connect').click(); 'requested'")
                await(30_000) {
                    js("document.getElementById('status').textContent").contains("เชื่อมต่อแล้ว")
                }
                await(15_000) { js(canvasProbe()).contains("\"nonUniform\":true") }
                val pixels = js(canvasProbe())
                screenshot("desktop")
                pixels
            }
            if (viewer) record("novnc_pointer_and_click") {
                val reset = linux.command("DISPLAY=:1 xdotool windowfocus 0")
                check(reset.trimEnd().endsWith("[exit 0]")) { reset }
                js("""(() => {
                    const c=document.querySelector('#screen canvas'), b=c.getBoundingClientRect();
                    const x=b.left+b.width*180/c.width, y=b.top+b.height*140/c.height;
                    for(const type of ['mousemove','mousedown','mouseup']) {
                        c.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,
                            view:window,clientX:x,clientY:y,button:0,buttons:type==='mousedown'?1:0}));
                    }
                    return 'clicked';
                })()""")
                var result = ""
                await(8000) {
                    result = linux.command("DISPLAY=:1 xdotool getmouselocation --shell; " +
                        "DISPLAY=:1 xdotool getwindowfocus getwindowclassname", 3)
                    val x = Regex("""(?m)^X=(\d+)$""").find(result)?.groupValues?.get(1)?.toIntOrNull()
                    val y = Regex("""(?m)^Y=(\d+)$""").find(result)?.groupValues?.get(1)?.toIntOrNull()
                    x != null && y != null && kotlin.math.abs(x - 180) <= 2 &&
                        kotlin.math.abs(y - 140) <= 2 && "XTerm" in result
                }
                result
            }
            record("desktop_ai_bubble") {
                fun visibleInputs(view: View): Int {
                    var count = if (view is android.widget.EditText && view.isShown) 1 else 0
                    if (view is ViewGroup) for (index in 0 until view.childCount)
                        count += visibleInputs(view.getChildAt(index))
                    return count
                }
                runOnMainSync {
                    check(visibleInputs(activity.window.decorView) == 0)
                    check(findView("เปิดหรือซ่อนผู้ช่วย AI")?.performClick() == true)
                    check(visibleInputs(activity.window.decorView) == 1)
                    check(findView("เปิดหรือซ่อนผู้ช่วย AI")?.performClick() == true)
                    check(visibleInputs(activity.window.decorView) == 0)
                }
                "Native AI bubble opens and hides its input without replacing the desktop"
            }
            if (viewer) record("novnc_keyboard_to_linux") {
                val focus = linux.command("DISPLAY=:1 xdotool search --onlyvisible --class XTerm " +
                    "windowactivate --sync")
                check(focus.trimEnd().endsWith("[exit 0]")) { focus }
                val expected = "RUNTIME_KEYBOARD_OK_" + java.util.UUID.randomUUID().toString().replace("-", "")
                val command = "printf " + expected + " > /root/runtime-smoke-keyboard.txt"
                js("var i=document.getElementById('linuxKeyboard'); i.value=" +
                    JSONObject.quote(command) +
                    "; i.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "i.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})); 'sent'")
                var result = ""
                await(12_000) {
                    result = linux.command("cat /root/runtime-smoke-keyboard.txt", 3)
                    expected in result
                }
                result
            }
            if (viewer) record("rotation_keeps_connected_desktop") {
                val sizes = mutableListOf<String>()
                try {
                    for (rotation in listOf(android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_0)) {
                        check(uiAutomation.setRotation(rotation))
                        await(12_000) {
                            check(!activity.isDestroyed) { "Rotation recreated the desktop Activity" }
                            val view = activity.window.decorView
                            if (rotation == android.view.Surface.ROTATION_90) view.width > view.height
                            else view.height > view.width
                        }
                        await(10_000) {
                            js("""(() => {
                                const c=document.querySelector('#screen canvas');
                                if(!c)return false;
                                const b=c.getBoundingClientRect();
                                return b.width>0 && b.height>0 && b.left>=-1 && b.top>=-1 &&
                                    b.right<=innerWidth+1 && b.bottom<=innerHeight+1 &&
                                    document.getElementById('status').textContent.includes('เชื่อมต่อแล้ว');
                            })()""") == "true"
                        }
                        screenshot(if (rotation == android.view.Surface.ROTATION_90) "landscape" else "portrait")
                        sizes.add(activity.window.decorView.width.toString() + "x" + activity.window.decorView.height)
                    }
                } finally { uiAutomation.setRotation(android.view.Surface.ROTATION_0) }
                "Same Activity and VNC session; canvas stays within viewport: " + sizes.joinToString()
            }
            record("stop_button_closes_desktop") {
                runOnMainSync { check(findView("หยุด Linux Desktop")?.performClick() == true) }
                try {
                    await(12_000) { !LinuxDesktopService.running && !portOpen(5901) && !portOpen(6080) }
                } catch (failure: Throwable) {
                    error("running=" + LinuxDesktopService.running + ",vnc=" + portOpen(5901) +
                        ",web=" + portOpen(6080) + "; " + failure.message)
                }
                "Stop button closed Xvnc and the noVNC listener"
            }
            record("restart_desktop") {
                startDesktop()
                check(portOpen(5901) && portOpen(6080))
                targetContext.stopService(Intent(targetContext, LinuxDesktopService::class.java))
                await(12_000) { !LinuxDesktopService.running && !portOpen(5901) && !portOpen(6080) }
                "Desktop started and stopped again without a stale socket"
            }
            finishReport()
        } catch (failure: Throwable) {
            record("runner") { throw failure }
            finishReport()
        }
    }

    private fun createDebugViewer(): WebView {
        val web = MainActivity::class.java.getDeclaredMethod("createWebView")
            .apply { isAccessible = true }.invoke(activity) as WebView
        web.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                browserConsole.add(message.message() + " @ " + message.sourceId() + ":" + message.lineNumber())
                return true
            }
        }
        return web
    }

    private fun viewerProbe() {
        record("viewer_module_probe") {
            val server = DesktopWebServer(targetContext)
            server.start()
            try {
                runOnMainSync {
                    val web = createDebugViewer()
                    activity.setContentView(web)
                    web.loadUrl("http://127.0.0.1:6080/")
                }
                await(20_000) {
                    js("Boolean(location.href.startsWith('http://127.0.0.1:6080/') && " +
                        "document.readyState==='complete' && document.getElementById('connect') && !document.getElementById('connect').disabled)") == "true"
                }
                js("window.__probeImport='pending'; import('http://127.0.0.1:6080/novnc/core/rfb.js').then(" +
                    "m=>window.__probeImport=typeof m.default).catch(e=>window.__probeImport=String(e.stack||e)); 'started'")
                await(15_000) { js("window.__probeImport") != "\"pending\"" }
                val result = js("({importResult:window.__probeImport, " +
                    "resources:performance.getEntriesByType('resource').map(r=>({name:r.name,size:r.decodedBodySize,status:r.responseStatus}))})")
                check(js("window.__probeImport") == "\"function\"") { result + "\n" + browserConsole.joinToString("\n") }
                js("document.getElementById('password').value='731924'; document.getElementById('connect').click(); 'clicked'")
                Thread.sleep(700)
                val status = js("document.getElementById('status').textContent")
                check(!status.contains("ยังไม่เชื่อมต่อ")) { result + "\nstatus=" + status + "\n" + browserConsole.joinToString("\n") }
                result + "\nstatus=" + status
            } finally { server.close() }
        }
    }

    private fun androidChecks() {
        record("task_checkpoint_on_android_storage") {
            val directory = File(outputDir, "checkpoint-test")
            val saved = TaskCheckpointStore(directory, "desktop")
            val task = AgentTask("android-smoke", "สร้างไฟล์ภาษาไทย").apply {
                startPlanning(); planned(); beginExecution(); noteExecution("written; needs verification")
            }
            check(saved.save(task))
            val restored = TaskCheckpointStore(directory, "desktop").restore()!!
            check(restored.request == task.request && restored.rounds == 1 && restored.recovered)
            check(!restored.beginExecution())
            "Atomic disk checkpoint retains Thai text and evidence; no approval is restored"
        }
        record("ai_stop_button_cancels_real_process") {
            val ready = CountDownLatch(1)
            val stopped = java.util.concurrent.atomic.AtomicBoolean()
            val child = arrayOfNulls<Process>(1)
            val worker = Thread {
                try {
                    kotlinx.coroutines.runBlocking {
                        AgentWork.run { ticket ->
                            val process = ProcessBuilder("/system/bin/sleep", "30").start()
                            child[0] = process
                            ticket.attach(process)
                            ready.countDown()
                            process.waitFor()
                            ticket.check()
                        }
                    }
                } catch (_: AgentWork.Stopped) { stopped.set(true) }
            }.apply { start() }
            check(ready.await(4, TimeUnit.SECONDS))
            runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("refreshChip")
                    .apply { isAccessible = true }.invoke(activity)
                check(findView("หยุดงานและบริการ AI")?.performClick() == true)
            }
            worker.join(5000)
            check(!worker.isAlive && stopped.get() && child[0]?.isAlive == false && !AgentWork.active)
            check(TaskCheckpointStore(File(outputDir, "checkpoint-test"), "desktop").restore() == null)
            "Visible AI stop button killed the attached Android process and cancelled the stored task"
        }
        record("desktop_http_assets_and_boundaries") {
            val server = DesktopWebServer(targetContext)
            server.start()
            try {
                check(http("127.0.0.1:6080", "/").contains("200 OK"))
                val module = http("127.0.0.1:6080", "/novnc/core/rfb.js")
                check(module.contains("200 OK") && module.contains("class RFB")) { module.take(160) }
                check(http("foreign.example:6080", "/").contains("403 Forbidden"))
                check(http("127.0.0.1:6080", "/novnc/../app.js").contains("404 Not Found"))
                check(http("127.0.0.1:6080", "/missing").contains("404 Not Found"))
            } finally { server.close() }
            await(3000) { !portOpen(6080) }
            "Real localhost server served bundled noVNC module, rejected foreign Host and traversal, and closed"
        }

    }

    private fun http(host: String, path: String): String =
        Socket("127.0.0.1", 6080).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " +
                host + "\r\nConnection: close\r\n\r\n").toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().bufferedReader().readText()
        }

    private fun homePixelsReady(): Boolean {
        val shot = uiAutomation.takeScreenshot() ?: return false
        val colors = HashSet<Int>()
        for (y in 100 until shot.height step 19)
            for (x in 0 until shot.width step 19) colors.add(shot.getPixel(x, y))
        val ready = colors.size > 30
        shot.recycle()
        return ready
    }

    private fun prepareRecovery() {
        record("prepare_recovery_after_process_exit") {
            val task = AgentTask("cross-process-smoke", "ทำงานค้างภาษาไทย").apply {
                startPlanning(); planned(); beginExecution(); noteExecution("actual evidence before process exit")
            }
            check(TaskCheckpointStore(File(outputDir, "process-recovery"), "chat").save(task))
            File(outputDir, "prepare-pid").writeText(android.os.Process.myPid().toString())
            "Saved resumable task for a separate instrumentation process"
        }
    }

    private fun finishReport() {
        if (::outputDir.isInitialized && options.getString("phase") != "recover") prepareRecovery()
        val report = JSONObject().put("package", targetContext.packageName)
            .put("sdk", Build.VERSION.SDK_INT).put("checks", checks)
        if (::outputDir.isInitialized) File(outputDir, "report.json").writeText(report.toString(2))
        val failed = (0 until checks.length()).any { !checks.getJSONObject(it).optBoolean("pass") }
        finish(if (failed) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            Bundle().apply { putString("stream", "\n" + report.toString(2) + "\n") })
    }

    private fun startDesktop() {
        val old = LinuxDesktopService.status(targetContext)
        targetContext.startForegroundService(Intent(targetContext,
            LinuxDesktopService::class.java).setAction(LinuxDesktopService.ACTION_START)
            .putExtra(LinuxDesktopService.EXTRA_PASSWORD, "731924"))
        await(15_000) { LinuxDesktopService.running ||
            LinuxDesktopService.status(targetContext) != old }
        await(60_000) {
            if (!LinuxDesktopService.running &&
                LinuxDesktopService.error(targetContext).isNotEmpty())
                error(LinuxDesktopService.error(targetContext))
            LinuxDesktopService.running
        }
    }

    private fun await(timeout: Long, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "Condition timed out after " + timeout + "ms" }
            Thread.sleep(250)
        }
    }

    private fun findView(description: String): View? {
        fun search(view: View): View? {
            if (view.contentDescription?.toString() == description && view.isShown) return view
            if (view is ViewGroup) for (index in 0 until view.childCount)
                search(view.getChildAt(index))?.let { return it }
            return null
        }
        return search(activity.window.decorView)
    }

    private fun js(script: String): String {
        val result = arrayOf("")
        val ready = CountDownLatch(1)
        runOnMainSync {
            fun web(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (index in 0 until view.childCount)
                    web(view.getChildAt(index))?.let { return it }
                return null
            }
            val view = web(activity.window.decorView)
            check(view != null) { "Desktop WebView missing" }
            view.evaluateJavascript(script) { value -> result[0] = value; ready.countDown() }
        }
        check(ready.await(8, TimeUnit.SECONDS)) { "WebView did not answer" }
        return result[0]
    }

    private fun canvasProbe() = """(() => {
        const c=document.querySelector('#screen canvas');
        if(!c||c.width<10||c.height<10)return {nonUniform:false};
        const d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;
        const colors=new Set();
        for(let y=0;y<c.height;y+=7)for(let x=0;x<c.width;x+=7) {
            const k=(y*c.width+x)*4;
            colors.add(d[k]+','+d[k+1]+','+d[k+2]);
        }
        return {width:c.width,height:c.height,colors:colors.size,nonUniform:colors.size>12};
    })()"""

    private fun portOpen(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
        true
    } catch (_: Exception) { false }

    private fun screenshot(name: String) {
        val bitmap = uiAutomation.takeScreenshot() ?: error("Android screenshot unavailable")
        File(outputDir, name + ".png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
