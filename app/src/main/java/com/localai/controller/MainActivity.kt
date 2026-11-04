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
    private var tabBeforeDesktop = "control"
    private var lastBackAtMs = 0L
    private var platformBackCallback: android.window.OnBackInvokedCallback? = null
    private var settingsCategory = "start"
    private var advancedSettingsVisible = false
    private var desktopAdvancedVisible = false
    private var desktopRefreshToken = 0
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
        actionButton(name) {
            settingsCategory = id
            showTab("control")
        }.apply {
            textSize = if (compactScreen()) 12f else 13f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(49)
            setPadding(dp(9), dp(6), dp(8), dp(6))
            maxLines = 2
            setTextColor(if (settingsCategory == id) Color.WHITE else color)
            background = panel(
                mixColor(cardDeep, color, if (settingsCategory == id) 0.28f else 0.12f),
                13, mixColor(line, color, if (settingsCategory == id) 0.83f else 0.35f)
            )
            contentDescription = name + ". " + description + if (settingsCategory == id) ". เลือกแล้ว" else ""
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
        val columns = if (resources.configuration.screenWidthDp < 320 ||
            resources.configuration.fontScale >= 1.7f) 1 else 2
        for (start in items.indices step columns) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            for (index in start until minOf(start + columns, items.size)) {
                val it = items[index]
                val element = categorySelector(it[0], it[1], it[2], colors[index])
                line.addView(element, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > start) leftMargin = dp(6)
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
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(39)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = panel(mixColor(cardDeep, color, 0.065f), 11,
                mixColor(line, color, 0.18f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                settingsCategory = target
                showTab("control")
            }
        }
        item.addView(label(if (done) "✓" else "○", 17f, true,
            if (done) linuxGreen else color).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(dp(27), -2))
        item.addView(label(title, 12.5f, false, text),
            LinearLayout.LayoutParams(0, -2, 1f))
        item.addView(label(if (done) "พร้อม" else "ตั้งค่า ›", 11f, true,
            if (done) linuxGreen else color))
        item.contentDescription = (if (done) "เสร็จแล้ว: " else "ยังไม่เสร็จ: ") + title +
            ". " + detail + ". แตะเพื่อเปิดเมนู"
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
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
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainColumn) { view, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(mainColumn)
        androidx.core.view.ViewCompat.requestApplyInsets(mainColumn)
        buildShell()
        installGestureBackHandler()
        showTab(if (store.hasPassword() && AiForegroundService.running) "workspace" else "control")
        AutoUpdateScheduler.sync(this)
        if (AutoUpdateScheduler.enabled(this) && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("localai_signed_updates", MODE_PRIVATE)
            if (!prefs.getBoolean("notification_asked", false)) {
                prefs.edit().putBoolean("notification_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 92)
            }
        }
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
            setPadding(dp(10), dp(2), dp(10), dp(7))
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
        desktopRefreshToken += 1
        if (target == "desktop" && currentTab != "desktop") {
            tabBeforeDesktop = currentTab
        }
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
        val modelFile = imports.modelFile()
        val alpineReady = LinuxRuntime(this).installed()
        val desktopReady = LinuxDesktopService.installed(this)
        val completed = listOf(store.hasPassword(), alpineReady,
            modelFile != null, desktopReady).count { it }
        if (settingsCategory == "start") {
            cardBlock(content, 5) {
                setPadding(dp(11), dp(10), dp(11), dp(10))
                background = panel(mixColor(card, accent, 0.10f), 15,
                    mixColor(line, accent, 0.38f))
                val quick = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                addView(quick)
                val status = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("ตั้งค่าและเริ่มใช้งาน", 15f, true))
                    addView(label("พร้อมแล้ว $completed/4 ขั้นตอน", 11f, false, sub))
                }
                quick.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
                val action = actionButton(
                    if (AiForegroundService.running) "เปิดแชท ›" else "เริ่ม AI ›",
                    true) {
                    if (AiForegroundService.running) showTab("workspace")
                    else if (modelFile == null) {
                        settingsCategory = "ai"; showTab("control")
                    } else if (!store.hasPassword()) {
                        settingsCategory = "privacy"; showTab("control")
                    } else startAi()
                }.apply {
                    minHeight = dp(42)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    textSize = 12f
                }
                quick.addView(action, LinearLayout.LayoutParams(-2, -2))
            }
            cardBlock(content, 7) {
                setPadding(dp(10), dp(11), dp(10), dp(11))
                addView(label("เช็กลิสต์", 14f, true))
                spacer(this, 7)
                val progressBar = android.widget.ProgressBar(this@MainActivity, null,
                    android.R.attr.progressBarStyleHorizontal).apply {
                    max = 4
                    progress = completed
                    progressTintList = android.content.res.ColorStateList.valueOf(linuxGreen)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(line)
                    contentDescription = "พร้อม $completed จาก 4 ขั้นตอน"
                }
                addView(progressBar, LinearLayout.LayoutParams(-1, dp(5)))
                spacer(this, 5)
                addSetupCheck(this, "รหัสผ่าน", "ตั้งรหัสผ่านเข้า AI",
                    store.hasPassword(), privacyBlue, "privacy")
                addSetupCheck(this, "Linux", "ติดตั้ง Alpine Linux",
                    alpineReady, linuxGreen, "linux")
                addSetupCheck(this, "สมอง AI", "ดาวน์โหลดโมเดล AI",
                    modelFile != null, aiPurple, "ai")
                addSetupCheck(this, "หน้าจอ Linux", "ติดตั้ง Linux Desktop",
                    desktopReady, desktopOrange, "desktop")
            }
            cardBlock(content, 7) {
                setPadding(dp(10), dp(11), dp(10), dp(11))
                addView(label("เมนูตั้งค่า", 14f, true))
                addCategoryMenu(this)
            }
        } else {
            val section = when (settingsCategory) {
                "linux" -> "🐧 Linux" to linuxGreen
                "ai" -> "✦ AI" to aiPurple
                "desktop" -> "▣ หน้าจอ Linux" to desktopOrange
                "privacy" -> "🔐 ความปลอดภัย" to privacyBlue
                else -> "⬇ ข้อมูลและอัปเดต" to dataPink
            }
            cardBlock(content, 5) {
                setPadding(dp(11), dp(8), dp(11), dp(8))
                val sectionRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                addView(sectionRow)
                sectionRow.addView(label(section.first, 16f, true, section.second),
                    LinearLayout.LayoutParams(0, -2, 1f))
                val back = actionButton("‹ เมนู", false) {
                    settingsCategory = "start"
                    showTab("control")
                }.apply {
                    minHeight = dp(39)
                    textSize = 12f
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                }
                sectionRow.addView(back, LinearLayout.LayoutParams(-2, -2))
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
                setPadding(dp(12), dp(12), dp(12), dp(12))
                val desktopReady = LinuxDesktopService.installed(this@MainActivity)
                val busy = LinuxDesktopService.installing
                val runningNow = LinuxDesktopService.running
                val statusLine = label("", 14f, true, desktopOrange)
                val progressLine = label("", 12f, false, sub)
                val errorLink = actionButton("!  ดูสาเหตุที่ติดตั้งไม่ได้") {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("รายละเอียดการติดตั้ง Linux")
                        .setMessage(LinuxDesktopService.error(this@MainActivity).ifBlank {
                            "ไม่มีข้อผิดพลาดที่บันทึกไว้"
                        }.takeLast(1800))
                        .setPositiveButton("ปิด", null)
                        .show()
                }.apply {
                    minHeight = dp(38)
                    textSize = 11.5f
                    setTextColor(Color.rgb(255, 174, 180))
                }
                val updateStatusUi = {
                    val isReady = LinuxDesktopService.installed(this@MainActivity)
                    val isWorking = LinuxDesktopService.installing
                    val isActive = LinuxDesktopService.running
                    val error = LinuxDesktopService.error(this@MainActivity)
                    statusLine.text = when {
                        isActive -> "✓  หน้าจอ Linux เปิดอยู่"
                        isWorking -> "◉  กำลังติดตั้ง…"
                        isReady -> "✓  ติดตั้งแล้ว พร้อมเปิด"
                        error.isNotBlank() -> "✕  ติดตั้งไม่สำเร็จ"
                        else -> "○  ยังไม่ได้ติดตั้ง"
                    }
                    statusLine.setTextColor(when {
                        isActive || isReady -> linuxGreen
                        error.isNotBlank() && !isWorking -> Color.rgb(255, 174, 180)
                        else -> desktopOrange
                    })
                    progressLine.text = when {
                        isWorking -> LinuxDesktopService.status(this@MainActivity).take(125)
                        isActive -> "แตะดูหน้าจอเพื่อเปิดโปรแกรม Linux"
                        error.isNotBlank() && !isReady -> error.take(120)
                        isReady -> "กรอกรหัสด้านล่างเพื่อเปิดหน้าจอ"
                        else -> "ดาวน์โหลดครั้งแรกต้องใช้อินเทอร์เน็ต"
                    }
                    errorLink.visibility =
                        if (error.isNotBlank() && !isWorking) View.VISIBLE else View.GONE
                }
                addView(statusLine)
                spacer(this, 4)
                addView(progressLine)
                addView(errorLink)
                updateStatusUi()
                spacer(this, 11)

                if (busy) {
                    addButton(this, "■  ยกเลิกการติดตั้ง") {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("ยกเลิกการติดตั้ง?")
                            .setMessage("การดาวน์โหลดจะหยุด คุณสามารถลองติดตั้งใหม่ภายหลังได้")
                            .setNegativeButton("ทำต่อ", null)
                            .setPositiveButton("หยุด") { _, _ ->
                                stopService(Intent(this@MainActivity, LinuxDesktopService::class.java))
                                notifyUser("กำลังหยุดการติดตั้ง")
                            }.show()
                    }
                } else if (!desktopReady) {
                    addButton(this, if (LinuxDesktopService.error(this@MainActivity).isBlank())
                        "↓  ติดตั้งหน้าจอ Linux" else "↻  ลองติดตั้งอีกครั้ง", true) {
                        if (!LinuxRuntime(this@MainActivity).available()) {
                            settingsCategory = "linux"
                            showTab("control")
                            notifyUser("ติดตั้ง Linux พื้นฐานก่อน")
                        } else {
                            startForegroundService(Intent(this@MainActivity,
                                LinuxDesktopService::class.java)
                                .setAction(LinuxDesktopService.ACTION_INSTALL))
                            showTab("control")
                        }
                    }
                } else if (!runningNow) {
                    val password = field("รหัสเปิดหน้าจอ (อังกฤษ/ตัวเลข 8–16 ตัว)", true)
                    addView(password)
                    spacer(this, 8)
                    addButton(this, "▶  เปิดหน้าจอ Linux", true) {
                        val key = password.text.toString()
                        if (!key.matches(Regex("[A-Za-z0-9]{8,16}"))) {
                            notifyUser("กรอกรหัสภาษาอังกฤษ/ตัวเลข 8–16 ตัว")
                        } else {
                            startForegroundService(Intent(this@MainActivity,
                                LinuxDesktopService::class.java)
                                .setAction(LinuxDesktopService.ACTION_START)
                                .putExtra(LinuxDesktopService.EXTRA_PASSWORD, key))
                            password.text.clear()
                            progressLine.text = "กำลังเปิดหน้าจอ Linux…"
                        }
                    }
                } else {
                    addButton(this, "▣  ดูหน้าจอ Linux", true) { showTab("desktop") }
                    spacer(this, 7)
                    addButton(this, "■  ปิดหน้าจอ") {
                        stopService(Intent(this@MainActivity, LinuxDesktopService::class.java))
                        showTab("control")
                    }
                }

                spacer(this, 8)
                addButton(this, if (desktopAdvancedVisible)
                    "−  ซ่อนตัวเลือกเพิ่มเติม" else "＋  ตัวเลือกเพิ่มเติม") {
                    desktopAdvancedVisible = !desktopAdvancedVisible
                    showTab("control")
                }
                if (desktopAdvancedVisible) {
                    spacer(this, 10)
                    if (runningNow && LinuxDesktopService.ipAddress.isNotBlank()) {
                        val ip = LinuxDesktopService.ipAddress
                        addView(label("คอมเชื่อม VNC: $ip:5902", 12f, true, mint)
                            .apply { setTextIsSelectable(true) })
                        spacer(this, 4)
                        addView(label("เว็บ AI: http://$ip:18789", 12f, false, sub)
                            .apply { setTextIsSelectable(true) })
                        spacer(this, 8)
                        addView(label("ใช้เฉพาะ Wi-Fi หรือ VPN ส่วนตัว ไม่เปิดพอร์ตออกอินเทอร์เน็ต",
                            11f, false, sub))
                        spacer(this, 9)
                    }
                    if (!busy && !runningNow && LinuxRuntime(this@MainActivity).available()) {
                        addButton(this, "◎  ตรวจสอบ Linux") {
                            Thread({
                                val result = try { LinuxRuntime(this@MainActivity).diagnostics() }
                                    catch (e: Exception) { e.message ?: "ตรวจไม่สำเร็จ" }
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        android.app.AlertDialog.Builder(this@MainActivity)
                                            .setTitle("ผลตรวจ Linux")
                                            .setMessage(result.takeLast(2500))
                                            .setPositiveButton("ปิด", null).show()
                                    }
                                }
                            }, "linux-self-check").start()
                        }
                        spacer(this, 7)
                        addButton(this, if (LinuxDesktopService.browserInstalled(this@MainActivity))
                            "✓  Chromium ติดตั้งแล้ว" else "↓  ติดตั้งเบราว์เซอร์ Chromium (เสริม)") {
                            if (!LinuxDesktopService.browserInstalled(this@MainActivity)) {
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("ติดตั้ง Chromium?")
                                    .setMessage("ต้องใช้อินเทอร์เน็ตและพื้นที่มาก เปิดใช้งานใน Linux เท่านั้น")
                                    .setNegativeButton("ยกเลิก", null)
                                    .setPositiveButton("ติดตั้ง") { _, _ ->
                                        startForegroundService(Intent(this@MainActivity,
                                            LinuxDesktopService::class.java)
                                            .setAction(LinuxDesktopService.ACTION_INSTALL_BROWSER))
                                        showTab("control")
                                    }.show()
                            }
                        }
                    }
                }

                val uiToken = desktopRefreshToken
                var previousBusy = LinuxDesktopService.installing
                var previousRunning = LinuxDesktopService.running
                val ticker = object : Runnable {
                    override fun run() {
                        if (uiToken != desktopRefreshToken ||
                            currentTab != "control" || settingsCategory != "desktop") return
                        val nowBusy = LinuxDesktopService.installing
                        val nowRunning = LinuxDesktopService.running
                        if (previousBusy != nowBusy || previousRunning != nowRunning) {
                            showTab("control")
                            return
                        }
                        previousBusy = nowBusy
                        previousRunning = nowRunning
                        updateStatusUi()
                        ui.postDelayed(this, 1100)
                    }
                }
                ui.postDelayed(ticker, 650)
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
            if (AutoUpdateScheduler.canUpdate(this@MainActivity)) {
                val toggle = android.widget.CheckBox(this@MainActivity).apply {
                    text = "อัปเดตอัตโนมัติเมื่อใช้ Wi-Fi"
                    textSize = 13.5f
                    setTextColor(this@MainActivity.text)
                    isChecked = AutoUpdateScheduler.enabled(this@MainActivity)
                    setOnCheckedChangeListener { _, checked ->
                        AutoUpdateScheduler.setEnabled(this@MainActivity, checked)
                        notifyUser(if (checked) "เปิดอัปเดตอัตโนมัติแล้ว" else "ปิดอัปเดตอัตโนมัติแล้ว")
                        if (checked) AutoUpdateScheduler.requestNow(this@MainActivity)
                    }
                }
                addView(toggle)
                spacer(this, 6)
                addView(label("เมื่อมีรุ่นใหม่ แอปจะดาวน์โหลดให้เองบน Wi-Fi และแจ้งเมื่อพร้อมติดตั้ง",
                    12f, false, sub))
                spacer(this, 7)
                val updateStatus = label(AutoUpdateScheduler.status(this@MainActivity),
                    12f, false, sub)
                addView(updateStatus)
                spacer(this, 9)
                if (AutoUpdateScheduler.readyFile(this@MainActivity) != null) {
                    addButton(this, "✓  ติดตั้งอัปเดตที่ดาวน์โหลดแล้ว", true) {
                        try {
                            val apk = AutoUpdateScheduler.readyFile(this@MainActivity)
                                ?: error("ไม่พบไฟล์อัปเดต")
                            startActivity(VerifiedApkUpdate.installIntent(this@MainActivity, apk))
                        } catch (e: Exception) {
                            notifyUser("ติดตั้งไม่ได้: " + (e.message ?: "unknown"))
                        }
                    }
                    spacer(this, 8)
                }
                addButton(this, "↻  ตรวจอัปเดตตอนนี้") {
                    AutoUpdateScheduler.requestNow(this@MainActivity)
                    updateStatus.text = "ระบบกำลังตรวจเมื่อเชื่อมต่อ Wi-Fi"
                }
            } else {
                addView(label("รุ่น TEST ยังอัปเดตทับไม่ได้ เพราะลายเซ็นไม่ตรงกัน",
                    12f, false, sub))
                spacer(this, 5)
                addView(label("ระบบอัปเดตอัตโนมัติใช้กับรุ่น Release ที่มีคีย์ถาวรเท่านั้น",
                    11.5f, false, sub))
            }
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
        if (!AutoUpdateScheduler.enabled(this)) return
        AutoUpdateScheduler.sync(this)
        val apk = AutoUpdateScheduler.readyFile(this)
        val version = AutoUpdateScheduler.pendingVersion(this)
        if (apk != null && version.isNotBlank()) {
            val prefs = getSharedPreferences("localai_signed_updates", MODE_PRIVATE)
            if (prefs.getString("prompted_version", "") != version) {
                prefs.edit().putString("prompted_version", version).apply()
                android.app.AlertDialog.Builder(this)
                    .setTitle("อัปเดตดาวน์โหลดเสร็จแล้ว")
                    .setMessage("พร้อมติดตั้งเวอร์ชัน " + version +
                        "\nLinux และโมเดลเดิมยังอยู่เมื่ออัปเดตด้วยลายเซ็นเดียวกัน")
                    .setNegativeButton("ภายหลัง", null)
                    .setPositiveButton("ติดตั้ง") { _, _ ->
                        try {
                            startActivity(VerifiedApkUpdate.installIntent(this, apk))
                        } catch (e: Exception) {
                            notifyUser("ติดตั้งไม่ได้: " + (e.message ?: "unknown"))
                        }
                    }.show()
            }
        }
    }

    private fun installGestureBackHandler() {
        // Android 13+ predictive back may not invoke Activity.onBackPressed().
        // Register with the platform dispatcher; keep the legacy override below
        // for Android 8-12 and hardware Back buttons.
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { navigateBackWithinApp() }
            platformBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    private fun navigateBackWithinApp() {
        // The operating system handles IME dismissal and dialogs first.
        when {
            currentTab == "desktop" -> {
                showTab(if (tabBeforeDesktop == "workspace") "workspace" else "control")
            }
            currentTab == "workspace" -> {
                settingsCategory = "start"
                showTab("control")
            }
            currentTab == "control" && settingsCategory != "start" -> {
                settingsCategory = "start"
                showTab("control")
            }
            currentTab != "control" -> {
                settingsCategory = "start"
                showTab("control")
            }
            else -> {
                // On root, never accidentally destroy a running Linux/AI session.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastBackAtMs <= 2200L) {
                    lastBackAtMs = 0L
                    moveTaskToBack(true)
                } else {
                    lastBackAtMs = now
                    Toast.makeText(this, "ปัดย้อนกลับอีกครั้งเพื่อไปหน้าโฮม",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Deprecated("Support hardware and legacy Android Back")
    override fun onBackPressed() {
        navigateBackWithinApp()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            platformBackCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            platformBackCallback = null
        }
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