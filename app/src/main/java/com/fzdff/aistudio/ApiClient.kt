package com.fzdff.aistudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Provider-neutral API layer.
 *
 * Supported protocol families:
 * - OpenAI Chat Completions compatible APIs (OpenAI, DeepSeek, Zhipu, OpenRouter,
 *   Ollama, LM Studio, vLLM and many compatible gateways)
 * - Anthropic Messages API
 * - Google Gemini generateContent / streamGenerateContent API
 */
object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private enum class Protocol { OPENAI, ANTHROPIC, GEMINI }

    private fun protocol(settings: AppSettings): Protocol =
        when (settings.apiProtocol.lowercase().trim()) {
            "anthropic", "claude", "messages" -> Protocol.ANTHROPIC
            "gemini", "google" -> Protocol.GEMINI
            else -> Protocol.OPENAI
        }

    private fun normalizedKey(value: String): String {
        val trimmed = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            trimmed.substringAfter(' ').trim()
        } else trimmed
    }

    private fun baseUrl(settings: AppSettings): String {
        val base = settings.endpoint.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "API 地址格式不正确：必须以 https:// 或 http:// 开头"
        }
        return base
    }

    private fun openAiChatUrl(settings: AppSettings): String {
        val base = baseUrl(settings)
        if (base.endsWith("/chat/completions", true)) return base
        return when {
            Regex("/v[0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(base) -> "$base/chat/completions"
            base.endsWith("/api/paas/v4", true) -> "$base/chat/completions"
            base.endsWith("/api/coding/paas/v4", true) -> "$base/chat/completions"
            base.endsWith("/openai", true) -> "$base/chat/completions"
            settings.provider.contains("DeepSeek", true) -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    private fun anthropicMessagesUrl(settings: AppSettings): String {
        val base = baseUrl(settings)
        if (base.endsWith("/v1/messages", true)) return base
        return if (base.endsWith("/v1", true)) "$base/messages" else "$base/v1/messages"
    }

    private fun geminiGenerateUrl(settings: AppSettings, streaming: Boolean): HttpUrl {
        val base = baseUrl(settings)
        val normalizedModel = settings.model.trim().removePrefix("models/")
        require(normalizedModel.isNotBlank()) { "模型 ID 不能为空" }
        val root = base.removeSuffix("/v1beta").removeSuffix("/v1")
        val version = if (base.endsWith("/v1", true)) "/v1" else "/v1beta"
        val method = if (streaming) "streamGenerateContent" else "generateContent"
        return "$root$version/models/${normalizedModel}:$method".toHttpUrl().newBuilder().apply {
            if (streaming) addQueryParameter("alt", "sse")
        }.build()
    }

    private fun errorMessage(code: Int, raw: String, fallback: String): String {
        var detail = raw.take(1000).ifBlank { fallback }
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
            // Keep plain-text reverse-proxy errors visible.
        }
        val suffix = if (providerCode.isNotBlank() && providerCode != code.toString()) "（服务商代码：$providerCode）" else ""
        return when (code) {
            400 -> "请求参数错误（HTTP 400）：$detail"
            401 -> "API 身份验证失败（HTTP 401）：$detail。请确认 API Key、鉴权方式和 API 地址属于同一个服务商产品。"
            403 -> "API 权限不足（HTTP 403）：$detail。请检查账户权限、模型权限、余额或 IP 白名单。"
            404 -> "接口或模型不存在（HTTP 404）：$detail。请检查协议类型、API 地址和模型 ID。"
            408 -> "API 请求超时（HTTP 408）：$detail"
            413 -> "请求内容过大（HTTP 413）：请缩短对话历史或减少附件。$detail"
            429 -> "请求过于频繁或额度不足（HTTP 429）：$detail。请检查 RPM、TPM、并发限制和账户额度。"
            500, 502, 503, 504 -> "服务商暂时不可用（HTTP $code）：$detail"
            else -> "API 请求失败（HTTP $code）$suffix：$detail"
        }
    }

    private fun authCandidates(settings: AppSettings, apiKey: String): List<String> {
        val raw = normalizedKey(apiKey)
        require(raw.isNotBlank()) { "请先填写 API Key / Token" }
        val candidates = mutableListOf(raw)

        // Zhipu documents both direct API-key and id.secret -> HS256 JWT authentication.
        if (settings.provider.contains("智谱") && protocol(settings) == Protocol.OPENAI) {
            val split = raw.indexOf('.')
            val secondDot = if (split >= 0) raw.indexOf('.', split + 1) else -1
            if (split in 1 until raw.lastIndex && secondDot == -1) {
                try {
                    val keyId = raw.substring(0, split)
                    val secret = raw.substring(split + 1)
                    val now = System.currentTimeMillis()
                    fun b64(bytes: ByteArray) =
                        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                    val header = b64("""{"alg":"HS256","sign_type":"SIGN"}""".toByteArray())
                    val payload = b64(
                        JSONObject().put("api_key", keyId)
                            .put("exp", now + 60L * 60L * 1000L)
                            .put("timestamp", now).toString().toByteArray()
                    )
                    val signed = "$header.$payload"
                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
                    candidates.add("$signed.${b64(mac.doFinal(signed.toByteArray()))}")
                } catch (_: Exception) {
                    // Continue with the original key if JWT construction isn't possible.
                }
            }
        }
        return candidates.distinct()
    }

    private fun messagesJson(messages: List<ChatMessage>): JSONArray =
        JSONArray().apply {
            messages.filter { it.content.isNotBlank() && it.role in listOf("system", "user", "assistant") }
                .takeLast(60)
                .forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }

    private fun requestBody(
        settings: AppSettings,
        messages: List<ChatMessage>,
        streaming: Boolean
    ): String {
        val all = messages.filter { it.content.isNotBlank() && it.role in listOf("system", "user", "assistant") }
            .takeLast(60)
        return when (protocol(settings)) {
            Protocol.OPENAI -> {
                val body = JSONObject()
                    .put("model", settings.model.trim())
                    .put("messages", messagesJson(all))
                    .put("stream", streaming)
                    .put("max_tokens", settings.maxTokens.coerceIn(1, 16384))
                if (settings.sendReasoning && settings.reasoning != "auto") {
                    if (settings.provider.contains("智谱")) {
                        body.put("thinking", JSONObject().put("type", "enabled"))
                    } else {
                        body.put("reasoning_effort", settings.reasoning)
                    }
                }
                body.toString()
            }
            Protocol.ANTHROPIC -> {
                val systemText = all.filter { it.role == "system" }.joinToString("\n") { it.content }
                val chat = JSONArray().apply {
                    all.filter { it.role == "user" || it.role == "assistant" }.forEach {
                        put(JSONObject()
                            .put("role", it.role)
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", it.content))))
                    }
                }
                JSONObject().apply {
                    put("model", settings.model.trim())
                    put("max_tokens", settings.maxTokens.coerceIn(1, 16384))
                    put("messages", chat)
                    put("stream", streaming)
                    if (systemText.isNotBlank()) put("system", systemText)
                    if (settings.sendReasoning && settings.reasoning != "auto") {
                        // The Messages API uses an explicit thinking budget, not reasoning_effort.
                        val budget = when (settings.reasoning) {
                            "low" -> 1024
                            "high" -> minOf(8192, settings.maxTokens / 2)
                            else -> 2048
                        }
                        if (settings.maxTokens > budget) {
                            put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
                        }
                    }
                }.toString()
            }
            Protocol.GEMINI -> {
                val systemText = all.filter { it.role == "system" }.joinToString("\n") { it.content }
                val contents = JSONArray().apply {
                    all.filter { it.role == "user" || it.role == "assistant" }.forEach { message ->
                        put(JSONObject()
                            .put("role", if (message.role == "assistant") "model" else "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", message.content))))
                    }
                }
                JSONObject().apply {
                    if (systemText.isNotBlank()) put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
                    )
                    put("contents", contents)
                    put("generationConfig", JSONObject().put("maxOutputTokens", settings.maxTokens.coerceIn(1, 16384)))
                }.toString()
            }
        }
    }

    private fun buildRequest(
        settings: AppSettings,
        token: String,
        body: String,
        streaming: Boolean
    ): Request {
        val builder: Request.Builder
        val url: String
        when (protocol(settings)) {
            Protocol.OPENAI -> {
                url = openAiChatUrl(settings)
                builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            }
            Protocol.ANTHROPIC -> {
                url = anthropicMessagesUrl(settings)
                builder = Request.Builder().url(url)
                    .header("x-api-key", token)
                    .header("anthropic-version", "2026-10-21")
            }
            Protocol.GEMINI -> {
                url = geminiGenerateUrl(settings, streaming).toString()
                builder = Request.Builder().url(url).header("x-goog-api-key", token)
            }
        }
        return builder
            .header("Content-Type", "application/json")
            .header("Accept", if (streaming) "text/event-stream, application/json" else "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun executeWithAuth(
        settings: AppSettings,
        apiKey: String,
        body: String,
        streaming: Boolean
    ): okhttp3.Response {
        val candidates = authCandidates(settings, apiKey)
        for ((index, token) in candidates.withIndex()) {
            val response = client.newCall(buildRequest(settings, token, body, streaming)).execute()
            if (response.code == 401 && index < candidates.lastIndex) {
                response.close()
                continue
            }
            return response
        }
        throw IllegalStateException("API 鉴权请求未能建立")
    }

    private fun modelsUrl(settings: AppSettings): HttpUrl {
        return when (protocol(settings)) {
            Protocol.OPENAI -> {
                val base = baseUrl(settings)
                val url = when {
                    base.endsWith("/models", true) -> base
                    base.endsWith("/chat/completions", true) -> base.substringBeforeLast("/chat/completions") + "/models"
                    Regex("/v[0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(base) -> "$base/models"
                    base.endsWith("/api/paas/v4", true) || base.endsWith("/api/coding/paas/v4", true) -> "$base/models"
                    base.endsWith("/openai", true) || settings.provider.contains("DeepSeek", true) -> "$base/models"
                    else -> "$base/v1/models"
                }
                url.toHttpUrl()
            }
            Protocol.ANTHROPIC -> {
                val base = baseUrl(settings)
                val url = when {
                    base.endsWith("/v1/models", true) -> base
                    base.endsWith("/v1", true) -> "$base/models"
                    else -> "$base/v1/models"
                }
                url.toHttpUrl().newBuilder().addQueryParameter("limit", "100").build()
            }
            Protocol.GEMINI -> {
                val base = baseUrl(settings)
                val root = base.removeSuffix("/v1beta").removeSuffix("/v1")
                val version = if (base.endsWith("/v1", true)) "/v1" else "/v1beta"
                "$root$version/models".toHttpUrl().newBuilder().addQueryParameter("pageSize", "100").build()
            }
        }
    }

    private fun modelsRequest(settings: AppSettings, token: String): Request {
        val builder = Request.Builder().url(modelsUrl(settings)).header("Accept", "application/json")
        when (protocol(settings)) {
            Protocol.OPENAI -> builder.header("Authorization", "Bearer $token")
            Protocol.ANTHROPIC -> builder.header("x-api-key", token).header("anthropic-version", "2026-10-21")
            Protocol.GEMINI -> builder.header("x-goog-api-key", token)
        }
        return builder.get().build()
    }

    suspend fun fetchModels(settings: AppSettings, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val candidates = authCandidates(settings, apiKey)
            var response: okhttp3.Response? = null
            for ((index, token) in candidates.withIndex()) {
                val current = client.newCall(modelsRequest(settings, token)).execute()
                if (current.code == 401 && index < candidates.lastIndex) {
                    current.close()
                    continue
                }
                response = current
                break
            }
            val finalResponse = response ?: throw IllegalStateException("模型列表请求未能建立")
            finalResponse.use {
                val raw = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw IllegalStateException(errorMessage(it.code, raw, it.message))
                val root = JSONObject(raw)
                val values = when (protocol(settings)) {
                    Protocol.OPENAI, Protocol.ANTHROPIC -> root.optJSONArray("data")
                        ?: root.optJSONArray("models")
                    Protocol.GEMINI -> root.optJSONArray("models")
                } ?: throw IllegalStateException("服务商没有提供标准模型列表接口。你可以手动输入模型 ID。")
                val result = (0 until values.length()).mapNotNull { i ->
                    val item = values.optJSONObject(i) ?: return@mapNotNull null
                    val id = when (protocol(settings)) {
                        Protocol.GEMINI -> item.optString("name").removePrefix("models/")
                        else -> item.optString("id")
                    }
                    id.takeIf { it.isNotBlank() && it != "null" }
                }.distinct().sorted()
                if (result.isEmpty()) throw IllegalStateException("接口返回空模型列表。你可以手动输入模型 ID。")
                result
            }
        }

    suspend fun testConnection(settings: AppSettings, apiKey: String): String =
        withContext(Dispatchers.IO) {
            if (settings.model.isNotBlank()) {
                complete(
                    settings.copy(maxTokens = 16, reasoning = "auto", sendReasoning = false),
                    apiKey,
                    "请只回复 OK"
                )
                "聊天接口连接成功"
            } else {
                val models = fetchModels(settings, apiKey)
                "连接成功 · 获取到 ${models.size} 个模型"
            }
        }

    private fun parseText(json: JSONObject, protocol: Protocol): String {
        return when (protocol) {
            Protocol.OPENAI -> {
                val choices = json.optJSONArray("choices")
                val message = choices?.optJSONObject(0)?.optJSONObject("message")
                when (val content = message?.opt("content")) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { i ->
                        content.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                    }.joinToString("")
                    else -> ""
                }
            }
            Protocol.ANTHROPIC -> {
                val blocks = json.optJSONArray("content") ?: JSONArray()
                (0 until blocks.length()).mapNotNull { i ->
                    blocks.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("")
            }
            Protocol.GEMINI -> {
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                (0 until parts.length()).mapNotNull { i ->
                    parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                }.joinToString("")
            }
        }
    }

    private fun parseStreamData(line: String, protocol: Protocol): String {
        val payload = line.removePrefix("data:").trim()
        if (payload.isBlank() || payload == "[DONE]") return ""
        val json = try { JSONObject(payload) } catch (_: Exception) { return "" }
        return when (protocol) {
            Protocol.OPENAI -> {
                val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                when {
                    delta?.opt("content") is String -> delta.optString("content")
                    delta?.optJSONArray("content") != null -> {
                        val parts = delta.optJSONArray("content")!!
                        (0 until parts.length()).mapNotNull { i ->
                            parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                        }.joinToString("")
                    }
                    else -> ""
                }
            }
            Protocol.ANTHROPIC -> {
                if (json.optString("type") == "content_block_delta") {
                    json.optJSONObject("delta")?.optString("text").orEmpty()
                } else ""
            }
            Protocol.GEMINI -> parseText(json, Protocol.GEMINI)
        }
    }

    suspend fun streamChat(
        settings: AppSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onChunk: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(settings.model.isNotBlank()) { "请先填写模型 ID" }
        val body = requestBody(settings, messages, true)
        executeWithAuth(settings, apiKey, body, true).use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                throw IllegalStateException(errorMessage(response.code, raw, response.message))
            }
            val source = response.body?.source() ?: throw IllegalStateException("API 返回空响应")
            var received = false
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                if (line.trim() == "data: [DONE]") break
                val text = parseStreamData(line, protocol(settings))
                if (text.isNotEmpty()) {
                    received = true
                    onChunk(text)
                }
            }
            if (!received) throw IllegalStateException(
                "连接成功，但服务商没有返回文本。请检查协议类型、模型 ID、API 权限和服务商的流式接口支持情况。"
            )
        }
    }

    suspend fun complete(settings: AppSettings, apiKey: String, prompt: String): String =
        completeMessages(settings, apiKey, listOf(ChatMessage("user", prompt)))

    suspend fun completeMessages(
        settings: AppSettings,
        apiKey: String,
        messages: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        require(settings.model.isNotBlank()) { "请先填写模型 ID" }
        val body = requestBody(settings, messages, false)
        executeWithAuth(settings, apiKey, body, false).use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(errorMessage(response.code, raw, response.message))
            val result = parseText(JSONObject(raw), protocol(settings))
            result.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("请求成功，但模型没有返回文本。请确认所选协议与模型对应。")
        }
    }
}
