package com.localai.controller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Offline-first native container with an embedded, loopback-only AI workspace. */
class MainActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var imports: ImportRepository
    private lateinit var mainColumn: LinearLayout
    private lateinit var screenHost: FrameLayout
    private lateinit var workspaceTab: TextView
    private lateinit var controlsTab: TextView
    private lateinit var serviceChip: TextView
    private var webView: WebView? = null
    private var desktopWebView: WebView? = null
    private var currentTab = "control"
    private val ui = Handler(Looper.getMainLooper())

    private val bg = Color.rgb(7, 12, 26)
    private val card = Color.rgb(18, 26, 49)
    private val cardDeep = Color.rgb(13, 20, 39)
    private val line = Color.rgb(42, 53, 84)
    private val text = Color.rgb(242, 245, 255)
    private val sub = Color.rgb(155, 168, 199)
    private val accent = Color.rgb(122, 115, 255)
    private val mint = Color.rgb(91, 232, 187)

    private fun dp(n: Int): Int = (resources.displayMetrics.density * n + .5f).toInt()
    private fun compactScreen(): Boolean =
        resources.configuration.screenWidthDp < 410 || resources.configuration.fontScale >= 1.2f
    private fun panel(color: Int = card, radius: Int = 22, stroke: Int = line): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }
    private fun gradient(radius: Int = 20): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(69, 82, 217), Color.rgb(132, 86, 219), Color.rgb(115, 65, 174))
    ).apply { cornerRadius = dp(radius).toFloat() }

    private fun label(value: String, size: Float = 14f, bold: Boolean = false, color: Int = text): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun cardBlock(parent: LinearLayout, top: Int = 12, body: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = if (compactScreen()) 12 else 18
            setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
            background = panel()
            val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
            parent.addView(this, lp)
            body()
        }
    private fun spacer(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun actionButton(title: String, filled: Boolean = false, onClick: () -> Unit): TextView =
        label(title, if (compactScreen()) 13.5f else 14.5f, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(49)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = if (filled) gradient(14) else panel(cardDeep, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    private fun addButton(parent: LinearLayout, title: String, filled: Boolean = false, onClick: () -> Unit) {
        parent.addView(actionButton(title, filled, onClick), LinearLayout.LayoutParams(-1, -2))
    }
    private fun field(hintText: String, password: Boolean = false): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(sub)
        setTextColor(this@MainActivity.text)
        textSize = 14f
        isSingleLine = true
        background = panel(cardDeep, 13)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        imports = ImportRepository(this, store, ChatDatabase(this))
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        setContentView(mainColumn)
        buildShell()
        showTab(if (store.hasPassword() && AiForegroundService.running) "workspace" else "control")
    }

    private fun buildShell() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compactScreen()) 10 else 19), dp(9),
                dp(if (compactScreen()) 10 else 19), dp(7))
        }
        mainColumn.addView(header, LinearLayout.LayoutParams(-1, -2))
        val logo = label("✦", if (compactScreen()) 21f else 25f, true).apply {
            gravity = Gravity.CENTER
            background = gradient(15)
        }
        val markSize = if (compactScreen()) 40 else 49
        header.addView(logo, LinearLayout.LayoutParams(dp(markSize), dp(markSize)))
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compactScreen()) 8 else 12), 0, dp(3), 0)
            addView(label("LOCAL AI · LINUX", if (compactScreen()) 14f else 17f, true))
            addView(label(if (compactScreen()) "DESKTOP" else "DESKTOP · " +
                AppUpdateManager(this@MainActivity).currentVersion(), 10f, true, sub))
        }
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))
        serviceChip = label("", 11f, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = panel(cardDeep, 14)
        }
        header.addView(serviceChip)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(17), dp(2), dp(17), dp(10))
        }
        mainColumn.addView(nav)
        workspaceTab = actionButton("✦  แชท AI") { showTab("workspace") }
        controlsTab = actionButton("⚙  ตั้งค่าและ Linux") { showTab("control") }
        nav.addView(workspaceTab, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        nav.addView(controlsTab, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        screenHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        mainColumn.addView(screenHost, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshChip()
    }

    private fun refreshChip() {
        val running = AiForegroundService.running
        serviceChip.text = if (compactScreen()) {
            if (running) "● ON" else "● OFF"
        } else {
            if (running) "● ACTIVE" else "● OFFLINE"
        }
        serviceChip.contentDescription = if (running) "AI service working" else "AI service stopped"
        serviceChip.setTextColor(if (running) mint else sub)
    }

    private fun showTab(target: String) {
        currentTab = target
        refreshChip()
        workspaceTab.background = if (target == "workspace") gradient(14) else panel(cardDeep, 14)
        controlsTab.background = if (target == "control") gradient(14) else panel(cardDeep, 14)
        screenHost.removeAllViews()
        when (target) {
            "workspace" -> showWorkspace()
            "desktop" -> showDesktop()
            else -> showControls()
        }
    }

    private fun showWorkspace() {
        if (!store.hasPassword() || !AiForegroundService.running) {
            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(30), dp(30), dp(30), dp(30))
                addView(label("✦", 52f, true, accent).apply { gravity = Gravity.CENTER })
                spacer(this, 16)
                addView(label("เปิด Workspace ของคุณ", 23f, true).apply { gravity = Gravity.CENTER })
                spacer(this, 10)
                addView(label("ตั้งรหัสผ่านและเริ่มบริการ AI เพื่อเข้าแชทออฟไลน์ในแอป โดยไม่ต้องเปิด Chrome", 14f, false, sub).apply { gravity = Gravity.CENTER })
                spacer(this, 20)
                addButton(this, "ตั้งค่าและเริ่ม AI", true) { showTab("control") }
            }
            screenHost.addView(outer, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val web = webView ?: createWebView().also { webView = it }
        (web.parent as? ViewGroup)?.removeView(web)
        screenHost.addView(web, FrameLayout.LayoutParams(-1, -1))
        if (web.url == null || !web.url.orEmpty().startsWith("http://127.0.0.1:${AiForegroundService.PORT}/")) {
            web.loadUrl("http://127.0.0.1:${AiForegroundService.PORT}/")
        } else web.reload()
    }

    private fun showDesktop() {
        if (!LinuxDesktopService.running) {
            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(28), dp(28), dp(28))
                addView(label("▣  Linux Desktop ยังไม่เปิด", 22f, true))
                spacer(this, 11)
                addView(label("เปิด Linux Desktop ใน Control Center แล้วลองอีกครั้ง", 13f, false, sub))
                spacer(this, 16)
                addButton(this, "ไปหน้า Control Center", true) { showTab("control") }
            }
            screenHost.addView(outer, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val desktop = desktopWebView ?: createWebView().also { desktopWebView = it }
        (desktop.parent as? ViewGroup)?.removeView(desktop)
        screenHost.addView(desktop, FrameLayout.LayoutParams(-1, -1))
        if (desktop.url == null || !desktop.url.orEmpty().startsWith("http://127.0.0.1:6080/")) {
            desktop.loadUrl("http://127.0.0.1:6080/")
        }
    }

    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(bg)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            private fun allowed(url: String): Boolean {
                val uri = Uri.parse(url)
                return uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in setOf(AiForegroundService.PORT, DesktopWebServer.PORT)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                request?.url?.toString()?.let { !allowed(it) } ?: true
            @Deprecated("Compatibility for old Android WebView URL callbacks")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let { !allowed(it) } ?: true
        }
    }

    private fun showControls() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            val horizontal = if (compactScreen()) 10 else 17
            setPadding(dp(horizontal), 0, dp(horizontal), dp(22))
        }
        screenHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        cardBlock(content, 5) {
            background = gradient(24)
            addView(label("✧  YOUR PRIVATE AI", 11f, true, Color.rgb(229, 229, 255)))
            spacer(this, 8)
            addView(label("พื้นที่ทำงาน AI ของคุณ", 23f, true))
            spacer(this, 6)
            addView(label("โมเดลและแชททำงานบนมือถือ ไม่ส่งคำถามไปยัง AI API ภายนอก", 13.5f, false, Color.WHITE))
            spacer(this, 16)
            addView(label(if (AiForegroundService.running) "●  บริการ AI กำลังทำงาน" else "●  บริการ AI หยุดอยู่", 13f, true))
            if (AiForegroundService.lastError.isNotBlank()) {
                spacer(this, 6)
                addView(label(AiForegroundService.lastError, 12f, false, Color.WHITE))
            }
            spacer(this, 14)
            if (AiForegroundService.running) {
                addButton(this, "เปิดแชทในแอป  ↗") { showTab("workspace") }
                spacer(this, 9)
                addButton(this, "หยุดบริการ AI") {
                    stopService(Intent(this@MainActivity, AiForegroundService::class.java))
                    ui.postDelayed({ showTab("control") }, 450)
                }
            } else {
                addButton(this, "▶  เริ่มบริการ AI", true) { startAi() }
            }
        }
        val modelFile = imports.modelFile()
        val alpineReady = LinuxRuntime(this).installed()
        val desktopReady = LinuxDesktopService.installed(this)
        cardBlock(content, 10) {
            addView(label("เริ่มใช้งานได้ใน 4 ขั้นตอน", 18f, true))
            spacer(this, 9)
            addView(label((if (alpineReady) "✓" else "1") +
                "  ติดตั้ง Alpine Linux", 13f, alpineReady, if (alpineReady) mint else text))
            spacer(this, 7)
            addView(label((if (modelFile != null) "✓" else "2") +
                "  ดาวน์โหลดโมเดล AI (ถ้าต้องการใช้ AI)", 13f, modelFile != null,
                if (modelFile != null) mint else text))
            spacer(this, 7)
            addView(label((if (desktopReady) "✓" else "3") +
                "  ติดตั้งหน้าจอ Linux Desktop", 13f, desktopReady, if (desktopReady) mint else text))
            spacer(this, 7)
            addView(label((if (LinuxDesktopService.running) "✓" else "4") +
                "  เริ่ม Linux Desktop เพื่อเปิดจากมือถือหรือคอม", 13f,
                LinuxDesktopService.running, if (LinuxDesktopService.running) mint else text))
            spacer(this, 10)
            addView(label("เลื่อนลงเพื่อทำขั้นตอนที่ยังไม่พร้อม • ระบบจะไม่ควบคุม Android", 12f, false, sub))
            if (LinuxDesktopService.running) {
                spacer(this, 11)
                addButton(this, "▣  เปิดหน้าจอ Linux Desktop", true) { showTab("desktop") }
            }
        }
        cardBlock(content) {
            addView(label("01  /  โมเดลออฟไลน์", 12f, true, mint))
            spacer(this, 10)
            addView(label(modelFile?.name ?: "ยังไม่ได้เลือกโมเดล GGUF", 17f, true))
            spacer(this, 4)
            addView(label(if (modelFile != null) "พร้อมใช้เมื่อเปิดบริการ • โมเดลยังไม่ถูกลบจากที่เดิม" else "รองรับ GGUF ผ่านการนำเข้าโดยผู้ใช้", 12.5f, false, sub))
            spacer(this, 13)
            addButton(this, "＋  นำเข้าโมเดล .gguf", true) { pick(31, "*/*") }
            spacer(this, 10)
            val modelProgress = label(ModelDownloadService.progress, 12f, false, sub)
            addView(modelProgress)
            spacer(this, 8)
            addButton(this, "↓  ติดตั้ง Qwen2.5 0.5B · 491 MB") { startModelDownload("small", modelProgress) }
            spacer(this, 8)
            addButton(this, "↓  ติดตั้ง Qwen2.5 1.5B · 1.12 GB (คุณภาพดีกว่า)") { startModelDownload("balanced", modelProgress) }
            spacer(this, 7)
            addView(label("ดาวน์โหลดครั้งแรกเท่านั้น • มี SHA-256 ตรวจสอบไฟล์ • จากนั้น AI ทำงานออฟไลน์ • ไม่ใช้ AI API", 12f, false, sub))
        }
        cardBlock(content) {
            addView(label("02  /  ชื่อ AI และความจำ", 12f, true, mint))
            spacer(this, 8)
            val name = field("ตั้งชื่อ AI (ไม่บังคับ)").apply { setText(store.aiName()) }
            addView(name)
            spacer(this, 10)
            addButton(this, "บันทึกชื่อ AI") {
                store.setAiName(name.text.toString().trim().ifBlank { "Local AI" })
                notifyUser("บันทึกชื่อ AI แล้ว")
            }
            spacer(this, 12)
            addButton(this, "นำเข้าความจำเก่า memory.db") { pick(32, "*/*") }
            spacer(this, 9)
            addButton(this, "นำเข้าชื่อจาก safe-config.json") { pick(33, "application/json") }
            spacer(this, 7)
            addView(label("นำเข้าข้อมูลเป็นสำเนา ไม่ลบไฟล์และโมเดลต้นฉบับ • ไม่ต้องใช้ Termux", 12f, false, sub))
        }
        cardBlock(content) {
            addView(label("03  /  ความปลอดภัย", 12f, true, mint))
            spacer(this, 7)
            addView(label(if (store.hasPassword()) "● ตั้งรหัสผ่านเว็บแล้ว" else "● ต้องตั้งรหัสผ่านก่อนเปิดบริการ", 14f, true))
            spacer(this, 10)
            val pw = field("รหัสผ่านใหม่อย่างน้อย 8 ตัวอักษร", true)
            addView(pw)
            spacer(this, 9)
            addButton(this, "บันทึกรหัสผ่าน") {
                try {
                    store.setPassword(pw.text.toString())
                    pw.setText("")
                    webView?.clearHistory()
                    notifyUser("บันทึกรหัสผ่านแล้ว • เซสชันเว็บเดิมถูกยกเลิก")
                    showTab("control")
                } catch (e: Exception) { notifyUser(e.message ?: "รหัสผ่านไม่ถูกต้อง") }
            }
            spacer(this, 7)
            addView(label("เว็บรับการเชื่อมต่อเฉพาะ 127.0.0.1 ไม่เปิดพอร์ตสาธารณะ", 12f, false, sub))
        }
        cardBlock(content) {
            addView(label("04  /  Linux AI Agent", 12f, true, mint))
            spacer(this, 9)
            addView(label("ให้ AI วางแผน รันคำสั่ง ติดตั้งแพ็กเกจ และจัดการไฟล์ภายใน Alpine Linux เท่านั้น", 14f, true))
            spacer(this, 8)
            addView(label("คำสั่ง AI จะแสดงให้ตรวจสอบก่อน แล้วต้องกดอนุมัติจึงจะรันจริง • ไม่แตะหน้าจอหรือข้อมูล Android", 12f, false, sub))
            spacer(this, 11)
            addButton(this, "✦  เปิด Linux Workspace", true) {
                if (!AiForegroundService.running) {
                    notifyUser("เริ่มบริการ AI ก่อนเปิด Linux Workspace")
                } else showTab("workspace")
            }
        }
        cardBlock(content) {
            addView(label("05  /  Linux Environment", 12f, true, mint))
            spacer(this, 8)
            addView(label("Alpine Linux ARM64 + PRoot ทำงานบน kernel ของ Android โดยไม่ใช้ Termux หรือ Root", 13f, false, sub))
            spacer(this, 8)
            addView(label("ไม่มี Accessibility หรือการควบคุม Android • Linux GUI ต้องติดตั้งโปรแกรมเพิ่มในขั้นตอน 07 และเริ่มจากแอป", 12f, false, sub))
        }
        cardBlock(content) {
            addView(label("06  /  Linux built-in · Alpine ARM64", 12f, true, mint))
            spacer(this, 8)
            val linux = LinuxRuntime(this@MainActivity)
            val status = label(if (linux.installed()) "●  Linux installed" else "○  Linux not unpacked yet", 13f, true, if (linux.installed()) mint else sub)
            addView(status)
            spacer(this, 6)
            addView(label("Linux userland ผ่าน PRoot ภายใน APK ไม่ต้อง Root/Termux • ใช้ kernel Android • เก็บไฟล์ Linux ในพื้นที่แอปแยกจากไฟล์อื่น", 12f, false, sub))
            spacer(this, 9)
            addButton(this, "▣  ติดตั้ง Alpine Linux (ออฟไลน์)", true) {
                if (linux.installed()) {
                    notifyUser("Alpine Linux ติดตั้งแล้ว")
                } else if (LinuxInstallService.running) {
                    notifyUser("กำลังติดตั้ง Linux อยู่แล้ว ตรวจแจ้งเตือน")
                } else {
                    try {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 91)
                        }
                        startForegroundService(Intent(this@MainActivity, LinuxInstallService::class.java))
                        status.text = "กำลังเตรียม Linux… ดูความคืบหน้าในแจ้งเตือน"
                        val watch = object : Runnable {
                            override fun run() {
                                if (currentTab != "control") return
                                status.text = LinuxInstallService.progress
                                status.setTextColor(if (linux.installed()) mint else sub)
                                if (LinuxInstallService.running) ui.postDelayed(this, 1800)
                            }
                        }
                        ui.postDelayed(watch, 1000)
                    } catch (e: Exception) {
                        status.text = "เริ่มติดตั้งไม่ได้: " + (e.message ?: "unknown")
                    }
                }
            }
            spacer(this, 12)
            addView(label("LINUX CONSOLE", 11.5f, true, sub))
            spacer(this, 8)
            val command = field("เช่น uname -a หรือ apk --version").apply {
                setText("uname -a && cat /etc/alpine-release")
            }
            addView(command)
            spacer(this, 9)
            val output = label("Linux command output will appear here", 12f, false, mint).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = panel(cardDeep, 13)
                setTextIsSelectable(true)
            }
            addButton(this, "▶  รันคำสั่งใน Linux") {
                val value = command.text.toString()
                output.text = "กำลังรัน…"
                Thread {
                    val result = try { linux.command(value) } catch (e: Exception) { "Linux: ${e.message}" }
                    runOnUiThread { output.text = result }
                }.start()
            }
            spacer(this, 8)
            addView(output)
            spacer(this, 8)
            addView(label("โปรดระวัง: คำสั่ง Linux สามารถแก้ไฟล์ใน rootfs ของแอปได้ อย่ารันคำสั่งที่ไม่รู้จัก", 11.5f, false, sub))
        }
        cardBlock(content) {
            addView(label("07  /  Linux Desktop · Remote จากคอม", 12f, true, mint))
            spacer(this, 10)
            val desktopReady = LinuxDesktopService.installed(this@MainActivity)
            val desktopStatus = label(
                when {
                    LinuxDesktopService.running -> "●  เดสก์ท็อป Linux เปิดอยู่"
                    LinuxDesktopService.installing -> "◉  กำลังติดตั้งโปรแกรมเดสก์ท็อป"
                    desktopReady -> "●  พร้อมเริ่ม Linux Desktop"
                    else -> "○  ยังไม่มีโปรแกรมเดสก์ท็อป"
                }, 15f, true, if (LinuxDesktopService.running) mint else sub
            )
            addView(desktopStatus)
            spacer(this, 6)
            addView(label("เดสก์ท็อป Openbox พร้อมจัดการไฟล์และเปิด Terminal • คอมเชื่อมผ่าน VNC บน Wi-Fi/VPN ส่วนตัว", 12f, false, sub))
            spacer(this, 12)
            addButton(this, "↓  ติดตั้ง Linux Desktop (อินเทอร์เน็ตครั้งแรก)") {
                if (LinuxDesktopService.installing) {
                    notifyUser("มีการติดตั้งแพ็กเกจ Linux อยู่แล้ว")
                } else if (!LinuxRuntime(this@MainActivity).available()) {
                    notifyUser("ติดตั้ง Alpine Linux จากขั้นตอน 06 ก่อน")
                } else {
                    startForegroundService(Intent(this@MainActivity, LinuxDesktopService::class.java)
                        .setAction(LinuxDesktopService.ACTION_INSTALL))
                    desktopStatus.text = "กำลังติดตั้ง TigerVNC / Openbox / File Manager… ตรวจในแถบแจ้งเตือน"
                    notifyUser("กำลังติดตั้งเดสก์ท็อป Linux")
                }
            }
            spacer(this, 9)
            addView(label(if (LinuxDesktopService.browserInstalled(this@MainActivity))
                "✓ Chromium ติดตั้งแล้ว" else "Chromium สำหรับ Linux: ยังไม่ติดตั้ง (ทางเลือก)",
                12f, false, sub))
            spacer(this, 8)
            addButton(this, "↓  ติดตั้ง Chromium ใน Linux (ใช้พื้นที่มาก)") {
                if (LinuxDesktopService.installing || LinuxDesktopService.running) {
                    notifyUser("หยุด Desktop และรอการติดตั้งเดิมให้เสร็จก่อน")
                } else if (!LinuxRuntime(this@MainActivity).available()) {
                    notifyUser("ติดตั้ง Alpine Linux ก่อน")
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("ติดตั้ง Chromium ภายใน Alpine?")
                        .setMessage("ต้องใช้อินเทอร์เน็ต พื้นที่และ RAM จำนวนมาก ระบบเปิดเบราว์เซอร์ผ่าน PRoot และ AI ควบคุมเว็บยังไม่ได้ผ่านการทดสอบจริง")
                        .setNegativeButton("ยกเลิก", null)
                        .setPositiveButton("ติดตั้ง") { _, _ ->
                            startForegroundService(Intent(this@MainActivity, LinuxDesktopService::class.java)
                                .setAction(LinuxDesktopService.ACTION_INSTALL_BROWSER))
                            notifyUser("เริ่มติดตั้ง Chromium ดูสถานะในแถบแจ้งเตือน")
                        }.show()
                }
            }
            spacer(this, 9)
            addButton(this, "◎  ตรวจสอบ Linux และ PRoot") {
                if (!LinuxRuntime(this@MainActivity).available()) {
                    notifyUser("Alpine ยังไม่พร้อม")
                } else {
                    notifyUser("กำลังตรวจ Linux…")
                    Thread({
                        val report = try { LinuxRuntime(this@MainActivity).diagnostics() }
                            catch (e: Exception) { "LINUX_CHECK_FAILED: " + (e.message ?: "unknown") }
                        runOnUiThread {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("ผลตรวจ Linux / PRoot")
                                .setMessage(report.take(3000))
                                .setPositiveButton("ปิด", null).show()
                        }
                    }, "linux-diagnostics").start()
                }
            }
            spacer(this, 12)
            addView(label("รหัสผ่าน VNC (8 ตัวแรกที่ใช้ยืนยันตัวตน)", 12f, false, sub))
            spacer(this, 7)
            val vncPassword = field("ตั้งรหัสผ่าน 8–16 ตัวอักษรภาษาอังกฤษ/ตัวเลข", true)
            addView(vncPassword)
            spacer(this, 9)
            addButton(this, "▶  เริ่ม Linux Desktop + เปิดให้คอมเชื่อม", true) {
                if (!LinuxDesktopService.installed(this@MainActivity)) {
                    notifyUser("ติดตั้งโปรแกรม Linux Desktop ก่อน")
                } else if (!store.hasPassword()) {
                    notifyUser("ตั้งรหัสผ่านของ AI Web ในขั้นตอน 03 ก่อน")
                } else {
                    val password = vncPassword.text.toString()
                    if (!password.matches(Regex("[A-Za-z0-9]{8,16}"))) {
                        notifyUser("รหัส VNC ต้องมี 8–16 ตัวอักษรอังกฤษหรือตัวเลข")
                    } else {
                        startForegroundService(Intent(this@MainActivity, LinuxDesktopService::class.java)
                            .setAction(LinuxDesktopService.ACTION_START)
                            .putExtra(LinuxDesktopService.EXTRA_PASSWORD, password))
                        vncPassword.text.clear()
                        desktopStatus.text = "กำลังเปิด Linux Desktop…"
                        ui.postDelayed({ if (currentTab == "control") showTab("control") }, 2600)
                    }
                }
            }
            spacer(this, 9)
            addButton(this, "■  หยุด Linux Desktop / รีโมท") {
                stopService(Intent(this@MainActivity, LinuxDesktopService::class.java))
                ui.postDelayed({ if (currentTab == "control") showTab("control") }, 600)
            }
            spacer(this, 10)
            val ip = LinuxDesktopService.ipAddress
            if (LinuxDesktopService.running && ip.isNotBlank()) {
                addView(label("คอมต่อ VNC: $ip:5902", 15f, true, mint).apply { setTextIsSelectable(true) })
                spacer(this, 7)
                addView(label("เว็บ AI บนคอม: http://$ip:18789", 13f, true, mint).apply { setTextIsSelectable(true) })
                spacer(this, 5)
                addView(label("เว็บใช้รหัสผ่าน AI ที่ตั้งในขั้นตอน 03", 11f, false, sub))
            } else {
                addView(label(LinuxDesktopService.message.take(250), 11f, false, sub))
            }
            if (LinuxDesktopService.lastError.isNotBlank()) {
                spacer(this, 6)
                addView(label(LinuxDesktopService.lastError.take(340), 11f, false, Color.rgb(255, 177, 183)))
            }
            spacer(this, 11)
            addButton(this, "▣  เปิด Linux Desktop บนมือถือ", true) {
                if (LinuxDesktopService.running) showTab("desktop")
                else notifyUser("เริ่ม Linux Desktop ก่อนเข้าใช้งาน")
            }
            spacer(this, 7)
            addButton(this, "↻  อัปเดตสถานะ Desktop") { showTab("control") }
            spacer(this, 10)
            addView(label("ความปลอดภัย: เปิดรีโมทเมื่อใช้จริงเท่านั้น • VNC และ HTTP ไม่เข้ารหัส ต้องใช้ Wi-Fi ส่วนตัวหรือ VPN เท่านั้น ห้ามส่งพอร์ต 5902/18789 ออกอินเทอร์เน็ต", 11.5f, false, sub))
        }
        cardBlock(content) {
            addView(label("08  /  ระบบและข้อจำกัด", 12f, true, mint))
            spacer(this, 8)
            addView(label("Android Native • Loopback Server 18788 • SQLite Memory • GGUF CPU", 12.5f, false, sub))
            spacer(this, 9)
            addButton(this, "เปิดเว็บ Dashboard ในเบราว์เซอร์") {
                if (!AiForegroundService.running) notifyUser("กรุณาเริ่มบริการ AI ก่อนเปิดเว็บ")
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:18788")))
            }
            spacer(this, 9)
            addView(label("เวอร์ชันแอป: " + AppUpdateManager(this@MainActivity).currentVersion(), 13f, true))
            spacer(this, 9)
            val updateStatus = label("ตรวจสอบรุ่นใหม่ได้จาก GitHub Releases", 12f, false, sub)
            addView(updateStatus)
            spacer(this, 8)
            addButton(this, "↻  ตรวจหาอัปเดตแอป", true) {
                updateStatus.text = "กำลังตรวจสอบอัปเดตผ่าน GitHub…"
                Thread({
                    try {
                        val release = AppUpdateManager(this@MainActivity).checkLatest()
                        runOnUiThread {
                            if (release.newer) {
                                updateStatus.text = "พบเวอร์ชันใหม่: " + release.version
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("มีอัปเดต Local AI Linux Desktop")
                                    .setMessage("รุ่นใหม่ " + release.version +
                                        "\\nไฟล์ APK จะเปิดจาก GitHub โดยตรง Android จะถามยืนยันก่อนติดตั้ง" +
                                        "\\n\\nหมายเหตุ: APK รุ่น Debug เดิมอาจมีลายเซ็นต่างกันและติดตั้งทับไม่ได้")
                                    .setNegativeButton("ภายหลัง", null)
                                    .setPositiveButton("ดาวน์โหลดอัปเดต") { _, _ ->
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
                                    }.show()
                            } else {
                                updateStatus.text = "คุณใช้รุ่นล่าสุดอยู่แล้ว (" + release.version + ")"
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { updateStatus.text = "ตรวจอัปเดตไม่ได้: " + (e.message ?: "ไม่ทราบสาเหตุ") }
                    }
                }, "native-update-check").start()
            }
            spacer(this, 9)
            addView(label("อัปเดตผ่านระบบติดตั้งของ Android เท่านั้น • ไม่สามารถติดตั้งเงียบ ๆ ได้ • ถ้าระบบแจ้งลายเซ็นต่างกัน อย่าเพิ่งถอนแอปเดิม เพราะข้อมูล Linux จะหาย", 11.5f, false, sub))
            spacer(this, 11)
            addView(label("สำรอง Linux + AI ก่อนเปลี่ยน APK", 14f, true, mint))
            spacer(this, 7)
            addView(label("บันทึก Alpine, โมเดล GGUF และฐานข้อมูลแชทเป็นไฟล์ .tar.gz ไปยังโฟลเดอร์ที่เลือก • ไฟล์สำรองไม่เข้ารหัสและอาจมีข้อมูลส่วนตัว • รุ่นนี้ยังไม่มีปุ่มกู้คืนอัตโนมัติ", 12f, false, sub))
            spacer(this, 8)
            addView(label(BackupService.progress.take(160), 12f, false, sub))
            spacer(this, 8)
            addButton(this, "⬇  สำรองข้อมูล Linux + AI") {
                if (LinuxDesktopService.running || LinuxDesktopService.installing ||
                    AiForegroundService.running || ModelDownloadService.running || BackupService.running) {
                    notifyUser("หยุด Desktop, AI และการดาวน์โหลดก่อนสำรองข้อมูล")
                } else {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/gzip"
                        putExtra(Intent.EXTRA_TITLE, "LocalAI-Linux-Backup-" +
                            java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                                .format(java.util.Date()) + ".tar.gz")
                    }, 44)
                }
            }

            spacer(this, 10)
            addView(label("Linux AI Agent ขออนุมัติก่อนรันคำสั่ง • เดสก์ท็อปเปิดจากแอปและให้คอมเชื่อมผ่าน VNC ได้ • ยังไม่ได้ทดสอบบน Android 17 • PRoot ไม่ใช่ VM", 12f, false, sub))
        }
        spacer(content, 26)
    }

    private fun startModelDownload(id: String, progressLabel: TextView) {
        if (ModelDownloadService.running) {
            notifyUser("กำลังดาวน์โหลดอยู่แล้ว")
            return
        }
        try {
            startForegroundService(Intent(this, ModelDownloadService::class.java).apply {
                putExtra(ModelDownloadService.EXTRA_MODEL, id)
            })
            progressLabel.text = "Starting download…"
            val updater = object : Runnable {
                override fun run() {
                    if (currentTab != "control") return
                    progressLabel.text = ModelDownloadService.progress
                    if (ModelDownloadService.running) ui.postDelayed(this, 1500)
                }
            }
            ui.postDelayed(updater, 1000)
        } catch (e: Exception) { notifyUser("Download failed: ${e.message}") }
    }

    private fun startAi() {
        if (!store.hasPassword()) { notifyUser("ตั้งรหัสผ่านเว็บก่อนเริ่มบริการ"); return }
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
            startForegroundService(Intent(this, AiForegroundService::class.java))
            notifyUser("กำลังเริ่มบริการ AI…")
            ui.postDelayed({
                refreshChip()
                if (AiForegroundService.running) showTab("workspace") else {
                    notifyUser(AiForegroundService.lastError.ifBlank { "บริการยังเริ่มไม่สำเร็จ" })
                    showTab("control")
                }
            }, 900)
        } catch (e: Exception) { notifyUser("เริ่มบริการไม่สำเร็จ: ${e.message}") }
    }

    private fun pick(request: Int, mime: String) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }, request)
    }

    @Deprecated("Uses Android Storage Access Framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 44) {
            try {
                startForegroundService(Intent(this, BackupService::class.java)
                    .putExtra(BackupService.EXTRA_DESTINATION, uri))
                notifyUser("กำลังสำรองข้อมูล ตรวจสถานะในแถบแจ้งเตือน")
            } catch (e: Exception) {
                notifyUser("สำรองข้อมูลไม่สำเร็จ: " + (e.message ?: "unknown"))
            }
            return
        }
        notifyUser("กำลังนำเข้าไฟล์…")
        Thread({
            try {
                val result = when (requestCode) {
                    31 -> imports.importModel(uri)
                    32 -> imports.importLegacyDatabase(uri)
                    33 -> {
                        val source = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("อ่านไฟล์ config ไม่ได้")
                        val name = JSONObject(source).optString("ai_name", "Local AI").take(60)
                        store.setAiName(name)
                        JSONObject().put("ai_name", name)
                    }
                    else -> error("ประเภทไฟล์นำเข้าไม่ถูกต้อง")
                }
                runOnUiThread { notifyUser("นำเข้าสำเร็จ: $result"); if (currentTab == "control") showTab("control") }
            } catch (e: Exception) {
                runOnUiThread { notifyUser("นำเข้าไม่สำเร็จ: ${e.message}") }
            }
        }, "native-import").start()
    }

    override fun onResume() {
        super.onResume()
        if (::serviceChip.isInitialized) refreshChip()
    }

    @Deprecated("Back behavior for native tab navigation")
    override fun onBackPressed() {
        if (currentTab == "workspace" || currentTab == "desktop") showTab("control") else super.onBackPressed()
    }

    override fun onDestroy() {
        val web = webView
        webView = null
        if (web != null) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
        desktopWebView?.let { viewer ->
            (viewer.parent as? ViewGroup)?.removeView(viewer)
            viewer.stopLoading()
            viewer.destroy()
        }
        desktopWebView = null
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notifyUser(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}