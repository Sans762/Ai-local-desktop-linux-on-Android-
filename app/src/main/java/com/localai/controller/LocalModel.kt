package com.localai.controller

import android.app.ActivityManager
import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Chat and desktop share one model slot, including across different HTTP servers. */
object LocalModel {
    private val slot = Mutex()

    suspend fun complete(context: Context, model: File, work: AgentWork.Ticket,
                         prompt: String, system: String, maxTokens: Int): String = slot.withLock {
        work.check()
        // Check RAM after waiting for the previous inference to release its model.
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val required = maxOf(1700L, (model.length() / 1048576L * 1.15).toLong() + 850L)
        require(info.availMem / 1048576L >= required) { "LOW_RAM_FOR_MODEL" }
        // JNI cannot be interrupted in 0.1.1. Keep ownership through cancellation,
        // including a timeout during load, so no native handle leaks or is freed early.
        currentCoroutineContext().ensureActive()
        val response = withContext(NonCancellable) {
            val handle = Llama.loadModel(model.absolutePath, LlamaConfig(contextSize = 3072, threads = 4))
            try {
                work.check()
                val result = Llama.complete(handle, prompt, system, maxTokens).text.trim()
                work.check()
                result
            } finally { Llama.releaseModel(handle) }
        }
        currentCoroutineContext().ensureActive()
        response
    }
}
