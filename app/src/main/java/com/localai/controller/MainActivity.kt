package com.localai.controller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/** Native control plane. The browser is simply another frontend to the same Android service. */
class MainActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var imports: ImportRepository
    private lateinit var status: TextView
    private lateinit var password: EditText
    private lateinit var nameInput: EditText
    private lateinit var controlSwitch: CheckBox
    private val modelPicker = 31
    private val dbPicker = 32
    private val configPicker = 33

    private fun button(container: LinearLayout, label: String, cb: () -> Unit) {
        val b = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { cb() } }
        container.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun text(container: LinearLayout, message: String, large: Boolean = false): TextView {
        val t = TextView(this).apply { text = message; textSize = if (large) 22f else 14f; setPadding(0, 10, 0, 10) }
        container.addView(t); return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppStore(this)
        imports = ImportRepository(this, store, ChatDatabase(this))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(26, 32, 26, 24) }
        val scroll = ScrollView(this).apply { addView(body) }
        setContentView(scroll)
        text(body, "Local AI Native", true)
        text(body, "AI runs offline inside this Android app. Local dashboard: http://127.0.0.1:18788")
        status = text(body, "Checking status…")
        text(body, "AI name (optional)")
        nameInput = EditText(this).apply { setText(store.aiName()); hint = "My AI" }
        body.addView(nameInput)
        button(body, "Save AI name") { store.setAiName(nameInput.text.toString().ifBlank { "Local AI" }); refresh() }

        text(body, "Set or change web access password (8+ characters)")
        password = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Choose your own password"
        }
        body.addView(password)
        button(body, "Save web password") {
            try {
                store.setPassword(password.text.toString())
                password.setText("")
                toast("Password saved. Existing browser sessions signed out.")
                refresh()
            } catch (e: Exception) { toast(e.message ?: "Invalid password") }
        }
        button(body, "Start foreground AI service") {
            if (!store.hasPassword()) { toast("First set a web password"); return@button }
            try {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 90)
                startForegroundService(Intent(this, AiForegroundService::class.java))
                toast("Starting service…")
            } catch (e: Exception) { toast("Could not start: ${e.message}") }
            window.decorView.postDelayed({ refresh() }, 1300)
        }
        button(body, "Stop AI service") {
            stopService(Intent(this, AiForegroundService::class.java)); window.decorView.postDelayed({ refresh() }, 500)
        }
        button(body, "Open web dashboard in Chrome") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:18788")))
        }
        text(body, "Offline AI models")
        button(body, "Import a GGUF model (no model is deleted from Termux)") { pick(modelPicker, "*/*") }
        text(body, "Restore old AI memory (copy only, no data deletion)")
        button(body, "Import legacy memory.db") { pick(dbPicker, "*/*") }
        button(body, "Import safe AI name from old config.json") { pick(configPicker, "application/json") }
        text(body, "Optional phone UI automation")
        controlSwitch = CheckBox(this).apply {
            text = "Allow requested tap/type/back/home actions through accessibility"
            isChecked = store.allowUiControl()
            setOnCheckedChangeListener { _, checked ->
                store.setAllowUiControl(checked)
                toast(if (checked) "Now enable the Accessibility Service below" else "UI control disabled")
            }
        }
        body.addView(controlSwitch)
        button(body, "Open Android Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        text(body, "Android can still stop a foreground service during memory pressure. Do not install another copy of your old model until the import succeeds.")
        refresh()
    }

    private fun pick(request: Int, mime: String) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }
        startActivityForResult(i, request)
    }

    @Deprecated("Uses the platform Storage Access Framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Importing selected file…"
        Thread({
            try {
                val result = when (requestCode) {
                    modelPicker -> imports.importModel(uri)
                    dbPicker -> imports.importLegacyDatabase(uri)
                    configPicker -> {
                        val s = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Unable to read config")
                        val j = JSONObject(s)
                        val name = j.optString("ai_name", "Local AI").take(60)
                        store.setAiName(name)
                        JSONObject().put("ai_name", name)
                    }
                    else -> error("Unknown import")
                }
                runOnUiThread { toast("Imported: $result"); refresh() }
            } catch (e: Exception) { runOnUiThread { toast("Import failed: ${e.message}"); refresh() } }
        }, "saf-import").start()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }
    private fun refresh() {
        val file = imports.modelFile()
        status.text = "Service: ${if (AiForegroundService.running) "RUNNING" else "STOPPED"}\n" +
            "Model: ${file?.name ?: "none (import GGUF)"}\n" +
            "Password: ${if (store.hasPassword()) "set" else "not set"}\n" +
            "Accessibility: ${if (AutomationAccessibilityService.current != null) "enabled" else "off"}\n" +
            "Control: ${if (store.allowUiControl()) "allowed" else "disabled"}\n" +
            "${AiForegroundService.lastError}"
    }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}