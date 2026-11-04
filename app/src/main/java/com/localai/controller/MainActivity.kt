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
import android.webkit.WebResourceError
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
    private lateinit var homeTab: TextView
    private lateinit var workspaceTab: TextView
    private lateinit var desktopTab: TextView
    private lateinit var toolsTab: TextView
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
    private var setupChecklistExpanded = false
    private var aiOptionsExpanded = false
    private var aiNameExpanded = false
    private var dataAdvancedExpanded = false
    private var desktopRefreshToken = 0
    private var terminalCommandThread: Thread? = null
    private var terminalBusy = false
    private var terminalOutputText = "เทอร์มินัลพร้อมใช้งาน"
    private var terminalOutputView: TextView? = null
    private var terminalRunButton: TextView? = null
    private var terminalStopButton: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val desktopLinuxAgent by lazy {
        LinuxAgent(this, imports, ChatDatabase(this))
    }
    private var desktopAiBusy = false

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
            arrayOf("🐧  Linux", "ระบบพื้นฐาน", "linux"),
            arrayOf("✦  สมอง AI", "เลือกและคุยกับ AI", "ai"),
            arrayOf("▣  หน้าจอคอม", "หน้าจอ Linux", "desktop"),
            arrayOf("🔐  รหัสผ่าน", "ป้องกันบัญชีของคุณ", "privacy"),
            arrayOf("⬇  ข้อมูล/อัปเดต", "สำรองและอัปเดต", "data")
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
        showTab("home")
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

    /** Compact native shell: real status only, one main action, fixed bottom navigation. */
    private fun buildShell() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        mainColumn.addView(header, LinearLayout.LayoutParams(-1, -2))
        val logo = label("✦", 19f, true).apply {
            gravity = Gravity.CENTER
            background = gradient(12)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(38), dp(38)))
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), 0, 0, 0)
            addView(label("Local AI Linux", 16f, true))
            addView(label("AI ในมือถือ • Linux", 10f, false, sub))
        }
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))
        serviceChip = label("", 10f, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = panel(cardDeep, 12)
        }
        header.addView(serviceChip)

        screenHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        mainColumn.addView(screenHost, LinearLayout.LayoutParams(-1, 0, 1f))

        // Navigation stays reachable even on small Android 17 screens.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = panel(cardDeep, 19)
        }
        mainColumn.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(7)
            rightMargin = dp(7)
            bottomMargin = dp(4)
        })
        fun item(title: String, action: () -> Unit): TextView =
            actionButton(title, onClick = action).apply {
                textSize = if (compactScreen()) 10.5f else 11.5f
                gravity = Gravity.CENTER
                minHeight = dp(51)
                maxLines = 2
                setPadding(dp(2), dp(6), dp(2), dp(6))
            }
        homeTab = item("⌂\nหน้าแรก") { showTab("home") }
        workspaceTab = item("✦\nแชท") { showTab("workspace") }
        desktopTab = item("▣\nLinux") {
            if (LinuxDesktopService.running) showTab("desktop")
            else openSetting("desktop")
        }
        toolsTab = item("⌨\nเครื่องมือ") {
            if (LinuxRuntime(this).available()) showTab("terminal")
            else openSetting("linux")
        }
        controlsTab = item("⚙\nตั้งค่า") { openSetting("start") }
        for (button in listOf(homeTab, workspaceTab, desktopTab, toolsTab, controlsTab)) {
            bottom.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(1)
                rightMargin = dp(1)
            })
        }
        refreshChip()
    }

    private fun refreshChip() {
        val active = AiForegroundService.running
        serviceChip.text = if (active) "● AI เปิดอยู่" else "● AI ปิดอยู่"
        serviceChip.contentDescription = if (active) "AI service active" else "AI service stopped"
        serviceChip.setTextColor(if (active) mint else sub)
    }

    private fun openSetting(category: String) {
        settingsCategory = category
        showTab("control")
    }

    private fun showTab(target: String) {
        desktopRefreshToken += 1
        if (target == "desktop" && currentTab != "desktop") {
            tabBeforeDesktop = currentTab
        }
        currentTab = target
        refreshChip()
        homeTab.background = if (target == "home") gradient(14) else panel(cardDeep, 14)
        workspaceTab.background = if (target == "workspace") gradient(14) else panel(cardDeep, 14)
        desktopTab.background = if (target == "desktop") gradient(14) else panel(cardDeep, 14)
        toolsTab.background = if (target == "terminal") gradient(14) else panel(cardDeep, 14)
        controlsTab.background = if (target == "control") gradient(14) else panel(cardDeep, 14)
        screenHost.removeAllViews()
        terminalOutputView = null
        terminalRunButton = null
        terminalStopButton = null
        when (target) {
            "home" -> showHome()
            "workspace" -> showWorkspace()
            "desktop" -> showDesktop()
            "terminal" -> showTerminal()
            else -> showControls()
        }
    }

    /** Real-device home screen: never label an uninstalled AI or Linux as ready. */
    private fun showHome() {
        val modelReady = imports.modelFile() != null
        val linuxReady = LinuxRuntime(this).installed()
        val desktopReady = LinuxDesktopService.installed(this)
        val aiRunning = AiForegroundService.running
        val desktopRunning = LinuxDesktopService.running

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        screenHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.addView(label("สวัสดี 👋", 23f, true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })
        content.addView(label("เลือกสิ่งที่อยากทำได้เลย", 12f, false, sub))
        cardBlock(content, 12) {
            setPadding(dp(15), dp(14), dp(15), dp(15))
            background = panel(mixColor(card, accent, 0.19f), 19,
                mixColor(line, accent, 0.45f))
            val primaryTitle = when {
                aiRunning -> "AI กำลังทำงาน"
                !modelReady -> "เริ่มใช้งาน AI"
                else -> "พร้อมเปิด AI"
            }
            addView(label(primaryTitle, 19f, true))
            spacer(this, 6)
            addView(label("โมเดล: " + if (modelReady) "ติดตั้งแล้ว" else "ยังไม่ได้ติดตั้ง",
                12f, false, if (modelReady) linuxGreen else sub))
            addView(label("บริการ AI: " + if (aiRunning) "เปิดอยู่" else "ปิดอยู่",
                12f, false, if (aiRunning) linuxGreen else sub))
            spacer(this, 12)
            val mainAction = when {
                aiRunning -> "✦  เปิดแชท AI"
                !modelReady -> "↓  ติดตั้งโมเดล AI"
                !store.hasPassword() -> "🔐  ตั้งรหัสผ่าน"
                else -> "▶  เริ่ม AI"
            }
            addButton(this, mainAction, true) {
                when {
                    aiRunning -> showTab("workspace")
                    !modelReady -> openSetting("ai")
                    !store.hasPassword() -> openSetting("privacy")
                    else -> startAi()
                }
            }
        }
        cardBlock(content, 9) {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(row)
            row.addView(label("▣  Linux Desktop", 16f, true),
                LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(when {
                desktopRunning -> "● เปิดอยู่"
                desktopReady -> "● พร้อมเปิด"
                linuxReady -> "○ ยังไม่มี Desktop"
                else -> "○ ยังไม่ได้ติดตั้ง"
            }, 11f, true, if (desktopRunning) linuxGreen else sub))
            spacer(this, 8)
            addButton(this, when {
                desktopRunning -> "เปิดหน้าจอ Linux"
                !linuxReady -> "ติดตั้ง Linux"
                desktopReady -> "เริ่มหน้าจอ Linux"
                else -> "ติดตั้ง Linux Desktop"
            }) {
                if (desktopRunning) showTab("desktop")
                else openSetting(if (linuxReady) "desktop" else "linux")
            }
        }
        cardBlock(content, 9) {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(label("เครื่องมือ", 15f, true))
            spacer(this, 7)
            addButton(this, "⌨  เทอร์มินัล Linux") {
                if (linuxReady) showTab("terminal") else openSetting("linux")
            }
            spacer(this, 6)
            addButton(this, "⚙  โมเดล · สำรองข้อมูล · อัปเดต") { openSetting("start") }
        }
        content.addView(label(
            "Linux ในแอปนี้อยู่บนมือถือ ไม่ใช่หน้าจอ Windows PC",
            10.5f, false, sub
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
        // Keep the Linux desktop as the main content; commands use the native
        // on-device LinuxAgent, never an unprotected JavaScript bridge.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), 0, dp(5), dp(5))
        }
        screenHost.addView(root, FrameLayout.LayoutParams(-1, -1))
        val desktop = desktopWebView ?: createWebView().also { desktopWebView = it }
        (desktop.parent as? ViewGroup)?.removeView(desktop)
        root.addView(desktop, LinearLayout.LayoutParams(-1, 0, 1f))

        val aiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(aiRow, LinearLayout.LayoutParams(-1, -2))
        val taskInput = field("สั่ง AI ให้ทำอะไรใน Linux…").apply {
            textSize = 14f
            setSingleLine(true)
            minHeight = dp(45)
            setPadding(dp(11), dp(10), dp(11), dp(10))
        }
        aiRow.addView(taskInput, LinearLayout.LayoutParams(0, -2, 1f))
        lateinit var send: TextView
        send = actionButton(if (desktopAiBusy) "กำลังคิด…" else "✦ สั่ง AI", true) {
            val task = taskInput.text.toString().trim()
            if (task.isBlank()) {
                notifyUser("พิมพ์งานที่ต้องการให้ AI ทำ")
            } else if (desktopAiBusy) {
                notifyUser("AI กำลังทำงานก่อนหน้าอยู่")
            } else if (imports.modelFile() == null) {
                notifyUser("ติดตั้งโมเดล AI ก่อน")
                openSetting("ai")
            } else {
                val keyboardManager = getSystemService(INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                keyboardManager.hideSoftInputFromWindow(taskInput.windowToken, 0)
                taskInput.clearFocus()
                requestDesktopAiPlan(task, send)
            }
        }.apply {
            textSize = 12f
            minHeight = dp(45)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        aiRow.addView(send, LinearLayout.LayoutParams(-2, -2).apply {
            leftMargin = dp(6)
        })
        if (desktop.url == null || !desktop.url.orEmpty().startsWith("http://127.0.0.1:6080/")) {
            desktop.loadUrl("http://127.0.0.1:6080/")
        } else desktop.reload()
    }

    private fun requestDesktopAiPlan(task: String, send: TextView) {
        if (desktopAiBusy) return
        desktopAiBusy = true
        send.isEnabled = false
        send.text = "กำลังคิด…"
        Thread({
            val proposal = try {
                kotlinx.coroutines.runBlocking { desktopLinuxAgent.propose(task) }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error",
                    e.message?.take(250) ?: "AI ไม่สามารถวางแผนได้")
            }
            ui.post {
                desktopAiBusy = false
                send.isEnabled = true
                send.text = "✦ สั่ง AI"
                if (isFinishing || isDestroyed) return@post
                if (!proposal.optBoolean("ok", false)) {
                    val reason = when (proposal.optString("error")) {
                        "INSTALL_QWEN_GGUF_FIRST" -> "ยังไม่ได้ติดตั้งโมเดล GGUF"
                        "LOW_RAM_FOR_MODEL" -> "หน่วยความจำเหลือไม่พอสำหรับโมเดล"
                        else -> proposal.optString("error", "ไม่สามารถวางแผนได้")
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("AI ยังทำงานไม่ได้")
                        .setMessage(reason.take(350))
                        .setPositiveButton("ปิด", null).show()
                    return@post
                }
                val commands = proposal.optJSONArray("commands") ?: return@post
                val planId = proposal.optString("planId")
                val preview = buildString {
                    append(proposal.optString("summary").take(250))
                    for (i in 0 until commands.length()) {
                        append("\n\n").append(i + 1).append(". ")
                        append(commands.optString(i).take(700))
                    }
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("AI เสนอคำสั่ง Linux")
                    .setMessage(preview)
                    .setNegativeButton("ยกเลิก", null)
                    .setPositiveButton("อนุมัติและทำ") { _, _ ->
                        executeDesktopAiPlan(planId, send)
                    }.show()
            }
        }, "linux-ai-plan-native").apply { isDaemon = true; start() }
    }

    private fun executeDesktopAiPlan(planId: String, send: TextView) {
        if (desktopAiBusy) return
        desktopAiBusy = true
        send.isEnabled = false
        send.text = "กำลังทำ…"
        Thread({
            val result = try {
                kotlinx.coroutines.runBlocking { desktopLinuxAgent.execute(planId) }
            } catch (e: Exception) {
                JSONObject().put("ok", false)
                    .put("error", e.message?.take(300) ?: "รันคำสั่งไม่ได้")
            }
            ui.post {
                desktopAiBusy = false
                send.isEnabled = true
                send.text = "✦ สั่ง AI"
                if (isFinishing || isDestroyed) return@post
                val successful = result.optBoolean("ok", false) &&
                    result.optBoolean("completed", false)
                val last = result.optJSONArray("results")
                val output = if (last != null && last.length() > 0)
                    last.optJSONObject(last.length() - 1)?.optString("output", "").orEmpty()
                else result.optString("error")
                android.app.AlertDialog.Builder(this)
                    .setTitle(if (successful) "AI ทำงานเสร็จ" else "งานยังไม่สำเร็จ")
                    .setMessage((result.optString("summary") +
                        if (output.isBlank()) "" else "\n\n" + output.takeLast(950)).trim())
                    .setPositiveButton("ปิด", null)
                    .show()
            }
        }, "linux-ai-execute-native").apply { isDaemon = true; start() }
    }

    /** Keep a failed localhost WebView from becoming a blank browser error screen. */
    private fun showWebFailure(view: WebView?) {
        val failedTab = when {
            currentTab == "workspace" && view === webView -> "workspace"
            currentTab == "desktop" && view === desktopWebView -> "desktop"
            else -> return // stale error from a background tab
        }
        view?.stopLoading()
        screenHost.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(25), dp(22), dp(25))
            addView(label("เชื่อมต่อบริการในเครื่องไม่ได้", 19f, true).apply {
                gravity = Gravity.CENTER
            })
            spacer(this, 9)
            addView(label(
                if (failedTab == "desktop")
                    "หน้าจอ Linux อาจหยุดทำงาน ลองเปิดใหม่ได้โดยไม่ต้องลบแอป"
                else "AI อาจยังเริ่มบริการไม่เสร็จ ข้อความและโมเดลเดิมยังอยู่",
                13f, false, sub
            ).apply { gravity = Gravity.CENTER })
            spacer(this, 18)
            addButton(this, "↻  ลองเปิดอีกครั้ง", true) { showTab(failedTab) }
            spacer(this, 8)
            addButton(this, "ไปตั้งค่าและตรวจสอบ") {
                settingsCategory = if (failedTab == "desktop") "desktop" else "start"
                showTab("control")
            }
        }
        screenHost.addView(panel, FrameLayout.LayoutParams(-1, -1))
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

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    ui.post { showWebFailure(view) }
                }
            }
        }
    }

    /**
     * Embedded Alpine terminal; provides the useful shell workflow of Termux
     * without embedding the separately signed Termux Android application.
     * All commands stay inside this application's PRoot and are NEVER remote APIs.
     */
    private fun showTerminal() {
        val linux = LinuxRuntime(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(11))
        }
        screenHost.addView(root, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("⌨  เทอร์มินัล Linux", 17f, true, linuxGreen))
            addView(label("พิมพ์คำสั่งได้ในแอป ไม่ต้องเปิด Termux", 11f, false, sub))
        }
        header.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("‹ กลับ") {
            settingsCategory = "linux"
            showTab("control")
        }.apply {
            textSize = 12f
            minHeight = dp(40)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })
        spacer(root, 10)
        if (!linux.available()) {
            cardBlock(root, 0) {
                addView(label("ยังไม่ได้ติดตั้ง Linux", 16f, true, desktopOrange))
                spacer(this, 6)
                addView(label("ติดตั้ง Linux พื้นฐานก่อนใช้เทอร์มินัล",
                    12f, false, sub))
                spacer(this, 10)
                addButton(this, "ไปติดตั้ง Linux", true) {
                    settingsCategory = "linux"
                    showTab("control")
                }
            }
            return
        }
        val terminalScroll = ScrollView(this).apply {
            isFillViewport = true
            background = panel(cardDeep, 15)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            clipToPadding = false
        }
        val output = label(terminalOutputText, 12f, false, linuxGreen).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(Color.rgb(205, 235, 222))
            textSize = 12f
        }
        terminalOutputView = output
        terminalScroll.addView(output, FrameLayout.LayoutParams(-1, -2))
        root.addView(terminalScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        terminalScroll.post { terminalScroll.fullScroll(View.FOCUS_DOWN) }
        spacer(root, 9)
        val command = field("พิมพ์คำสั่ง Linux เช่น ls -la")
        command.setText("")
        command.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        root.addView(command, LinearLayout.LayoutParams(-1, -2))
        spacer(root, 8)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        val run = actionButton("▶  รันคำสั่ง", true) {
            val value = command.text.toString()
            if (value.isBlank()) {
                notifyUser("พิมพ์คำสั่งก่อน")
            } else {
                startTerminalCommand(value)
                command.text.clear()
            }
        }
        terminalRunButton = run
        buttons.addView(run, LinearLayout.LayoutParams(0, -2, 1f))
        val stop = actionButton("■  หยุด") {
            terminalCommandThread?.interrupt()
            notifyUser("กำลังหยุดคำสั่ง")
        }.apply {
            visibility = if (terminalBusy) View.VISIBLE else View.GONE
            setTextColor(desktopOrange)
        }
        terminalStopButton = stop
        buttons.addView(stop, LinearLayout.LayoutParams(0, -2, 0.60f).apply {
            leftMargin = dp(7)
        })
        run.text = if (terminalBusy) "◉  กำลังทำงาน…" else "▶  รันคำสั่ง"
        spacer(root, 9)
        val shortcuts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(shortcuts)
        val selfTest = actionButton("◎  ตรวจ Linux") {
            startTerminalCommand("uname -m && cat /etc/alpine-release && df -h /root")
        }.apply {
            minHeight = dp(42)
            textSize = 12f
        }
        shortcuts.addView(selfTest, LinearLayout.LayoutParams(0, -2, 1f))
        val share = actionButton("↗  ส่งรายงานให้ ChatGPT") {
            shareLinuxSupportReport()
        }.apply {
            minHeight = dp(42)
            textSize = 12f
        }
        shortcuts.addView(share, LinearLayout.LayoutParams(0, -2, 1.2f).apply {
            leftMargin = dp(7)
        })
        spacer(root, 5)
        root.addView(label("ใช้ Alpine Linux: ติดตั้งโปรแกรมด้วย apk add แทน pkg " +
            "• คำสั่งทำงานใน Linux ของแอป ไม่ใช่ระบบ Android", 10.5f, false, sub))
    }

    private fun startTerminalCommand(command: String) {
        if (terminalBusy) { notifyUser("กำลังทำคำสั่งก่อนหน้าอยู่"); return }
        if (command.length !in 1..1000) { notifyUser("คำสั่งยาวเกินไป"); return }
        if (!LinuxRuntime(this).available()) { notifyUser("ติดตั้ง Linux ก่อน"); return }
        terminalBusy = true
        val prefix = terminalOutputText.takeLast(12_000) +
            "\n$ " + command + "\n"
        terminalOutputText = prefix + "กำลังทำงาน…"
        terminalOutputView?.text = terminalOutputText
        terminalRunButton?.text = "◉  กำลังทำงาน…"
        terminalStopButton?.visibility = View.VISIBLE
        val thread = Thread({
            val result = try {
                LinuxRuntime(this).command(command, timeoutSeconds = 90)
            } catch (e: Exception) {
                "คำสั่งหยุดหรือผิดพลาด: " + (e.message ?: "ไม่ทราบสาเหตุ")
            }
            ui.post {
                terminalBusy = false
                terminalCommandThread = null
                terminalOutputText = (prefix + result).takeLast(18_000)
                if (!isFinishing && !isDestroyed && currentTab == "terminal") {
                    terminalOutputView?.text = terminalOutputText
                    terminalRunButton?.text = "▶  รันคำสั่ง"
                    terminalStopButton?.visibility = View.GONE
                }
            }
        }, "local-embedded-terminal").apply { isDaemon = true }
        terminalCommandThread = thread
        thread.start()
    }

    private fun shareLinuxSupportReport() {
        notifyUser("กำลังตรวจระบบเพื่อสร้างรายงาน…")
        Thread({
            val report = try {
                LinuxSupportReport.snapshot(this, withLinuxCheck = true)
            } catch (e: Exception) {
                "ไม่สามารถสร้างรายงาน: " + (e.message ?: "unknown")
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                // User decides which application receives these diagnostics.
                // No shell logs, chat history, passwords or background upload.
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Local AI Linux • Support report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                try {
                    startActivity(Intent.createChooser(share,
                        "เลือกแอปเพื่อส่งรายงาน (เช่น ChatGPT)"))
                } catch (e: Exception) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Local AI support report", report))
                    notifyUser("คัดลอกรายงานแล้ว สามารถนำไปวางในแชทได้")
                }
            }
        }, "linux-readonly-support-report").start()
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
            cardBlock(content, 6) {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(label("ตั้งค่า", 19f, true))
                spacer(this, 3)
                addView(label("เลือกเฉพาะส่วนที่ต้องการ", 12f, false, sub))
                spacer(this, 8)
                addCategoryMenu(this)
            }
            cardBlock(content, 8) {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(label("ความพร้อม: $completed/4", 12f, true, sub))
                spacer(this, 6)
                addButton(this, if (setupChecklistExpanded)
                    "ซ่อนรายการตั้งค่า  ⌃" else "ดูรายการตั้งค่า  ⌄") {
                    setupChecklistExpanded = !setupChecklistExpanded
                    showTab("control")
                }
                if (setupChecklistExpanded) {
                    spacer(this, 7)
                    addSetupCheck(this, "รหัสผ่าน", "เข้า AI", store.hasPassword(),
                        privacyBlue, "privacy")
                    addSetupCheck(this, "Linux", "Alpine Linux", alpineReady,
                        linuxGreen, "linux")
                    addSetupCheck(this, "โมเดล", "AI ออฟไลน์", modelFile != null,
                        aiPurple, "ai")
                    addSetupCheck(this, "หน้าจอ Linux", "Linux Desktop", desktopReady,
                        desktopOrange, "desktop")
                }
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
                setPadding(dp(12), dp(13), dp(12), dp(13))
                val modelReady = modelFile != null
                addView(label(if (modelReady) "✓  AI พร้อมใช้งาน" else
                    "ยังไม่มีสมอง AI", 16f, true,
                    if (modelReady) linuxGreen else aiPurple))
                spacer(this, 5)
                addView(label(if (modelReady) "คุยกับ AI ได้แบบออฟไลน์" else
                    "ดาวน์โหลดครั้งแรก แล้วใช้งานได้โดยไม่ต้องต่อเน็ต",
                    12f, false, sub))
                val modelProgress = label(ModelDownloadService.progress, 11.5f, false, sub)
                if (ModelDownloadService.running) {
                    spacer(this, 8)
                    addView(modelProgress)
                }
                spacer(this, 12)
                if (!modelReady) {
                    addButton(this, "↓  ดาวน์โหลด AI (แนะนำ · 1.12 GB)", true) {
                        startModelDownload("balanced", modelProgress)
                        if (modelProgress.parent == null) addView(modelProgress)
                    }
                } else {
                    addButton(this, if (AiForegroundService.running)
                        "✦  ไปหน้าแชท" else "▶  เปิด AI และแชท", true) {
                        if (AiForegroundService.running) showTab("workspace")
                        else if (!store.hasPassword()) {
                            settingsCategory = "privacy"
                            showTab("control")
                            notifyUser("ตั้งรหัสผ่านก่อนใช้งาน AI")
                        } else startAi()
                    }
                }
                spacer(this, 10)
                addButton(this, if (aiOptionsExpanded)
                    "−  ซ่อนตัวเลือกโมเดล" else "＋  ตัวเลือกโมเดลอื่น") {
                    aiOptionsExpanded = !aiOptionsExpanded
                    showTab("control")
                }
                if (aiOptionsExpanded) {
                    spacer(this, 9)
                    addButton(this, "↓  AI ขนาดเล็ก (491 MB)") {
                        startModelDownload("small", modelProgress)
                        if (modelProgress.parent == null) addView(modelProgress)
                    }
                    spacer(this, 7)
                    addButton(this, "＋  เลือกไฟล์ AI ที่มีอยู่") { pick(31, "*/*") }
                    if (modelReady) {
                        spacer(this, 5)
                        addView(label("ที่ใช้ตอนนี้: " + modelFile!!.name.take(55),
                            11f, false, sub))
                    }
                }
            }
            categoryCard(content, aiPurple) {
                setPadding(dp(12), dp(11), dp(12), dp(11))
                addButton(this, if (aiNameExpanded)
                    "−  ซ่อนการตั้งชื่อ AI" else "✎  ตั้งชื่อ AI (ไม่บังคับ)") {
                    aiNameExpanded = !aiNameExpanded
                    showTab("control")
                }
                if (aiNameExpanded) {
                    spacer(this, 9)
                    val name = field("ชื่อที่อยากเรียก AI").apply { setText(store.aiName()) }
                    addView(name)
                    spacer(this, 8)
                    addButton(this, "บันทึกชื่อ") {
                        store.setAiName(name.text.toString().trim().ifBlank { "Local AI" })
                        notifyUser("บันทึกชื่อเรียบร้อย")
                        aiNameExpanded = false
                        showTab("control")
                    }
                    spacer(this, 9)
                    addButton(this, if (advancedSettingsVisible)
                        "−  ซ่อนการนำเข้าข้อมูล" else "＋  นำเข้าข้อมูลจากแอปเก่า") {
                        advancedSettingsVisible = !advancedSettingsVisible
                        showTab("control")
                    }
                    if (advancedSettingsVisible) {
                        spacer(this, 7)
                        addButton(this, "นำเข้าประวัติแชท .db") { pick(32, "*/*") }
                        spacer(this, 7)
                        addButton(this, "นำเข้าชื่อ AI .json") { pick(33, "application/json") }
                    }
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
        if (settingsCategory == "linux") {
            categoryCard(content, linuxGreen) {
                setPadding(dp(12), dp(13), dp(12), dp(13))
                val linux = LinuxRuntime(this@MainActivity)
                val ready = linux.installed()
                val status = label(if (ready) "✓  Linux พร้อมแล้ว" else
                    if (LinuxInstallService.running) "◉  กำลังติดตั้ง Linux…" else
                    "○  ยังไม่ได้ติดตั้ง Linux", 16f, true,
                    if (ready) linuxGreen else sub)
                addView(status)
                spacer(this, 5)
                addView(label("พื้นที่เปิดโปรแกรมและจัดการไฟล์ภายในแอป",
                    12f, false, sub))
                spacer(this, 12)
                if (ready) {
                    addButton(this, "⌨  เปิดเทอร์มินัล", true) {
                        showTab("terminal")
                    }
                    spacer(this, 8)
                    addButton(this, "▣  ไปหน้าจอ Linux") {
                        settingsCategory = "desktop"
                        showTab("control")
                    }
                } else {
                    addButton(this, if (LinuxInstallService.running)
                        "◉  กำลังติดตั้ง…" else "↓  ติดตั้ง Linux (ไม่ใช้เน็ต)", true) {
                        if (LinuxInstallService.running) {
                            notifyUser("กำลังติดตั้ง Linux อยู่แล้ว")
                        } else {
                            try {
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                    PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 91)
                                }
                                startForegroundService(Intent(this@MainActivity,
                                    LinuxInstallService::class.java))
                                status.text = "◉  กำลังติดตั้ง Linux…"
                            } catch (e: Exception) {
                                status.text = "✕  ติดตั้งไม่ได้"
                                notifyUser(e.message ?: "ติดตั้ง Linux ไม่สำเร็จ")
                            }
                        }
                    }
                }
                val activeCategory = "linux"
                var wasInstalling = LinuxInstallService.running
                val watch = object : Runnable {
                    override fun run() {
                        if (currentTab != "control" || settingsCategory != activeCategory) return
                        status.text = when {
                            linux.installed() -> "✓  Linux พร้อมแล้ว"
                            LinuxInstallService.running -> "◉  " +
                                LinuxInstallService.progress.take(54)
                            else -> LinuxInstallService.progress.take(65)
                                .ifBlank { "○  ยังไม่ได้ติดตั้ง Linux" }
                        }
                        status.setTextColor(if (linux.installed()) linuxGreen else sub)
                        if (wasInstalling && !LinuxInstallService.running) {
                            showTab("control")
                            return
                        }
                        wasInstalling = LinuxInstallService.running
                        ui.postDelayed(this, 1600)
                    }
                }
                ui.postDelayed(watch, 800)
                spacer(this, 10)
                addButton(this, "♡  ส่งรายงานตรวจระบบให้ ChatGPT") {
                    shareLinuxSupportReport()
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
                val errorLink = actionButton("!  ดูสาเหตุที่เปิด Linux ไม่ได้") {
                    val technical = LinuxDesktopService.error(this@MainActivity).ifBlank {
                        "ไม่มีข้อผิดพลาดที่บันทึกไว้"
                    }.takeLast(1800)
                    val summary = when {
                        "Xvnc" in technical && ("Permission denied" in technical ||
                            ".X1-lock" in technical) ->
                            "Linux Desktop เปิดไม่ขึ้นเพราะสิทธิ์ไฟล์ชั่วคราว\n" +
                                "แอปรุ่นใหม่จะลองแก้อัตโนมัติ"
                        else -> "เปิดหน้าจอ Linux ไม่สำเร็จ\nแตะข้อมูลเทคนิคเพื่อดูรายละเอียด"
                    }
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("หน้าจอ Linux เปิดไม่ได้")
                        .setMessage(summary)
                        .setNeutralButton("ข้อมูลเทคนิค") { _, _ ->
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("รายละเอียด Xvnc")
                                .setMessage(technical)
                                .setPositiveButton("ปิด", null)
                                .show()
                        }
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
                        error.isNotBlank() -> "✕  Linux Desktop หยุดหรือเปิดไม่ได้"
                        isReady -> "✓  ติดตั้งแล้ว พร้อมเปิด"
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
                        error.isNotBlank() -> error.take(120)
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
                    val password = field("รหัส VNC 6–8 ตัว (เช่น PIN 6 หลัก)", true)
                    addView(password)
                    spacer(this, 8)
                    addButton(this, "▶  เปิดหน้าจอ Linux", true) {
                        val key = password.text.toString()
                        if (!key.matches(Regex("[A-Za-z0-9]{6,8}"))) {
                            notifyUser("รหัส VNC ต้องมี 6–8 ตัว")
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
            addView(label("เวอร์ชัน " + AppUpdateManager(this@MainActivity).currentVersion(),
                14f, true, dataPink))
            spacer(this, 10)
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
                addView(label("รุ่น TEST · ยังอัปเดตทับรุ่นที่เซ็นต่างกันไม่ได้",
                    13f, true, desktopOrange))
                spacer(this, 6)
                addView(label("ที่มีสองไอคอนเพราะ Android มองเป็นคนละแอป " +
                    "ให้สำรองข้อมูลจากตัวที่ใช้งานอยู่ก่อน ไม่ต้องลบแอปเดิม",
                    12f, false, sub))
                spacer(this, 9)
                addButton(this, "⬇  สำรองข้อมูลก่อนย้าย", true) {
                    startBackupPicker()
                }
                spacer(this, 7)
                addView(label("เมื่อมี APK ที่เซ็นถาวร: ติดตั้งรุ่นใหม่ → " +
                    "นำเข้าไฟล์สำรอง → ตรวจข้อมูล → ค่อยถอนแอป TEST เก่า " +
                    "จากนั้นอัปเดตรุ่นถัดไปทับตัวเดิมได้",
                    11.5f, false, sub))
            }
            spacer(this, 12)
            addButton(this, "⇄  ย้ายข้อมูลจากแอปเก่า", true) {
                if (LinuxDesktopService.running || LinuxDesktopService.installing ||
                    LinuxInstallService.running || AiForegroundService.running ||
                    ModelDownloadService.running || BackupService.running ||
                    RestoreService.running) {
                    notifyUser("ปิด AI และหน้าจอ Linux ก่อนย้ายข้อมูล")
                } else {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("นำข้อมูลเดิมกลับมา")
                        .setMessage("เลือกไฟล์สำรอง .tar.gz จากแอปเก่า " +
                            "ระบบจะกู้คืน Linux และโมเดลที่ยังไม่มี " +
                            "รวมประวัติแชทโดยไม่เขียนทับข้อมูลที่มีอยู่\n\n" +
                            "ไม่ต้องถอนการติดตั้งแอปเดิม")
                        .setNegativeButton("ยกเลิก", null)
                        .setPositiveButton("เลือกไฟล์สำรอง") { _, _ ->
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }, 45)
                        }.show()
                }
            }
            spacer(this, 5)
            addView(label(RestoreService.progress.take(90), 11f, false, sub))
            spacer(this, 12)
            addButton(this, if (dataAdvancedExpanded)
                "−  ซ่อนตัวเลือกเพิ่มเติม" else "＋  สำรองข้อมูลและตัวเลือกอื่น") {
                dataAdvancedExpanded = !dataAdvancedExpanded
                showTab("control")
            }
            if (dataAdvancedExpanded) {
                spacer(this, 12)
                addView(label("สำรองข้อมูลของคุณ", 14f, true, linuxGreen))
                spacer(this, 6)
                addView(label("รวมไฟล์ Linux, AI และประวัติแชทเป็นไฟล์เดียว " +
                    "ไฟล์สำรองยังไม่เข้ารหัส เก็บไว้ในที่ปลอดภัย " +
                    "และนำกลับได้ด้วยปุ่มย้ายข้อมูลจากแอปเก่า",
                    11.5f, false, sub))
                spacer(this, 8)
                addView(label(BackupService.progress.take(120), 11f, false, sub))
                spacer(this, 8)
                addButton(this, "⬇  สำรองข้อมูล") { startBackupPicker() }
                spacer(this, 10)
                addButton(this, "เปิดแชทผ่านเบราว์เซอร์") {
                    if (!AiForegroundService.running) {
                        notifyUser("เปิด AI ก่อน")
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://127.0.0.1:18788")))
                    }
                }
            }
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

    /** Wait for the socket to bind instead of assuming it is ready after 900 ms. */
    private fun awaitAiReady(remainingChecks: Int) {
        if (isFinishing || isDestroyed) return
        refreshChip()
        when {
            AiForegroundService.running -> showTab("workspace")
            AiForegroundService.lastError.isNotBlank() -> {
                notifyUser(AiForegroundService.lastError)
                settingsCategory = "start"
                showTab("control")
            }
            remainingChecks > 0 -> ui.postDelayed({
                awaitAiReady(remainingChecks - 1)
            }, 400L)
            else -> {
                notifyUser("AI ยังไม่พร้อม กรุณาลองเปิดใหม่")
                settingsCategory = "start"
                showTab("control")
            }
        }
    }

    private fun startAi() {
        if (!store.hasPassword()) { notifyUser("ตั้งรหัสผ่านเว็บก่อนเริ่มบริการ"); return }
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
            AiForegroundService.lastError = ""
            startForegroundService(Intent(this, AiForegroundService::class.java))
            notifyUser("กำลังเริ่มบริการ AI…")
            ui.postDelayed({ awaitAiReady(20) }, 300L)
        } catch (e: Exception) { notifyUser("เริ่มบริการไม่สำเร็จ: ${e.message}") }
    }

    /** One shared entry point; do not back up a live or half-installed Linux rootfs. */
    private fun startBackupPicker() {
        if (LinuxDesktopService.running || LinuxDesktopService.installing ||
            LinuxInstallService.running || AiForegroundService.running ||
            ModelDownloadService.running || BackupService.running ||
            RestoreService.running) {
            notifyUser("หยุด AI, Linux และการดาวน์โหลดให้เสร็จก่อนสำรองข้อมูล")
            return
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gzip"
            putExtra(Intent.EXTRA_TITLE, "LocalAI-Linux-Backup-" +
                java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date()) + ".tar.gz")
        }, 44)
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
        if (requestCode == 45) {
            try {
                startForegroundService(Intent(this, RestoreService::class.java)
                    .putExtra(RestoreService.EXTRA_SOURCE, uri))
                notifyUser("กำลังกู้คืนข้อมูลเดิม ดูความคืบหน้าในแถบแจ้งเตือน")
            } catch (e: Exception) {
                notifyUser("เริ่มกู้คืนไม่ได้: " + (e.message ?: "unknown"))
            }
            return
        }
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
        // Swipe Back always returns to Home without stopping Linux or AI.
        when {
            currentTab == "control" && settingsCategory != "start" -> {
                settingsCategory = "start"
                showTab("control")
            }
            currentTab != "home" -> showTab("home")
            else -> {
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
        terminalCommandThread?.interrupt()
        terminalCommandThread = null
        terminalOutputView = null
        terminalRunButton = null
        terminalStopButton = null
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