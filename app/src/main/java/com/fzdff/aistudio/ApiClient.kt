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
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    private fun normalizedKey(value: String): String {
        val trimmed = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            trimmed.substringAfter(' ').trim()
        } else trimmed
    }

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
            401 -> "服务端拒绝了 API 身份验证（HTTP 401）：$detail。本应用已先尝试原始 API Key；对于 id.secret 格式，也自动尝试了智谱文档提供的 JWT 鉴权。此错误不是模型名称导致的。请核对密钥是否属于「智谱标准 API Keys」而不是「GLM Coding Plan」：两类 Key 分开管理、不能混用；当前地址是标准 API 的 /api/paas/v4。还需检查 Key 是否被禁用或复制不完整。"
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

    private fun authCandidates(apiKey: String): List<String> {
        val raw = normalizedKey(apiKey)
        if (raw.isBlank()) throw IllegalArgumentException("请先填写 API Key / Token")

        val candidates = mutableListOf(raw)
        val split = raw.indexOf('.')
        val secondDot = if (split >= 0) raw.indexOf('.', split + 1) else -1
        if (split in 1 until raw.lastIndex && secondDot == -1) {
            val keyId = raw.substring(0, split)
            val secret = raw.substring(split + 1)
            try {
                // BigModel supports both the direct API Key and its documented HS256 JWT form.
                // Retry with JWT only if the direct API Key gets a 401.
                val now = System.currentTimeMillis()
                fun b64(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
                val header = b64("""{"alg":"HS256","sign_type":"SIGN"}""".toByteArray(Charsets.UTF_8))
                val payload = b64(
                    JSONObject()
                        .put("api_key", keyId)
                        .put("exp", now + 60L * 60L * 1000L)
                        .put("timestamp", now)
                        .toString().toByteArray(Charsets.UTF_8)
                )
                val signingInput = "$header.$payload"
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                val signature = b64(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8)))
                candidates.add("$signingInput.$signature")
            } catch (_: Exception) {
                // Keep direct-key authentication available even if JWT creation fails.
            }
        }
        return candidates.distinct()
    }

    private fun request(settings: AppSettings, bearerToken: String, body: String): Request {
        return Request.Builder()
            .url(chatUrl(settings))
            .header("Authorization", "Bearer $bearerToken")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun executeAuthenticated(settings: AppSettings, apiKey: String, body: String): okhttp3.Response {
        val candidates = authCandidates(apiKey)
        for ((index, token) in candidates.withIndex()) {
            val response = client.newCall(request(settings, token, body)).execute()
            if (response.code == 401 && index < candidates.lastIndex) {
                // The official API accepts either a direct key or JWT. Try the alternate
                // documented form once for id.secret keys before reporting an auth failure.
                response.close()
                continue
            }
            return response
        }
        throw IllegalStateException("API 鉴权请求未能建立")
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
        val payloadBody = requestBody(settings, payload, true)
        executeAuthenticated(settings, apiKey, payloadBody).use { response ->
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
            val payloadBody = requestBody(settings, messages, false)
            executeAuthenticated(settings, apiKey, payloadBody).use { response ->
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
