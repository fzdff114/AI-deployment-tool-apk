package com.fzdff.aistudio

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ChatMessage(val role: String, val content: String, val id: Long = System.nanoTime())

data class AppSettings(
    val provider: String = "自定义",
    val apiProtocol: String = "openai_chat",
    val authHeaderName: String = "auto",
    val authHeaderPrefix: String = "Bearer",
    val endpoint: String = "",
    val model: String = "",
    val theme: String = "blue",
    val reasoning: String = "medium",
    val sendReasoning: Boolean = false,
    val maxTokens: Int = 2048,
    val sessionLimit: Int = 32000,
    val sessionUsed: Int = 0,
    val localModelPath: String = "",
    val contextSize: Int = 4096
) {
    val hasLocalModel: Boolean get() = localModelPath.isNotBlank()
}

object SecureStore {
    private const val PREFS = "secure_secrets"
    private const val KEY = "api_key"
    private const val ALIAS = "AIStudio.ApiKey.v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun save(context: Context, value: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isBlank()) {
            prefs.edit().remove(KEY).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        prefs.edit().putString(KEY, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context): String {
        return try {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return ""
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > 12)
            val iv = combined.copyOfRange(0, 12)
            val data = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}

object AppStore {
    private const val PREFS = "ai_studio"
    private const val MESSAGES = "messages"

    fun loadSettings(context: Context): AppSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One-time migration: remove only the model IDs that older builds silently
        // inserted as defaults. Do not clear them on every app launch, because the user
        // may intentionally choose one of these model IDs.
        if (!p.getBoolean("model_default_migration_done", false)) {
            val savedModel = p.getString("model", "") ?: ""
            if (savedModel in setOf("gpt-4.1-mini", "deepseek-chat", "openai/gpt-4.1-mini", "glm-4.7-flash")) {
                p.edit().putString("model", "").apply()
            }
            p.edit().putBoolean("model_default_migration_done", true).apply()
        }
        val savedProtocol = p.getString("api_protocol", "openai_chat") ?: "openai_chat"
        val normalizedProtocol = when (savedProtocol) {
            "openai" -> "openai_chat"
            "anthropic" -> "anthropic_messages"
            "gemini" -> "gemini_generate"
            else -> savedProtocol
        }
        return AppSettings(
            provider = p.getString("provider", "自定义") ?: "自定义",
            apiProtocol = normalizedProtocol,
            authHeaderName = p.getString("auth_header_name", "auto") ?: "auto",
            authHeaderPrefix = p.getString("auth_header_prefix", "Bearer") ?: "Bearer",
            endpoint = p.getString("endpoint", "") ?: "",
            model = p.getString("model", "") ?: "",
            theme = p.getString("theme", "blue") ?: "blue",
            reasoning = p.getString("reasoning", "medium") ?: "medium",
            sendReasoning = p.getBoolean("send_reasoning", false),
            maxTokens = p.getInt("max_tokens", 2048),
            sessionLimit = p.getInt("session_limit", 32000),
            sessionUsed = p.getInt("session_used", 0),
            localModelPath = p.getString("local_model_path", "") ?: "",
            contextSize = p.getInt("context_size", 4096)
        )
    }

    fun saveSettings(context: Context, s: AppSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("provider", s.provider)
            .putString("api_protocol", s.apiProtocol)
            .putString("endpoint", s.endpoint)
            .putString("model", s.model)
            .putString("auth_header_name", s.authHeaderName)
            .putString("auth_header_prefix", s.authHeaderPrefix)
            .putString("theme", s.theme)
            .putString("reasoning", s.reasoning)
            .putBoolean("send_reasoning", s.sendReasoning)
            .putInt("max_tokens", s.maxTokens.coerceIn(128, 8192))
            .putInt("session_limit", s.sessionLimit.coerceIn(0, 200000))
            .putInt("session_used", s.sessionUsed.coerceAtLeast(0))
            .putString("local_model_path", s.localModelPath)
            .putInt("context_size", s.contextSize.coerceIn(1024, 16384))
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("setup_done", false)) return true
        val s = loadSettings(context)
        val authRequired = !s.authHeaderName.equals("none", ignoreCase = true)
        return s.endpoint.isNotBlank() && s.model.isNotBlank() && (!authRequired || SecureStore.read(context).isNotBlank())
    }

    fun markSetupDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("setup_done", true).apply()
    }

    fun loadMessages(context: Context): List<ChatMessage> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MESSAGES, "[]") ?: "[]"
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { i ->
                val item = json.optJSONObject(i) ?: return@mapNotNull null
                val role = item.optString("role")
                val content = item.optString("content")
                if (role !in listOf("user", "assistant") || content.isBlank()) null
                else ChatMessage(role, content, item.optLong("id", i.toLong()))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMessages(context: Context, messages: List<ChatMessage>) {
        val json = JSONArray()
        messages.takeLast(120).forEach {
            json.put(JSONObject().put("role", it.role).put("content", it.content).put("id", it.id))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MESSAGES, json.toString()).apply()
    }

    fun resetSessionUsage(context: Context) {
        val s = loadSettings(context)
        saveSettings(context, s.copy(sessionUsed = 0))
    }
}


data class ChatSession(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val usedTokens: Int,
    val messages: List<ChatMessage>
)

object ChatHistoryStore {
    private const val PREFS = "ai_studio"
    private const val KEY = "chat_sessions"

    fun load(context: Context): List<ChatSession> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val messageArray = item.optJSONArray("messages") ?: JSONArray()
                val parsed = (0 until messageArray.length()).mapNotNull { i ->
                    val message = messageArray.optJSONObject(i) ?: return@mapNotNull null
                    val role = message.optString("role")
                    val text = message.optString("content")
                    if (role !in listOf("user", "assistant") || text.isBlank()) null
                    else ChatMessage(role, text, message.optLong("id", i.toLong()))
                }
                ChatSession(
                    id = item.optLong("id"),
                    title = item.optString("title", "新对话"),
                    updatedAt = item.optLong("updatedAt"),
                    usedTokens = item.optInt("usedTokens"),
                    messages = parsed
                )
            }.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, sessions: List<ChatSession>) {
        val array = JSONArray()
        sessions.sortedByDescending { it.updatedAt }.take(50).forEach { session ->
            val messageArray = JSONArray()
            session.messages.takeLast(120).forEach { message ->
                messageArray.put(JSONObject().put("id", message.id).put("role", message.role).put("content", message.content))
            }
            array.put(JSONObject()
                .put("id", session.id)
                .put("title", session.title)
                .put("updatedAt", session.updatedAt)
                .put("usedTokens", session.usedTokens)
                .put("messages", messageArray))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
