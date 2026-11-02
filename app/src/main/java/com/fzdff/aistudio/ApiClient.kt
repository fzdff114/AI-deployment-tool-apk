package com.fzdff.aistudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    private fun normalizedKey(value: String): String =
        value.trim().replace(Regex("^(?i)Bearer\\s+"), "").trim()

    private fun chatUrl(settings: AppSettings): String {
        val base = settings.endpoint.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "API 地址格式不正确：请填写 https:// 或 http:// 开头的 API 基础地址"
        }
        require(settings.model.isNotBlank()) { "模型 ID 不能为空" }
        if (base.endsWith("/chat/completions", ignoreCase = true)) return base
        val provider = settings.provider.lowercase()
        return when {
            base.endsWith("/v1", ignoreCase = true) -> "$base/chat/completions"
            provider.contains("deepseek") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun errorMessage(code: Int, raw: String, fallback: String): String {
        val detail = try {
            val root = JSONObject(raw)
            root.optJSONObject("error")?.let { e ->
                e.optString("message").ifBlank { e.optString("detail") }
            }?.takeIf { it.isNotBlank() }
                ?: root.optString("message").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null } ?: raw.take(800).ifBlank { fallback }
        return when (code) {
            401 -> "API Key / Token 无效或未获授权（HTTP 401）。请确认密钥属于当前服务商、没有多余空格，并检查是否误填了访问令牌。服务端信息：$detail"
            403 -> "API 拒绝访问（HTTP 403）。请检查密钥权限、模型访问权限或账户余额。服务端信息：$detail"
            404 -> "接口或模型不存在（HTTP 404）。请检查 API 地址是否为兼容接口基础地址，以及模型 ID 是否准确。服务端信息：$detail"
            429 -> "请求过于频繁或额度不足（HTTP 429）。请检查服务商限额和余额。服务端信息：$detail"
            else -> "API 请求失败（HTTP $code）：$detail"
        }
    }

    private fun requestBody(settings: AppSettings, messages: JSONArray, stream: Boolean): String {
        val body = JSONObject()
            .put("model", settings.model.trim())
            .put("messages", messages)
            .put("stream", stream)
            .put("max_tokens", settings.maxTokens.coerceIn(1, 8192))
        if (settings.sendReasoning && settings.reasoning != "auto") {
            body.put("reasoning_effort", settings.reasoning)
        }
        return body.toString()
    }

    private fun request(settings: AppSettings, apiKey: String, body: String): Request {
        val key = normalizedKey(apiKey)
        require(key.isNotBlank()) { "请先填写 API Key / Token" }
        return Request.Builder()
            .url(chatUrl(settings))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    suspend fun streamChat(
        settings: AppSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onChunk: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val payload = JSONArray().apply {
            messages.filter { it.content.isNotBlank() && it.role in listOf("user", "assistant", "system") }
                .takeLast(40)
                .forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }
        val req = request(settings, apiKey, requestBody(settings, payload, true))
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                throw IllegalStateException(errorMessage(response.code, raw, response.message))
            }
            val source = response.body?.source() ?: throw IllegalStateException("API 返回了空响应")
            var received = false
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank()) continue
                if (data == "[DONE]") break
                val json = try { JSONObject(data) } catch (_: Exception) { continue }
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta")
                val content = when {
                    delta?.opt("content") is String -> delta.optString("content")
                    delta?.optJSONArray("content") != null -> delta.optJSONArray("content")!!.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                        }.joinToString("")
                    }
                    else -> ""
                }
                if (content.isNotEmpty()) {
                    received = true
                    onChunk(content)
                }
                if (choice.optString("finish_reason") == "length" && !received) {
                    throw IllegalStateException("模型只返回了推理或非文本内容。请关闭 reasoning_effort，或换用支持文本聊天的模型。")
                }
            }
            if (!received) throw IllegalStateException("服务器已连接，但没有返回可显示的文本。请检查模型 ID、服务商的 OpenAI 兼容接口，以及模型是否支持 /chat/completions。")
        }
    }

    suspend fun complete(settings: AppSettings, apiKey: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
            val req = request(settings, apiKey, requestBody(settings, messages, false))
            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(errorMessage(response.code, raw, response.message))
                val root = JSONObject(raw)
                val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val content = message?.opt("content")
                val result = when (content) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { i ->
                        content.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                    }.joinToString("")
                    else -> ""
                }
                result.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("请求成功，但模型没有返回文本。请检查模型 ID 和服务商接口兼容性。")
            }
        }
}
