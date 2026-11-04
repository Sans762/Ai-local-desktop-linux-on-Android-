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
    private var settingsCategory = "start"
    private var advancedSettingsVisible = false
    private val ui = Handler(Looper.getMainLooper())

    private val bg = Color.rgb(7, 12, 26)
    private val card = Color.rgb(18, 26, 49)
    private val cardDeep = Color.rgb(13, 20, 39)
    private val line = Color.rgb(42, 53, 84)
    private val text = Color.rgb(242, 245, 255)
    private val sub = Color.rgb(155, 168, 199)
    private val accent = Color.rgb(122, 115, 255)
    private val mint = Color.rgb(91, 232, 187)

    // Each category has its own color; all state is derived from installed services,
    // never from a fake "completed" preference flag.
    private val linuxGreen = Color.rgb(112, 228, 173)
    private val aiPurple = Color.rgb(189, 158, 255)
    private val desktopOrange = Color.rgb(255, 197, 120)
    private val privacyBlue = Color.rgb(125, 192, 255)
    private val dataPink = Color.rgb(255, 158, 182)

    private fun mixColor(a: Int, b: Int, share: Float): Int =
        Color.rgb(
            (Color.red(a) * (1f - share) + Color.red(b) * share).toInt(),
            (Color.green(a) * (1f - share) + Color.green(b) * share).toInt(),
            (Color.blue(a) * (1f - share) + Color.blue(b) * share).toInt()
        )

    private fun categoryCard(parent: LinearLayout, tone: Int, body: LinearLayout.() -> Unit): LinearLayout =
        cardBlock(parent) {
            background = panel(mixColor(card, tone, 0.105f), 20, mixColor(line, tone, 0.42f))
            body()
        }

    private fun categorySelector(name: String, description: String, id: String, color: Int): TextView =
        actionButton(name + "\n" + description) {
            settingsCategory = id
            showTab("control")
        }.apply {
            textSize = if (compactScreen()) 12.5f else 13f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(76)
            setPadding(dp(11), dp(10), dp(10), dp(10))
            setTextColor(if (settingsCategory == id) Color.WHITE else color)
            background = panel(
                mixColor(cardDeep, color, if (settingsCategory == id) 0.26f else 0.12f),
                15, mixColor(line, color, if (settingsCategory == id) 0.88f else 0.40f)
            )
            contentDescription = name + ", " + description + if (settingsCategory == id) ", เลือกแล้ว" else ""
        }

    private fun addCategoryMenu(parent: LinearLayout) {
        val items = listOf(
            arrayOf("🐧  Linux พื้นฐาน", "ติดตั้งและตรวจสอบ", "linux"),
            arrayOf("✦  AI ของฉัน", "ดาวน์โหลดและพูดคุย", "ai"),
            arrayOf("▣  หน้าจอ Linux", "เปิดโปรแกรมและรีโมท", "desktop"),
            arrayOf("🔐  ความปลอดภัย", "รหัสผ่านและสิทธิ์", "privacy"),
            arrayOf("⬇  ข้อมูลและอัปเดต", "สำรองและอัปเดตแอป", "data")
        )
        val colors = listOf(linuxGreen, aiPurple, desktopOrange, privacyBlue, dataPink)
        val columns = if (resources.configuration.screenWidthDp < 360 ||
            resources.configuration.fontScale >= 1.5f) 1 else 2
        for (start in items.indices step columns) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            for (index in start until minOf(start + columns, items.size)) {
                val it = items[index]
                val element = categorySelector(it[0], it[1], it[2], colors[index])
                line.addView(element, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > start) leftMargin = dp(8)
                })
            }
            if (columns == 2 && start + 1 == items.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    leftMargin = dp(8)
                })
            }
        }
    }

    private fun addSetupCheck(parent: LinearLayout, title: String, detail: String,
                              done: Boolean, color: Int, target: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = panel(mixColor(cardDeep, color, 0.10f), 13,
                mixColor(line, color, 0.23f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                settingsCategory = target
                showTab("control")
            }
        }
        box.addView(label(if (done) "✓" else "○", 22f, true, if (done) linuxGreen else color)
            .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(34), dp(34)))
        val textGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), 0, 0, 0)
            addView(label(title, 13.5f, true))
            addView(label(if (done) "ติดตั้ง/ตั้งค่าแล้ว • แตะเพื่อตรวจสอบ" else detail,
                11.5f, false, sub))
        }
        box.addView(textGroup, LinearLayout.LayoutParams(0, -2, 1f))
        box.contentDescription = (if (done) "ทำแล้ว" else "ยังไม่ทำ") + ": " + title + ". " + detail
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

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
        controlsTab = actionButton("⚙  ตั้งค่า") { showTab("control") }
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
            background = gradient(23)
            addView(label("เริ่มต้นใช้งาน", 12f, true, Color.WHITE))
            spacer(this, 8)
            addView(label("ตั้งค่าง่าย ๆ ทีละขั้น", 23f, true))
            spacer(this, 6)
            addView(label("เลือกสิ่งที่อยากทำ แล้วแอปจะพาไปยังเมนูที่เกี่ยวข้อง ไม่ต้องใช้ Termux และไม่ควบคุม Android", 13f, false, Color.WHITE))
            spacer(this, 13)
            if (AiForegroundService.running) {
                addView(label("✓  AI กำลังทำงาน", 13f, true, Color.WHITE))
                spacer(this, 8)
                addButton(this, "✦  ไปคุยกับ AI") { showTab("workspace") }
            } else {
                addView(label("○  AI ยังไม่ได้เริ่ม", 13f, true, Color.WHITE))
                spacer(this, 8)
                addButton(this, "▶  เปิด AI") {
                    if (imports.modelFile() == null) {
                        settingsCategory = "ai"
                        showTab("control")
                        notifyUser("ต้องดาวน์โหลดโมเดล AI ก่อน")
                    } else if (!store.hasPassword()) {
                        settingsCategory = "privacy"
                        showTab("control")
                        notifyUser("กรุณาตั้งรหัสผ่านก่อน")
                    } else startAi()
                }
            }
        }
        val modelFile = imports.modelFile()
        val alpineReady = LinuxRuntime(this).installed()
        val desktopReady = LinuxDesktopService.installed(this)
        cardBlock(content, 10) {
            background = panel(mixColor(card, privacyBlue, 0.07f), 20,
                mixColor(line, privacyBlue, 0.3f))
            val completed = listOf(store.hasPassword(), alpineReady, modelFile != null,
                desktopReady).count { it }
            addView(label("เช็กลิสต์การเริ่มใช้งาน", 18f, true))
            spacer(this, 5)
            addView(label("ทำแล้ว " + completed + " จาก 4 ขั้นตอน • แตะรายการที่ยังไม่ทำเพื่อไปตั้งค่า",
                12f, false, sub))
            spacer(this, 10)
            val progressBar = android.widget.ProgressBar(this@MainActivity, null,
                android.R.attr.progressBarStyleHorizontal).apply {
                max = 4
                progress = completed
                progressTintList = android.content.res.ColorStateList.valueOf(linuxGreen)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(line)
                contentDescription = "ตั้งค่าไปแล้ว " + completed + " จาก 4 ขั้นตอน"
            }
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(7)))
            spacer(this, 9)
            addSetupCheck(this, "1. ตั้งรหัสผ่าน",
                "ปกป้องหน้าเว็บและแชท AI", store.hasPassword(), privacyBlue, "privacy")
            addSetupCheck(this, "2. ติดตั้ง Linux",
                "ติดตั้งระบบ Linux จากในแอป ไม่ต้องใช้อินเทอร์เน็ต", alpineReady, linuxGreen, "linux")
            addSetupCheck(this, "3. ดาวน์โหลดสมอง AI",
                "เลือกโมเดลที่ต้องการ แล้วใช้งานออฟไลน์", modelFile != null, aiPurple, "ai")
            addSetupCheck(this, "4. ติดตั้งหน้าจอ Linux",
                "เปิดโปรแกรม Linux และเชื่อมต่อจากคอม", desktopReady, desktopOrange, "desktop")
            spacer(this, 14)
            addView(label("เลือกหมวดการตั้งค่า", 17f, true))
            spacer(this, 4)
            addView(label("แยกสีให้จำง่าย เมนูที่เกี่ยวข้องอยู่ด้วยกัน", 11.5f, false, sub))
            addCategoryMenu(this)
        }
        if (settingsCategory == "start") {
            cardBlock(content) {
                addView(label("ควรทำอะไรต่อ?", 17f, true, privacyBlue))
                spacer(this, 8)
                val next = when {
                    !store.hasPassword() -> "ตั้งรหัสผ่านก่อน เพื่อเปิด AI และป้องกันคนอื่นเข้าใช้งาน"
                    !alpineReady -> "ติดตั้ง Linux ที่อยู่ในแอป ใช้งานได้โดยไม่ต้องใช้ Termux"
                    modelFile == null -> "ดาวน์โหลดสมอง AI หากอยากแชทหรือสั่ง AI ให้ช่วยงาน"
                    !desktopReady -> "ติดตั้งหน้าจอ Linux เพื่อเปิดโปรแกรมและรีโมทจากคอม"
                    else -> "ตั้งค่าพื้นฐานแล้ว เลือกหน้าจอ Linux เพื่อเริ่มใช้งาน"
                }
                addView(label(next, 13f, false, sub))
                spacer(this, 11)
                val target = when {
                    !store.hasPassword() -> "privacy"
                    !alpineReady -> "linux"
                    modelFile == null -> "ai"
                    else -> "desktop"
                }
                addButton(this, "ไปทำขั้นตอนถัดไป  →", true) {
                    settingsCategory = target
                    showTab("control")
                }
            }
        } else {
            cardBlock(content) {
                val section = when (settingsCategory) {
                    "linux" -> "🐧  Linux พื้นฐาน" to linuxGreen
                    "ai" -> "✦  AI ของฉัน" to aiPurple
                    "desktop" -> "▣  หน้าจอ Linux และรีโมท" to desktopOrange
                    "privacy" -> "🔐  ความปลอดภัย" to privacyBlue
                    else -> "⬇  ข้อมูลและการอัปเดต" to dataPink
                }
                addView(label(section.first, 19f, true, section.second))
                spacer(this, 5)
                addView(label("แสดงเฉพาะเมนูในหมวดนี้ เพื่อลดความสับสน",
                    12f, false, sub))
                spacer(this, 9)
                addButton(this, "←  กลับไปดูเช็กลิสต์") {
                    settingsCategory = "start"
                    showTab("control")
                }
            }
        }
        if (settingsCategory == "ai") {
        categoryCard(content, aiPurple) {
            addView(label("✦  เลือกสมอง AI", 14f, true, aiPurple))
            spacer(this, 10)
            addView(label(modelFile?.name ?: "ยังไม่ได้ติดตั้งสมอง AI", 17f, true))
            spacer(this, 4)
            addView(label(if (modelFile != null) "มีไฟล์ AI แล้ว • กดเปิด AI ที่หน้าแรกเพื่อใช้งาน" else "ดาวน์โหลด AI ด้านล่างก่อนเริ่มแชท", 12.5f, false, sub))
            spacer(this, 13)
            addButton(this, "＋  ใช้โมเดล AI ที่มีอยู่ในเครื่อง", true) { pick(31, "*/*") }
            spacer(this, 10)
            val modelProgress = label(ModelDownloadService.progress, 12f, false, sub)
            addView(modelProgress)
            spacer(this, 8)
            addButton(this, "↓  ดาวน์โหลด AI ขนาดเล็ก (ประหยัดพื้นที่ · 491 MB)") { startModelDownload("small", modelProgress) }
            spacer(this, 8)
            addButton(this, "↓  ดาวน์โหลด AI คุณภาพสูงขึ้น (1.12 GB)") { startModelDownload("balanced", modelProgress) }
            spacer(this, 7)
            addView(label("ต้องต่ออินเทอร์เน็ตตอนดาวน์โหลดครั้งแรก จากนั้นพูดคุยกับ AI แบบออฟไลน์ได้", 12f, false, sub))
        }
        }
        if (settingsCategory == "ai") {
        categoryCard(content, aiPurple) {
            addView(label("✎  ชื่อ AI และข้อมูลที่จำ", 14f, true, aiPurple))
            spacer(this, 8)
            val name = field("อยากเรียก AI ว่าอะไร? (ข้ามได้)").apply { setText(store.aiName()) }
            addView(name)
            spacer(this, 10)
            addButton(this, "บันทึกชื่อ AI") {
                store.setAiName(name.text.toString().trim().ifBlank { "Local AI" })
                notifyUser("บันทึกชื่อ AI แล้ว")
            }
            spacer(this, 12)
            addButton(this, if (advancedSettingsVisible) "−  ซ่อนตัวเลือกขั้นสูง" else "＋  ตัวเลือกขั้นสูง (สำหรับผู้มีไฟล์เดิม)") {
                advancedSettingsVisible = !advancedSettingsVisible
                showTab("control")
            }
            if (advancedSettingsVisible) {
                spacer(this, 9)
                addButton(this, "นำเข้าประวัติแชทเก่า (ไฟล์ .db)") { pick(32, "*/*") }
                spacer(this, 9)
                addButton(this, "นำเข้าชื่อ AI จากไฟล์ตั้งค่า .json") { pick(33, "application/json") }
                spacer(this, 7)
                addView(label("ใช้เมื่อต้องการนำไฟล์จากระบบเดิมเข้ามาเท่านั้น ไม่ลบไฟล์ต้นฉบับ", 12f, false, sub))
            }
        }
        }
        if (settingsCategory == "privacy") {
        categoryCard(content, privacyBlue) {
            addView(label("🔐  รหัสผ่านสำหรับใช้งาน", 14f, true, privacyBlue))
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
            addView(label("ต้องใช้รหัสผ่านเมื่อเปิดหน้า AI ผ่านเว็บ • หากเปิดให้คอมเชื่อมต่อ ให้ใช้เฉพาะ Wi-Fi ส่วนตัวหรือ VPN", 12f, false, sub))
        }
        }
        if (settingsCategory == "ai") {
        categoryCard(content, aiPurple) {
            addView(label("✧  ให้ AI ช่วยทำงาน Linux", 14f, true, aiPurple))
            spacer(this, 9)
            addView(label("พิมพ์งานที่อยากให้ AI ช่วย เช่น สร้างโฟลเดอร์ เปิดโปรแกรม หรือจัดการไฟล์ Linux", 14f, true))
            spacer(this, 8)
            addView(label("AI จะบอกแผนให้คุณดูก่อน และทำงานใน Linux เมื่อคุณกดยืนยันเท่านั้น", 12f, false, sub))
            spacer(this, 11)
            addButton(this, "✦  ไปหน้าพูดคุยและสั่ง AI", true) {
                if (!AiForegroundService.running) {
                    notifyUser("เริ่มบริการ AI ก่อนเปิด Linux Workspace")
                } else showTab("workspace")
            }
        }
        }
        if (settingsCategory == "linux") {
        categoryCard(content, linuxGreen) {
            addView(label("🐧  รู้จัก Linux ในแอป", 14f, true, linuxGreen))
            spacer(this, 8)
            addView(label("Linux คือพื้นที่ทำงานคล้ายคอมที่อยู่ในแอปนี้ ไม่ต้องติดตั้ง Termux หรือรูทเครื่อง", 13f, false, sub))
            spacer(this, 8)
            addView(label("แอปจะไม่ควบคุมมือถือ Android ของคุณ • หน้าจอ Linux ติดตั้งแยกได้ในหมวดสีส้ม", 12f, false, sub))
        }
        }
        if (settingsCategory == "linux") {
        categoryCard(content, linuxGreen) {
            addView(label("📦  ติดตั้ง Linux และใช้คำสั่ง", 14f, true, linuxGreen))
            spacer(this, 8)
            val linux = LinuxRuntime(this@MainActivity)
            val status = label(if (linux.installed()) "✓  ติดตั้ง Linux แล้ว" else "○  ยังไม่ได้ติดตั้ง Linux", 13f, true, if (linux.installed()) mint else sub)
            addView(status)
            spacer(this, 6)
            addView(label("กดปุ่มด้านล่างเพื่อติดตั้งระบบ Linux ที่มีอยู่ในแอป ไม่ต้องใช้อินเทอร์เน็ต", 12f, false, sub))
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
            addButton(this, if (advancedSettingsVisible) "−  ซ่อนเครื่องมือขั้นสูง" else "＋  เปิดเครื่องมือขั้นสูง (Terminal)") {
                advancedSettingsVisible = !advancedSettingsVisible
                showTab("control")
            }
            if (advancedSettingsVisible) {
            spacer(this, 12)
            addView(label("คำสั่ง Linux (สำหรับผู้ใช้ขั้นสูง)", 11.5f, true, sub))
            spacer(this, 8)
            val command = field("เช่น uname -a หรือ apk --version").apply {
                setText("uname -a && cat /etc/alpine-release")
            }
            addView(command)
            spacer(this, 9)
            val output = label("ผลลัพธ์คำสั่งจะแสดงที่นี่", 12f, false, mint).apply {
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
        }
        }
        if (settingsCategory == "desktop") {
        categoryCard(content, desktopOrange) {
            addView(label("▣  หน้าจอ Linux และคอมรีโมท", 14f, true, desktopOrange))
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
            addView(label("เปิดหน้าจอ Linux พร้อมหน้าต่างโปรแกรมเหมือนคอม และเชื่อมต่อจากคอมอีกเครื่องได้เมื่ออยู่ในเครือข่ายส่วนตัว", 12f, false, sub))
            spacer(this, 12)
            addButton(this, "↓  ดาวน์โหลดหน้าจอ Linux (ครั้งแรก)") {
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
            val vncPassword = field("ตั้งรหัสสำหรับคอมที่เชื่อมต่อ (ภาษาอังกฤษ/ตัวเลข)", true)
            addView(vncPassword)
            spacer(this, 9)
            addButton(this, "▶  เปิดหน้าจอ Linux และอนุญาตคอมเชื่อมต่อ", true) {
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
            addButton(this, "■  ปิดหน้าจอ Linux และหยุดการเชื่อมต่อ") {
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
            addButton(this, "▣  ดูหน้าจอ Linux บนมือถือ", true) {
                if (LinuxDesktopService.running) showTab("desktop")
                else notifyUser("เริ่ม Linux Desktop ก่อนเข้าใช้งาน")
            }
            spacer(this, 7)
            addButton(this, "↻  อัปเดตสถานะ Desktop") { showTab("control") }
            spacer(this, 10)
            addView(label("ความปลอดภัย: เปิดรีโมทเมื่อใช้จริงเท่านั้น • VNC และ HTTP ไม่เข้ารหัส ต้องใช้ Wi-Fi ส่วนตัวหรือ VPN เท่านั้น ห้ามส่งพอร์ต 5902/18789 ออกอินเทอร์เน็ต", 11.5f, false, sub))
        }
        }
        if (settingsCategory == "data") {
        categoryCard(content, dataPink) {
            addView(label("⬇  สำรองข้อมูลและอัปเดตแอป", 14f, true, dataPink))
            spacer(this, 8)
            addView(label("ดูแลข้อมูลและติดตั้งอัปเดตของแอปได้ที่นี่", 12.5f, false, sub))
            spacer(this, 9)
            addButton(this, "เปิดหน้าแชท AI ผ่านเว็บเบราว์เซอร์") {
                if (!AiForegroundService.running) notifyUser("กรุณาเริ่มบริการ AI ก่อนเปิดเว็บ")
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:18788")))
            }
            spacer(this, 9)
            addView(label("เวอร์ชันแอป: " + AppUpdateManager(this@MainActivity).currentVersion(), 13f, true))
            spacer(this, 9)
            val updateStatus = label(
                if (packageName.contains(".test"))
                    "รุ่น TEST ยังอัปเดตทับกันไม่ได้ จนกว่าจะมีลายเซ็นถาวรที่ตรงกัน"
                else AppUpdateDownloadService.progress, 12f, false, sub
            )
            addView(updateStatus)
            spacer(this, 8)
            addButton(this, "↻  ตรวจหาอัปเดตในแอป", true) {
                updateStatus.text = "กำลังตรวจหารุ่นใหม่…"
                Thread({
                    try {
                        val release = AppUpdateManager(this@MainActivity).checkLatest()
                        runOnUiThread {
                            when {
                                !release.newer -> updateStatus.text =
                                    "คุณใช้แอปรุ่นล่าสุดที่รองรับแล้ว"
                                packageName.contains(".test") -> {
                                    updateStatus.text = "พบรุ่น " + release.version +
                                        " แต่แอป TEST ไม่สามารถติดตั้งทับแอปหลักได้"
                                    android.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("รุ่น TEST ยังอัปเดตทับไม่ได้")
                                        .setMessage("รุ่นทดสอบใช้ลายเซ็น Debug ที่เปลี่ยนตาม Build " +
                                            "ระบบไม่สามารถติดตั้ง APK ที่เซ็นไม่ตรงกันทับแอปนี้ได้ " +
                                            "และจะไม่ลบไฟล์ Linux หรือโมเดลของคุณ")
                                        .setPositiveButton("เข้าใจแล้ว", null).show()
                                }
                                else -> {
                                    updateStatus.text = "มีเวอร์ชันใหม่ " + release.version +
                                        " · ขนาดประมาณ " + (release.sizeBytes / 1048576) + " MB"
                                    android.app.AlertDialog.Builder(this@MainActivity)
                                        .setTitle("พบอัปเดต " + release.version)
                                        .setMessage("แอปจะดาวน์โหลด APK จาก GitHub โดยตรง " +
                                            "ตรวจ SHA-256 และลายเซ็นก่อนให้ Android ถามติดตั้ง\n\n" +
                                            "ข้อมูล Linux/AI ของคุณจะไม่ถูกลบด้วยตัวดาวน์โหลดนี้")
                                        .setNegativeButton("ภายหลัง", null)
                                        .setPositiveButton("ดาวน์โหลดในแอป") { _, _ ->
                                            if (AppUpdateDownloadService.downloading) {
                                                notifyUser("กำลังดาวน์โหลดอัปเดตอยู่แล้ว")
                                            } else {
                                                startForegroundService(
                                                    Intent(this@MainActivity, AppUpdateDownloadService::class.java)
                                                        .setAction(AppUpdateDownloadService.ACTION_DOWNLOAD)
                                                        .putExtra(AppUpdateDownloadService.EXTRA_URL, release.downloadUrl)
                                                        .putExtra(AppUpdateDownloadService.EXTRA_SHA, release.sha256)
                                                        .putExtra(AppUpdateDownloadService.EXTRA_SIZE, release.sizeBytes)
                                                        .putExtra(AppUpdateDownloadService.EXTRA_VERSION, release.version)
                                                )
                                                updateStatus.text = "กำลังดาวน์โหลด ตรวจได้ในแถบแจ้งเตือน"
                                            }
                                        }.show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            updateStatus.text = "ตรวจไม่ได้: " + (e.message ?: "ไม่มีอินเทอร์เน็ตหรือไม่มีรุ่นอัปเดตที่เซ็นถูกต้อง")
                        }
                    }
                }, "local-ai-check-updates").start()
            }
            spacer(this, 9)
            addButton(this, "◉  ดูความคืบหน้าการอัปเดต") {
                updateStatus.text = AppUpdateDownloadService.progress +
                    if (AppUpdateDownloadService.lastError.isBlank()) ""
                    else "\n" + AppUpdateDownloadService.lastError
            }
            spacer(this, 9)
            addView(label("หลังดาวน์โหลดเสร็จ ให้แตะการแจ้งเตือนเพื่อเปิดตัวติดตั้ง Android แล้วกดยืนยัน " +
                "แอปไม่สามารถแอบติดตั้งเองได้ • ถ้าลายเซ็นไม่ตรง ระบบจะหยุดก่อนติดตั้งและไม่ลบข้อมูลเก่า",
                11.5f, false, sub))
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
        }
        spacer(content, 26)
        if (settingsCategory != "start") {
            scroll.post {
                if (content.childCount >= 3 && currentTab == "control") {
                    scroll.scrollTo(0, content.getChildAt(2).top)
                }
            }
        }
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