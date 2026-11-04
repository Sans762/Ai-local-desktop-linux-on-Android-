package com.localai.controller

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Small, local-only preferences store. Never stores the user's plaintext password. */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("native_ai", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun hasPassword(): Boolean = prefs.contains("pass_hash")

    private fun randomToken(bytes: Int = 32): String {
        val raw = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun hash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 150_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    @Synchronized fun setPassword(password: String) {
        require(password.length >= 8) { "Password must be at least 8 characters" }
        val salt = ByteArray(24).also { random.nextBytes(it) }
        val chars = password.toCharArray()
        val digest = try { hash(chars, salt) } finally { chars.fill('\u0000') }
        prefs.edit()
            .putString("pass_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pass_hash", Base64.encodeToString(digest, Base64.NO_WRAP))
            .putString("session", randomToken())
            .putString("csrf", randomToken())
            .apply()
    }

    fun verifyPassword(password: String): Boolean {
        if (!hasPassword()) return false
        return try {
            val salt = Base64.decode(prefs.getString("pass_salt", ""), Base64.DEFAULT)
            val expected = Base64.decode(prefs.getString("pass_hash", ""), Base64.DEFAULT)
            val chars = password.toCharArray()
            val actual = try { hash(chars, salt) } finally { chars.fill('\u0000') }
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) { false }
    }

    fun hasSession(received: String?): Boolean {
        val value = received ?: return false
        val correct = prefs.getString("session", "") ?: ""
        return correct.isNotEmpty() && value.length == correct.length &&
            MessageDigest.isEqual(value.toByteArray(), correct.toByteArray())
    }

    fun sessionToken(): String = prefs.getString("session", "") ?: ""
    fun csrfToken(): String = prefs.getString("csrf", "") ?: ""
    fun selectedModel(): String = prefs.getString("model_file", "") ?: ""
    fun setSelectedModel(name: String) { prefs.edit().putString("model_file", name).apply() }
    fun allowUiControl(): Boolean = prefs.getBoolean("allow_control", false)
    fun setAllowUiControl(yes: Boolean) { prefs.edit().putBoolean("allow_control", yes).apply() }
    fun aiName(): String = prefs.getString("ai_name", "Local AI") ?: "Local AI"
    fun setAiName(name: String) { prefs.edit().putString("ai_name", name.take(60)).apply() }
}