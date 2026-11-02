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
        value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace(Regex("^(?i)Bearer\\s+"), "")
            .trim()

    private fun chatUrl(settings: AppSettings): String {
        val base = settings.endpoint.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "API 地址格式不正确：请填写 https:// 或 http:// 开头的 API 基础地址"
        }
        require(settings.model.isNotBlank()) { "模型 ID 不能为空" }

        if (base.endsWith("/chat/completions", ignoreCase = true)) return base

        val provider = settings.provider.lowercase()
        return when {
            // Zhipu's official OpenAI-compatible API uses /api/paas/v4/chat/completions.
            base.endsWith("/api/paas/v4", ignoreCase = true) -> "$base/chat/completions"
            // OpenAI-style versioned base URLs already include their API version.
            Regex("/v[0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(base) -> "$base/chat/completions"
            // DeepSeek's default base URL does not include /v1.
            provider.contains("deepseek") -> "$base/chat/completions"
            // Some custom gateways expose the OpenAI-compatible route directly under /openai.
            base.endsWith("/openai", ignoreCase = true) -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun errorMessage(code: Int, raw: String, fallback: String): String {
        var detail = raw.take(800).ifBlank { fallback }
        var providerCode = ""
        try {
            val root = JSONObject(raw)
            providerCode = root.optString("code")
            val nested = root.optJSONObject("error")
            detail = sequenceOf(
                nested?.optString("message"),
                root.optString("message"),
                nested?.optString("detail"),
                root.optString("detail")
            ).firstOrNull { !it.isNullOrBlank() && it != "null" } ?: detail
        } catch (_: Exception) {
            // Preserve non-JSON gateway error text.
        }

        val codeSuffix = if (providerCode.isNotBlank() && providerCode != code.toString()) "（服务商代码：$providerCode）" else ""
        return when (code) {
            400 -> "请求参数错误（HTTP 400）：$detail"
            401 -> "API Key 验证失败（HTTP 401）：$detail。请在对应服务商控制台重新复制完整 API Key；不要使用网页登录令牌、其他平台的 Key 或只复制密钥的一部分。"
            403 -> "API 权限不足（HTTP 403）：$detail。请检查模型权限、账户余额和 IP 白名单。"
            404 -> "接口或模型不存在（HTTP 404）：$detail。请检查 API 基础地址和模型 ID。"
            429 -> "请求过于频繁或额度不足（HTTP 429）：$detail。请检查并发限制、账户额度和速率限制。"
            else -> "API 请求失败（HTTP $code）$codeSuffix：$detail"
        }
    }

    private fun requestBody(settings: AppSettings, messages: JSONArray, stream: Boolean): String {
        val body = JSONObject()
            .put("model", settings.model.trim())
            .put("messages", messages)
            .put("stream", stream)
            .put("max_tokens", settings.maxTokens.coerceIn(1, 8192))

        if (settings.sendReasoning && settings.reasoning != "auto") {
            if (settings.provider.contains("智谱")) {
                // BigModel v4 expects its own thinking object, not OpenAI reasoning_effort.
                body.put("thinking", JSONObject().put("type", "enabled"))
            } else {
                body.put("reasoning_effort", settings.reasoning)
            }
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
            }
            if (!received) throw IllegalStateException(
                "服务器已连接，但没有返回可显示的文本。请检查模型 ID、模型是否支持 /chat/completions，以及是否需要关闭推理模式。"
            )
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
