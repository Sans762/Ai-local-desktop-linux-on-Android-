package com.localai.controller

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class ImportRepository(private val context: Context, private val store: AppStore, private val db: ChatDatabase) {
    private fun displayName(uri: Uri): String {
        var name = "model.gguf"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        return name
    }

    fun modelDir(): File = File(context.filesDir, "models").apply { mkdirs() }
    fun modelFile(): File? = store.selectedModel().takeIf { it.isNotBlank() }
        ?.let { File(modelDir(), it) }?.takeIf { it.isFile }

    /** Only SAF-selected files can be imported. No Termux private directories are accessed. */
    fun importModel(uri: Uri): JSONObject {
        val suggested = displayName(uri)
        require(suggested.lowercase().endsWith(".gguf")) { "Select a .gguf file" }
        val clean = suggested.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
        val existing = File(modelDir(), clean)
        val safeName = if (existing.exists()) clean.removeSuffix(".gguf") + "-" + System.currentTimeMillis() + ".gguf" else clean
        val target = File(modelDir(), safeName)
        val temp = File(modelDir(), "$safeName.partial")
        try {
            val usable = context.filesDir.usableSpace
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val magic = ByteArray(4)
                    require(input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") { "Not a GGUF file" }
                    output.write(magic)
                    val chunk = ByteArray(128 * 1024)
                    var written = 4L
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        written += count
                        require(written <= 12L * 1024 * 1024 * 1024) { "Model exceeds 12 GB import cap" }
                        require(written < usable - 256L * 1024 * 1024) { "Insufficient storage" }
                        output.write(chunk, 0, count)
                    }
                    output.flush()
                }
            } ?: error("Cannot read selected file")
            require(temp.length() > 1024) { "GGUF file is too small" }
            require(!target.exists() && temp.renameTo(target)) { "Cannot finalize model import without overwriting existing models" }
            store.setSelectedModel(target.name)
            return JSONObject().put("name", target.name).put("bytes", target.length())
        } finally { if (temp.exists()) temp.delete() }
    }

    fun importLegacyDatabase(uri: Uri): JSONObject {
        val temp = File(context.cacheDir, "legacy-readonly-${System.nanoTime()}.db")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: error("Cannot read selected file")
            require(temp.length() in 256L..250_000_000L) { "Legacy DB size out of range" }
            return db.importLegacy(temp)
        } finally { temp.delete() }
    }
}