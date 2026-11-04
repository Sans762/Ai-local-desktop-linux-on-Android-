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
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = panel()
            val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
            parent.addView(this, lp)
            body()
        }
    private fun spacer(parent: LinearLayout, height: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun actionButton(title: String, filled: Boolean = false, onClick: () -> Unit): TextView =
        label(title, 14.5f, true).apply {
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
            setPadding(dp(19), dp(14), dp(19), dp(10))
        }
        mainColumn.addView(header, LinearLayout.LayoutParams(-1, -2))
        val logo = label("✦", 25f, true).apply {
            gravity = Gravity.CENTER
            background = gradient(15)
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(49), dp(49)))
        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("LOCAL AI", 17f, true))
            addView(label("NATIVE WORKSPACE  •  V0.4", 10f, true, sub))
        }
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f))
        serviceChip = label("", 11f, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = panel(cardDeep, 14)
        }
        header.addView(serviceChip)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(17), dp(2), dp(17), dp(10))
        }
        mainColumn.addView(nav)
        workspaceTab = actionButton("✦   Workspace") { showTab("workspace") }
        controlsTab = actionButton("⚙   Control Center") { showTab("control") }
        nav.addView(workspaceTab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        nav.addView(controlsTab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        screenHost = FrameLayout(this).apply { setBackgroundColor(bg) }
        mainColumn.addView(screenHost, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshChip()
    }

    private fun refreshChip() {
        val running = AiForegroundService.running
        serviceChip.text = if (running) "●  ACTIVE" else "●  OFFLINE"
        serviceChip.setTextColor(if (running) mint else sub)
    }

    private fun showTab(target: String) {
        currentTab = target
        refreshChip()
        workspaceTab.background = if (target == "workspace") gradient(14) else panel(cardDeep, 14)
        controlsTab.background = if (target == "control") gradient(14) else panel(cardDeep, 14)
        screenHost.removeAllViews()
        if (target == "workspace") showWorkspace() else showControls()
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
                return uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == AiForegroundService.PORT
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
            setPadding(dp(17), 0, dp(17), dp(22))
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
            addView(label("04  /  ควบคุมหน้าจอ Android", 12f, true, mint))
            spacer(this, 9)
            val control = CheckBox(this@MainActivity).apply {
                text = "อนุญาตให้ AI ส่งคำสั่งแตะ/พิมพ์/ย้อนกลับ"
                textSize = 13f
                setTextColor(this@MainActivity.text)
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = store.allowUiControl()
                setOnCheckedChangeListener { _, checked ->
                    store.setAllowUiControl(checked)
                    notifyUser(if (checked) "เปิดการอนุญาตในแอป • ต้องเปิด Accessibility ด้วย" else "ปิดการควบคุมแล้ว")
                }
            }
            addView(control)
            spacer(this, 8)
            addView(label(if (AutomationAccessibilityService.current != null) "● Accessibility เชื่อมต่อแล้ว" else "● Accessibility ยังไม่เปิด", 12f, true, if (AutomationAccessibilityService.current != null) mint else sub))
            spacer(this, 11)
            addButton(this, "ไปที่การตั้งค่า Accessibility") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            spacer(this, 7)
            addView(label("คำสั่งจริงต้องได้รับสิทธิ์ทั้งสองจุด • ไม่รองรับการควบคุมทั้งเครื่องโดยอัตโนมัติ", 12f, false, sub))
        }
        cardBlock(content) {
            addView(label("05  /  Remote Desktop", 12f, true, mint))
            spacer(this, 9)
            addView(label("ดูหน้าจอ Android สดผ่านเว็บจากอุปกรณ์ใน LAN หรือ VPN ส่วนตัว", 13f, false, sub))
            spacer(this, 9)
            if (ScreenShareService.running) {
                addView(label("●  กำลังแชร์หน้าจอ", 14.5f, true, mint))
                spacer(this, 7)
                addView(label("รหัสจับคู่ 8 หลัก: ${RemoteSession.pin}", 20f, true))
                spacer(this, 8)
                val urls = RemoteSession.localUrls(ScreenShareService.PORT)
                addView(label(if (urls.isEmpty()) "ไม่พบ IP เครือข่ายส่วนตัว" else urls.joinToString("\n"), 13f, false, sub))
                spacer(this, 12)
                val remoteInput = CheckBox(this@MainActivity).apply {
                    text = "อนุญาตให้รีโมทกดหน้าจอ/พิมพ์ได้ในเซสชันนี้"
                    textSize = 13f
                    setTextColor(this@MainActivity.text)
                    buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                    isChecked = RemoteSession.allowInput
                    setOnCheckedChangeListener { _, checked ->
                        RemoteSession.allowInput = checked
                        notifyUser(if (checked) "อนุญาตรีโมทแล้ว • ต้องเปิด Accessibility ด้วย" else "ปิดคำสั่งรีโมทแล้ว")
                    }
                }
                addView(remoteInput)
                spacer(this, 10)
                addButton(this, "■  หยุดแชร์หน้าจอ") {
                    RemoteSession.allowInput = false
                    stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                    ui.postDelayed({ showTab("control") }, 400)
                }
            } else {
                addView(label("●  ยังไม่ได้แชร์หน้าจอ", 13f, true, sub))
                spacer(this, 11)
                addButton(this, "▣  เริ่มแชร์หน้าจอ (ต้องอนุญาต)", true) {
                    if (!store.hasPassword()) { notifyUser("ตั้งรหัสผ่านในแอปก่อนเริ่มใช้ Remote Desktop"); return@addButton }
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), 77)
                }
            }
            if (ScreenShareService.lastError.isNotBlank()) {
                spacer(this, 9)
                addView(label(ScreenShareService.lastError, 12f, false, Color.rgb(255, 177, 183)))
            }
            spacer(this, 12)
            addView(label("เพื่อความปลอดภัย: เปิดเฉพาะเมื่อใช้จริง • จับคู่ด้วย PIN • ค่าเริ่มต้นดูได้อย่างเดียว • HTTP LAN ไม่เข้ารหัส จึงห้ามเปิดพอร์ตสู่อินเทอร์เน็ต", 12f, false, sub))
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
            addButton(this, "▣  ติดตั้ง Linux จาก APK (ใช้แบบออฟไลน์)", true) {
                status.text = "กำลังแตกไฟล์ Linux…"
                Thread {
                    try {
                        linux.install { value -> runOnUiThread { status.text = value } }
                        runOnUiThread { status.setTextColor(mint) }
                    } catch (e: Exception) {
                        runOnUiThread { status.text = "Linux error: ${e.message}" }
                    }
                }.start()
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
            addView(label("07  /  ระบบและข้อจำกัด", 12f, true, mint))
            spacer(this, 8)
            addView(label("Android Native • Loopback Server 18788 • SQLite Memory • GGUF CPU", 12.5f, false, sub))
            spacer(this, 9)
            addButton(this, "เปิดเว็บ Dashboard ในเบราว์เซอร์") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:18788")))
            }
            spacer(this, 9)
            addView(label("จอสด Android ใช้ MediaProjection โดยต้องยืนยันทุกเซสชัน • Windows Remote, โทรเรียลไทม์ และการเชื่อมตรงกับ ChatGPT ยังไม่รองรับ • Android อาจหยุดบริการเมื่อ RAM ต่ำ", 12f, false, sub))
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
        if (requestCode == 77) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    startForegroundService(Intent(this, ScreenShareService::class.java).apply {
                        putExtra(ScreenShareService.EXTRA_RESULT, resultCode)
                        putExtra(ScreenShareService.EXTRA_INTENT, data)
                    })
                    notifyUser("กำลังเริ่ม Remote Desktop ตามสิทธิ์ที่อนุญาต…")
                    ui.postDelayed({ showTab("control") }, 800)
                } catch (e: Exception) { notifyUser("เริ่มแชร์หน้าจอไม่ได้: ${e.message}") }
            } else notifyUser("ยกเลิกการแชร์หน้าจอแล้ว")
            return
        }
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
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
        if (currentTab == "workspace") showTab("control") else super.onBackPressed()
    }

    override fun onDestroy() {
        val web = webView
        webView = null
        if (web != null) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notifyUser(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}