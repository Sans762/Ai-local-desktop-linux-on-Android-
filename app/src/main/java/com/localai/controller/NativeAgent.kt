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

/** Offline chat only. No Android Accessibility API or phone UI actions. */
class NativeAgent(
    private val context: Context,
    private val store: AppStore,
    private val db: ChatDatabase,
    private val imports: ImportRepository
) {
    private val lock = Mutex()
    private fun ramMb(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem / 1048576L
    }

    fun status(): JSONObject {
        val file = imports.modelFile()
        return JSONObject().put("ok", true).put("name", store.aiName())
            .put("model", file?.name ?: "")
            .put("modelBytes", file?.length() ?: 0)
            .put("engine", "on-device GGUF (CPU)")
            .put("ramAvailableMb", ramMb())
            .put("scope", "LINUX_ONLY")
            .put("linuxInstalled", LinuxRuntime(context).installed())
            .put("accessibilityEnabled", false)
            .put("controlAllowed", false)
            .put("working", AiForegroundService.working)
    }

    suspend fun chat(userText: String): JSONObject = lock.withLock {
        val text = userText.trim().take(3500)
        require(text.isNotBlank()) { "Empty message" }
        val model = imports.modelFile() ?: return@withLock JSONObject()
            .put("ok", false).put("error", "NO_GGUF_MODEL_IMPORTED")
        val available = ramMb()
        val required = maxOf(1700L, ((model.length() / 1048576L) * 1.15).toLong() + 850)
        if (available < required) return@withLock JSONObject().put("ok", false)
            .put("error", "LOW_RAM: $available MB available; approximately $required MB required")
        val history = db.recentMessages(6)
        val prior = buildString {
            for (i in 0 until history.length()) {
                val item = history.getJSONObject(i)
                append(item.optString("role")).append(": ")
                    .append(item.optString("content").take(400)).append("\n")
            }
        }
        val memories = db.recall(text)
        val system = buildString {
            append("You are ").append(store.aiName())
            append(", a local offline assistant inside an Android app with a PRIVATE Alpine Linux userland. ")
            append("Reply in Thai or English to match the user. Never claim you controlled Android. ")
            append("You have NO Android screen, Accessibility, or phone control. ")
            append("For actual Linux operations, direct the user to the Linux Agent panel: it creates commands for their approval. ")
            append("Never invent completed commands or facts. ")
            if (memories.isNotEmpty()) append("Memories: ").append(memories.joinToString(" | "))
        }
        AiForegroundService.working = true
        try {
            val answer = withContext(Dispatchers.IO) {
                val handle = Llama.loadModel(model.absolutePath, LlamaConfig(contextSize = 2048, threads = 4))
                try { Llama.complete(handle, prompt = prior + "\nuser: " + text, systemPrompt = system, maxTokens = 310).text.trim() }
                finally { Llama.releaseModel(handle) }
            }
            db.addMessage("user", text)
            db.addMessage("assistant", answer, model.name)
            JSONObject().put("ok", true).put("reply", answer).put("model", model.name)
        } finally { AiForegroundService.working = false }
    }
}
