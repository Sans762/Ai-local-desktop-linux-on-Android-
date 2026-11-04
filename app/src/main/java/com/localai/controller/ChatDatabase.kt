package com.localai.controller

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "native-memory.db", null, 1) {
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