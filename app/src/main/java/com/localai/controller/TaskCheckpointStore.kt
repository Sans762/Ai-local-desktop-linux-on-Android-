package com.localai.controller

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.WeakHashMap

/** Two bounded app-private records; no model files, approvals or executable plans are saved. */
class TaskCheckpointStore(
    private val directory: File,
    channel: String,
    private val wallClock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val MAX_BYTES = 65_536L
        private val lock = Any()
        private val registered = WeakHashMap<TaskCheckpointStore, Boolean>()

        fun cancelRegistered() {
            val stores = synchronized(lock) { registered.keys.toList() }
            for (store in stores) try { store.cancel() } catch (_: Exception) { }
        }
    }

    private val file: File
    init {
        require(channel in setOf("chat", "desktop"))
        file = File(directory, "$channel-task.properties")
        synchronized(lock) { registered[this] = true }
    }

    data class Record(val id: String, val request: String, val rounds: Int,
                      val evidence: String, val state: AgentTask.State,
                      val remainingMs: Long) {
        fun restore(): AgentTask? = AgentTask.restore(id, request, rounds, evidence, remainingMs, state)
    }

    fun save(task: AgentTask, allowed: () -> Boolean = { true }): Boolean = synchronized(lock) {
        if (!allowed()) return@synchronized false
        write(Record(task.id, task.request.take(1200), task.rounds, task.evidence.takeLast(6000),
            task.state, task.remainingMillis()))
        true
    }

    fun load(): Record? = synchronized(lock) {
        try {
            if (!file.isFile || file.length() !in 1..MAX_BYTES) return@synchronized null
            val p = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            if (p.getProperty("schema") != "1") return@synchronized null
            val id = p.getProperty("id") ?: return@synchronized null
            val request = p.getProperty("request") ?: return@synchronized null
            val evidence = p.getProperty("evidence", "").orEmpty()
            val rounds = p.getProperty("rounds")?.toIntOrNull() ?: return@synchronized null
            val state = AgentTask.State.valueOf(p.getProperty("state") ?: return@synchronized null)
            val savedAt = p.getProperty("savedAt")?.toLongOrNull() ?: return@synchronized null
            val remaining = p.getProperty("remainingMs")?.toLongOrNull() ?: return@synchronized null
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || request.length !in 1..1200 ||
                evidence.length > 6000 || rounds !in 0..AgentTask.MAX_ROUNDS ||
                remaining !in 0..AgentTask.BUDGET_MS || savedAt < 0) return@synchronized null
            val elapsed = (wallClock() - savedAt).coerceAtLeast(0L)
            Record(id, request, rounds, evidence, state, (remaining - elapsed).coerceAtLeast(0L))
        } catch (_: Exception) { null }
    }

    fun restore(): AgentTask? = load()?.restore()

    private fun cancel() = synchronized(lock) {
        val record = load() ?: return@synchronized
        write(record.copy(state = AgentTask.State.CANCELLED))
    }

    private fun write(record: Record) {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create task checkpoint directory" }
        val p = Properties().apply {
            setProperty("schema", "1"); setProperty("id", record.id)
            setProperty("request", record.request); setProperty("rounds", record.rounds.toString())
            setProperty("evidence", record.evidence); setProperty("state", record.state.name)
            setProperty("remainingMs", record.remainingMs.toString())
            setProperty("savedAt", wallClock().toString())
        }
        val staging = File(directory, file.name + ".new")
        try {
            FileOutputStream(staging).use { stream ->
                val writer = stream.writer(Charsets.UTF_8)
                p.store(writer, "Local AI task checkpoint")
                writer.flush()
                require(staging.length() <= MAX_BYTES) { "Checkpoint exceeds size limit" }
                stream.fd.sync()
            }
            try {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { staging.delete() }
    }
}
