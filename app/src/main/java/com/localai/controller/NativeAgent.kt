package com.localai.controller

import android.app.ActivityManager
import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** No network API calls. GGUF inference happens in the Android application process. */
class NativeAgent(private val context: Context,
                  private val store: AppStore,
                  private val db: ChatDatabase,
                  private val imports: ImportRepository) {
    private val lock = Mutex()

    private fun ramMb(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    fun status(): JSONObject {
        val file = imports.modelFile()
        val ui = AutomationAccessibilityService.current
        return JSONObject().put("ok", true).put("name", store.aiName())
            .put("model", file?.name ?: "")
            .put("modelBytes", file?.length() ?: 0)
            .put("engine", "on-device GGUF (CPU)")
            .put("ramAvailableMb", ramMb())
            .put("accessibilityEnabled", ui != null)
            .put("controlAllowed", store.allowUiControl())
            .put("working", AiForegroundService.working)
    }

    private fun isDirectControlRequest(message: String): Boolean =
        Regex("^(ช่วย)?(กด|แตะ|คลิก|พิมพ์|กลับ|ไปหน้าโฮม|tap\\b|click\\b|type\\b|back\\b|home\\b)", RegexOption.IGNORE_CASE)
            .containsMatchIn(message.trim())

    private fun performTool(data: JSONObject): JSONObject {
        if (!store.allowUiControl()) return JSONObject().put("ok", false).put("error", "UI_CONTROL_DISABLED_IN_NATIVE_APP")
        val a11y = AutomationAccessibilityService.current
            ?: return JSONObject().put("ok", false).put("error", "ENABLE_ACCESSIBILITY_SERVICE")
        val action = data.optString("action", "")
        val ok = when (action) {
            "tap" -> {
                val x = data.optInt("x", -1); val y = data.optInt("y", -1)
                if (x !in 0..5000 || y !in 0..5000) false else a11y.tap(x, y)
            }
            "back" -> a11y.goBack()
            "home" -> a11y.goHome()
            "type" -> a11y.typeIntoFocused(data.optString("text", "").take(800))
            else -> false
        }
        return JSONObject().put("ok", ok).put("action", action)
            .put("message", if (ok) "Android accepted the action; target outcome not yet verified" else "Action not completed")
    }

    fun directAction(data: JSONObject): JSONObject = performTool(data)

    suspend fun chat(userText: String): JSONObject = lock.withLock {
        val text = userText.trim().take(3500)
        require(text.isNotBlank()) { "Empty message" }
        val model = imports.modelFile() ?: return@withLock JSONObject().put("ok", false)
            .put("error", "NO_GGUF_MODEL_IMPORTED")
        val available = ramMb()
        // Conservative RAM guard: model weights + KV cache + Android/engine overhead.
        val required = maxOf(1700L, ((model.length() / (1024L * 1024L)) * 1.15).toLong() + 850)
        if (available < required) return@withLock JSONObject().put("ok", false)
            .put("error", "LOW_RAM: ${available} MB available; model requires approx ${required} MB free. Try a smaller GGUF or close heavy apps.")

        val history = db.recentMessages(6)
        val memories = db.recall(text)
        val controlRequested = store.allowUiControl() && isDirectControlRequest(text)
        val observation = if (controlRequested) AutomationAccessibilityService.current?.observe()?.toString()?.take(3000) ?: "Accessibility unavailable" else ""
        val system = buildString {
            append("You are ").append(store.aiName()).append(" running entirely offline on Android. ")
            append("Reply only in Thai or English matching the user. Use real evidence. Never invent completed actions. ")
            append("You are not ChatGPT and must not claim capabilities you lack. ")
            if (memories.isNotEmpty()) append("Useful saved memories: ").append(memories.joinToString(" | ")).append(". ")
            if (controlRequested) append("For an explicitly requested phone UI action, output ONLY JSON: ")
                .append("""{"action":"tap|back|home|type|none","x":100,"y":100,"text":"","reply":"short explanation"}. """)
                .append("Choose only one action; never assume it succeeded. ")
        }
        val prior = buildString {
            for (i in 0 until history.length()) {
                val item = history.getJSONObject(i)
                append(item.optString("role")).append(": ").append(item.optString("content").take(500)).append("\n")
            }
        }
        val prompt = buildString {
            append(prior)
            if (controlRequested) append("CURRENT_ACCESSIBILITY_TREE: ").append(observation).append("\n")
            append("user: ").append(text)
        }

        AiForegroundService.working = true
        try {
            val answer = withContext(Dispatchers.IO) {
                val handle = Llama.loadModel(model.absolutePath, LlamaConfig(contextSize = 2048, threads = 4))
                try { Llama.complete(handle, prompt = prompt, systemPrompt = system, maxTokens = 260).text.trim() }
                finally { Llama.releaseModel(handle) }
            }
            var response = answer
            var tool: JSONObject? = null
            if (controlRequested && answer.startsWith("{")) {
                try {
                    val proposed = JSONObject(answer)
                    if (proposed.optString("action") in setOf("tap", "back", "home", "type")) {
                        tool = performTool(proposed)
                        response = (if (tool.optBoolean("ok")) "ส่งคำสั่งให้ Android แล้ว แต่ยังไม่ยืนยันผลในแอปปลายทาง" else "สั่งงานไม่สำเร็จ: ${tool.optString("error", "ไม่ทราบสาเหตุ")}")
                    } else response = proposed.optString("reply", answer)
                } catch (_: Exception) { response = "ยังสั่งงานไม่ได้: โมเดลไม่ส่งคำสั่งในรูปแบบที่ถูกต้อง" }
            }
            db.addMessage("user", text)
            db.addMessage("assistant", response, model.name)
            JSONObject().put("ok", true).put("reply", response).put("model", model.name)
                .also { if (tool != null) it.put("tool", tool) }
        } finally { AiForegroundService.working = false }
    }
}