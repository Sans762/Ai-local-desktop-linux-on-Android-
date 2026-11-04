package com.localai.controller

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "native-memory.db", null, 1) {
    companion object {
        // Do not download, fine-tune or store a second AI model for learning.
        // These small, local SQLite notes are the entire learning footprint.
        private const val MAX_SUCCEEDED = 96
        private const val MAX_FAILED = 32
        private val sensitive = Regex(
            """(?i)(password|passwd|secret|token|bearer|authorization|cookie|api.?key|private.?key|รหัสผ่าน|รหัสยืนยัน|otp|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,})"""
        )
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, model TEXT, legacy_id INTEGER UNIQUE)")
        db.execSQL("CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT, ts REAL NOT NULL, content TEXT NOT NULL, tags TEXT, legacy_id INTEGER UNIQUE)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun addMessage(role: String, content: String, model: String = "") {
        val row = ContentValues().apply {
            put("ts", System.currentTimeMillis() / 1000.0)
            put("role", role.take(20)); put("content", content.take(32_000)); put("model", model.take(200))
        }
        writableDatabase.insert("messages", null, row)
    }

    @Synchronized fun recentMessages(limit: Int = 12): JSONArray {
        val list = mutableListOf<JSONObject>()
        readableDatabase.rawQuery("SELECT role,content,model,ts FROM messages ORDER BY id DESC LIMIT ?", arrayOf(limit.coerceIn(1, 60).toString())).use { c ->
            while (c.moveToNext()) list.add(JSONObject().put("role", c.getString(0)).put("content", c.getString(1)).put("model", c.getString(2) ?: "").put("ts", c.getDouble(3)))
        }
        return JSONArray(list.reversed())
    }

    @Synchronized fun recall(query: String, limit: Int = 3): List<String> {
        val q = query.lowercase()
        val grams = (0 until (q.length - 2).coerceAtLeast(0)).map { q.substring(it, it + 3) }.toSet()
        val scores = mutableListOf<Pair<Int, String>>()
        readableDatabase.rawQuery("SELECT content,tags FROM memory ORDER BY id DESC LIMIT 300", null).use { c ->
            while (c.moveToNext()) {
                val t = ((c.getString(0) ?: "") + " " + (c.getString(1) ?: "")).lowercase()
                val score = grams.count { t.contains(it) }
                if (score > 0) scores.add(score to t.take(500))
            }
        }
        return scores.sortedByDescending { it.first }.take(limit.coerceIn(1, 6)).map { it.second }
    }

    /**
     * Memory, not model training: retain only a bounded number of short,
     * non-sensitive observations about approved local Linux tasks.
     * Never delete user chat history, imported memories, Linux files or models.
     */
    private fun recordAgentLesson(task: String, detail: String, tag: String) {
        val request = task.trim().replace(Regex("""\s+"""), " ").take(140)
        val note = detail.trim().replace(Regex("""\s+"""), " ").take(160)
        if (request.isBlank() || note.isBlank() ||
            sensitive.containsMatchIn(request) || sensitive.containsMatchIn(note)) return
        val cap = if (tag == "agent:success") MAX_SUCCEEDED else MAX_FAILED
        val value = if (tag == "agent:success")
            "Linux command exited 0 · Task: $request · Result: $note"
        else "Linux command failed · Task: $request · Category: $note"
        val now = System.currentTimeMillis() / 1000.0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val update = ContentValues().apply { put("ts", now) }
            val refreshed = db.update("memory", update, "tags=? AND content=?",
                arrayOf(tag, value))
            if (refreshed == 0) {
                val row = ContentValues().apply {
                    put("ts", now)
                    put("content", value)
                    put("tags", tag)
                }
                db.insert("memory", null, row)
            }
            // Only prune our OWN auto-learned rows, never legacy/user memories.
            db.execSQL(
                "DELETE FROM memory WHERE tags=? AND id NOT IN (" +
                    "SELECT id FROM memory WHERE tags=? ORDER BY ts DESC, id DESC LIMIT ?)",
                arrayOf(tag, tag, cap)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun rememberLinuxSuccess(summary: String, task: String = summary) {
        // A zero exit status proves only command completion; GUI outcomes are
        // handled separately and must never be labeled visually verified.
        recordAgentLesson(task, summary, "agent:success")
    }

    @Synchronized fun rememberLinuxFailure(task: String, category: String) {
        // Record only a predefined failure category, not raw output or secrets.
        val accepted = setOf("package-lock", "permission", "not-found", "network", "command-failed")
        recordAgentLesson(task, category.takeIf { it in accepted } ?: "command-failed",
            "agent:failure")
    }

    @Synchronized fun recallAgentLessons(query: String, limit: Int = 3): List<String> {
        val needle = query.lowercase().take(180)
        if (needle.length < 3) return emptyList()
        val grams = (0 until needle.length - 2).map { needle.substring(it, it + 3) }.toSet()
        val found = mutableListOf<Pair<Int, String>>()
        readableDatabase.rawQuery(
            "SELECT content,tags FROM memory WHERE tags IN ('agent:success','agent:failure')" +
                " ORDER BY ts DESC, id DESC LIMIT 128", null
        ).use { c ->
            while (c.moveToNext()) {
                val content = c.getString(0) ?: continue
                val score = grams.count { content.lowercase().contains(it) }
                if (score > 0) {
                    found.add(score to content.take(330))
                }
            }
        }
        return found.sortedByDescending { it.first }.take(limit.coerceIn(1, 5))
            .map { it.second }
    }

    @Synchronized fun agentLearningStats(): JSONObject {
        var successes = 0
        var failures = 0
        var bytes = 0L
        readableDatabase.rawQuery(
            "SELECT tags,COUNT(*),COALESCE(SUM(LENGTH(CAST(content AS BLOB))),0) " +
                "FROM memory WHERE tags IN ('agent:success','agent:failure') GROUP BY tags",
            null
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == "agent:success") successes = c.getInt(1)
                else failures = c.getInt(1)
                bytes += c.getLong(2)
            }
        }
        return JSONObject().put("successes", successes).put("failures", failures)
            .put("approxTextBytes", bytes).put("maxLessons", MAX_SUCCEEDED + MAX_FAILED)
    }

    @Synchronized fun clearAgentLessons(): Int =
        writableDatabase.delete("memory", "tags IN (?,?)",
            arrayOf("agent:success", "agent:failure"))

    /** Reads a *copy* of the old Termux SQLite DB, never writes to the source database. */
    @Synchronized fun importLegacy(copy: File): JSONObject {
        val legacy = SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        var memories = 0
        var messages = 0
        val target = writableDatabase
        target.beginTransaction()
        try {
            if (hasTable(legacy, "messages")) {
                legacy.rawQuery("SELECT id,ts,role,content,model FROM messages ORDER BY id DESC LIMIT 2000", null).use { c ->
                    while (c.moveToNext()) {
                        val row = ContentValues().apply {
                            put("legacy_id", c.getLong(0)); put("ts", c.getDouble(1))
                            put("role", (c.getString(2) ?: "user").take(20))
                            put("content", (c.getString(3) ?: "").take(32_000))
                            put("model", (c.getString(4) ?: "").take(200))
                        }
                        if (target.insertWithOnConflict("messages", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L) messages++
                    }
                }
            }
            if (hasTable(legacy, "memory")) {
                legacy.rawQuery("SELECT id,ts,content,tags FROM memory ORDER BY id DESC LIMIT 2000", null).use { c ->
                    while (c.moveToNext()) {
                        val row = ContentValues().apply {
                            put("legacy_id", c.getLong(0)); put("ts", c.getDouble(1))
                            put("content", (c.getString(2) ?: "").take(32_000))
                            put("tags", (c.getString(3) ?: "").take(500))
                        }
                        if (target.insertWithOnConflict("memory", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L) memories++
                    }
                }
            }
            target.setTransactionSuccessful()
        } finally {
            target.endTransaction()
            legacy.close()
        }
        return JSONObject().put("messages", messages).put("memories", memories)
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { return it.moveToFirst() }
    }
}