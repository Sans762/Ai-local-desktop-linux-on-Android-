package com.localai.controller

import android.app.ActivityManager
import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * The AI controls only the private Alpine PRoot filesystem, never the Android UI.
 * Planning never executes shell commands. Every plan needs explicit confirmation.
 * PRoot shares the host kernel: it is not a security boundary against hostile code.
 */
class LinuxAgent(
    private val context: Context,
    private val imports: ImportRepository,
    private val db: ChatDatabase
) {
    private val guard = Mutex()
    private val linux = LinuxRuntime(context)

    private data class Pending(
        val id: String,
        val task: String,
        val summary: String,
        val commands: List<String>,
        val until: Long
    )
    private var pending: Pending? = null

    fun status(): JSONObject = JSONObject()
        .put("ok", true)
        .put("installed", linux.installed())
        .put("ready", linux.available())
        .put("model", imports.modelFile()?.name ?: "")
        .put("scope", "ALPINE_LINUX_ONLY")
        .put("desktopRunning", LinuxDesktopService.running)
        .put("androidControl", false)
        .put("approvalRequired", true)

    suspend fun propose(request: String): JSONObject = guard.withLock {
        val task = request.trim().take(1600)
        if (task.isBlank()) return@withLock JSONObject().put("ok", false).put("error", "EMPTY_TASK")
        if (!linux.available()) return@withLock JSONObject().put("ok", false).put("error", "INSTALL_LINUX_FIRST")
        val model = imports.modelFile() ?: return@withLock JSONObject().put("ok", false).put("error", "INSTALL_QWEN_GGUF_FIRST")
        val memoryInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memoryInfo)
        val freeMb = memoryInfo.availMem / 1048576L
        if (freeMb < 1250L) return@withLock JSONObject().put("ok", false).put("error", "LOW_RAM_FOR_MODEL")

        val desktopHint = if (LinuxDesktopService.running) {
            "A Linux X11 desktop is running on DISPLAY=:1. You may propose " +
                "DISPLAY=:1 xdotool key/type/mousemove/click commands only after " +
                "explicit user approval. You CANNOT observe a screenshot, " +
                "so do not invent visual verification."
        } else {
            "Linux Desktop is not running; use command-line actions only."
        }
        val system = """
            You are a local Alpine Linux ARM64 assistant, not an Android controller.
            The user asks for a Linux task. Return one JSON object only:
            {"summary":"short Thai or English description","commands":["shell command 1","shell command 2"]}
            Choose 1 to 4 commands for /bin/sh. Commands run with root inside the
            app's private Alpine PRoot userland, NEVER Android's shell or UI.
            Installed packages use apk, not apt. Files stay in the Linux rootfs.
            Never claim execution or success: you only PREPARE commands for review.
            Explain limitations instead of inventing successful results.
            Only use network tools that are installed inside Alpine for web tasks.
            Never claim a website was read without actual command output.
            Do not include markdown fences, surrounding prose, or extra fields.
        """.trimIndent() + "\n" + desktopHint
        val draft = try {
            withContext(Dispatchers.IO) {
                val handle = Llama.loadModel(model.absolutePath, LlamaConfig(contextSize = 2048, threads = 4))
                try { Llama.complete(handle, prompt = "Linux user request: $task", systemPrompt = system, maxTokens = 370).text.trim() }
                finally { Llama.releaseModel(handle) }
            }
        } catch (e: Exception) {
            return@withLock JSONObject().put("ok", false).put("error", "MODEL_INFERENCE_FAILED").put("detail", e.message ?: "")
        }
        val start = draft.indexOf('{')
        val end = draft.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return@withLock JSONObject().put("ok", false).put("error", "MODEL_DID_NOT_PRODUCE_JSON").put("draft", draft.take(1300))
        }
        val parsed = try { JSONObject(draft.substring(start, end + 1)) } catch (_: Exception) {
            return@withLock JSONObject().put("ok", false).put("error", "INVALID_MODEL_JSON").put("draft", draft.take(1300))
        }
        val raw = parsed.optJSONArray("commands")
            ?: return@withLock JSONObject().put("ok", false).put("error", "MODEL_DID_NOT_PROPOSE_COMMANDS").put("draft", draft.take(1300))
        val commands = mutableListOf<String>()
        if (raw.length() !in 1..4) return@withLock JSONObject().put("ok", false).put("error", "PLAN_MUST_HAVE_1_TO_4_COMMANDS")
        for (i in 0 until raw.length()) {
            val value = raw.optString(i).trim()
            if (value.length !in 1..700 || value.contains('\u0000')) {
                return@withLock JSONObject().put("ok", false).put("error", "UNSAFE_COMMAND_LENGTH_OR_ENCODING")
            }
            commands.add(value)
        }
        val plan = Pending(UUID.randomUUID().toString(), task,
            parsed.optString("summary").take(600).ifBlank { "Proposed Linux commands" },
            commands, System.currentTimeMillis() + 5L * 60_000L)
        pending = plan
        JSONObject().put("ok", true).put("planId", plan.id).put("summary", plan.summary)
            .put("commands", JSONArray(commands)).put("expiresInSeconds", 300)
            .put("notice", "Not executed. Review commands and press Approve.")
    }

    suspend fun execute(planId: String): JSONObject = guard.withLock {
        val plan = pending
        pending = null // One-shot, including failed or cancelled execution
        if (plan == null || plan.id != planId || System.currentTimeMillis() > plan.until) {
            return@withLock JSONObject().put("ok", false).put("error", "PLAN_EXPIRED_OR_ALREADY_USED")
        }
        if (!linux.available()) return@withLock JSONObject().put("ok", false).put("error", "LINUX_NOT_READY")
        val results = JSONArray()
        var completed = true
        for (command in plan.commands) {
            val output = try {
                withContext(Dispatchers.IO) { linux.command(command, timeoutSeconds = 30) }
            } catch (e: Exception) {
                "Linux runtime error: " + (e.message ?: "unknown")
            }
            results.put(JSONObject().put("command", command).put("output", output.take(6000)))
            if (!output.trimEnd().endsWith("[exit 0]")) {
                completed = false
                break
            }
        }
        db.addMessage("user", "Linux: " + plan.task)
        db.addMessage("assistant", results.toString().take(10000), "Alpine Linux Agent")
        JSONObject().put("ok", true).put("completed", completed)
            .put("executed", results.length()).put("total", plan.commands.size)
            .put("results", results).put("summary", plan.summary)
    }

    suspend fun command(value: String): JSONObject = guard.withLock {
        val cmd = value.trim()
        if (cmd.isBlank() || cmd.length > 1000) return@withLock JSONObject().put("ok", false).put("error", "BAD_COMMAND")
        if (!linux.available()) return@withLock JSONObject().put("ok", false).put("error", "INSTALL_LINUX_FIRST")
        val output = withContext(Dispatchers.IO) { linux.command(cmd, timeoutSeconds = 35) }
        JSONObject().put("ok", true).put("command", cmd).put("output", output)
    }
}
