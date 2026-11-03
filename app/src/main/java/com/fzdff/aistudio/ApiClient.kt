package com.fzdff.aistudio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ApiClient {
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun normalizedKey(value: String): String {
        val trimmed = value.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        return if (trimmed.startsWith("Bearer ", true)) trimmed.substringAfter(' ').trim() else trimmed
    }

    private fun baseUrl(settings: AppSettings): String {
        val base = settings.endpoint.trim().trimEnd('/')
        require(base.startsWith("https://", true) || base.startsWith("http://", true)) {
            "API 地址格式不正确：请填写完整的 http:// 或 https:// 地址"
        }
        return base
    }

    // Preserve gateway query parameters (for example Azure api-version)
    // when joining the endpoint path.
    private fun appendPathPreservingQuery(base: String, suffix: String): String {
        val url = base.toHttpUrl()
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + suffix).build().toString()
    }

    private fun replacePathPreservingQuery(base: String, path: String): String {
        return base.toHttpUrl().newBuilder().encodedPath(path).build().toString()
    }

    private fun basePath(base: String): String = base.toHttpUrl().encodedPath.trimEnd('/')

    private fun chatUrl(settings: AppSettings, stream: Boolean = false): String {
        val base = baseUrl(settings)
        val path = basePath(base)
        val model = settings.model.trim()
        require(model.isNotBlank()) { "请先填写模型 ID" }
        return when (settings.apiProtocol) {
            "openai_responses" -> when {
                path.endsWith("/responses", true) -> base
                Regex("/v[0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
                    appendPathPreservingQuery(base, "/responses")
                else -> appendPathPreservingQuery(base, "/v1/responses")
            }
            "anthropic_messages" -> when {
                path.endsWith("/messages", true) -> base
                path.endsWith("/v1", true) -> appendPathPreservingQuery(base, "/messages")
                else -> appendPathPreservingQuery(base, "/v1/messages")
            }
            "gemini_generate" -> when {
                path.endsWith(":streamGenerateContent", true) -> {
                    if (stream) base else replacePathPreservingQuery(
                        base, path.removeSuffix(":streamGenerateContent") + ":generateContent"
                    )
                }
                path.endsWith(":generateContent", true) -> {
                    if (!stream) base else replacePathPreservingQuery(
                        base, path.removeSuffix(":generateContent") + ":streamGenerateContent"
                    ).toHttpUrl().newBuilder().setQueryParameter("alt", "sse").build().toString()
                }
                else -> {
                    val safeModel = URLEncoder.encode(model.removePrefix("models/"), "UTF-8").replace("+", "%20")
                    val method = if (stream) ":streamGenerateContent" else ":generateContent"
                    val url = appendPathPreservingQuery(base, "/models/$safeModel$method")
                    if (stream) url.toHttpUrl().newBuilder().setQueryParameter("alt", "sse").build().toString() else url
                }
            }
            else -> when {
                path.endsWith("/chat/completions", true) -> base
                path.contains("/openai/deployments/", true) ->
                    appendPathPreservingQuery(base, "/chat/completions")
                Regex("/v[0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
                    appendPathPreservingQuery(base, "/chat/completions")
                path.endsWith("/api/paas/v4", true) || path.endsWith("/api/coding/paas/v4", true) ->
                    appendPathPreservingQuery(base, "/chat/completions")
                path.endsWith("/openai", true) || settings.provider.contains("DeepSeek", true) ->
                    appendPathPreservingQuery(base, "/chat/completions")
                else -> appendPathPreservingQuery(base, "/v1/chat/completions")
            }
        }
    }

    private fun modelsUrl(settings: AppSettings): String {
        val base = baseUrl(settings)
        val path = basePath(base)
        return when (settings.apiProtocol) {
            "anthropic_messages" -> when {
                path.endsWith("/v1/models", true) -> base
                path.endsWith("/v1", true) -> appendPathPreservingQuery(base, "/models")
                else -> appendPathPreservingQuery(base, "/v1/models")
            }
            "gemini_generate" -> if (path.endsWith("/models", true)) base else appendPathPreservingQuery(base, "/models")
            else -> when {
                path.endsWith("/models", true) -> base
                path.endsWith("/chat/completions", true) ->
                    replacePathPreservingQuery(base, path.substringBeforeLast("/chat/completions") + "/models")
                path.endsWith("/responses", true) ->
                    replacePathPreservingQuery(base, path.substringBeforeLast("/responses") + "/models")
                path.contains("/openai/deployments/", true) ->
                    throw IllegalStateException("Azure OpenAI 部署端点不提供通用 GET /models。请在模型 ID 中填写部署名称，然后直接测试聊天。")
                Regex("/v[0-9]+$", RegexOption.IGNORE_CASE).containsMatchIn(path) ->
                    appendPathPreservingQuery(base, "/models")
                path.endsWith("/api/paas/v4", true) || path.endsWith("/api/coding/paas/v4", true) ->
                    appendPathPreservingQuery(base, "/models")
                path.endsWith("/openai", true) || settings.provider.contains("DeepSeek", true) ->
                    appendPathPreservingQuery(base, "/models")
                else -> appendPathPreservingQuery(base, "/v1/models")
            }
        }
    }

    private fun errorMessage(code: Int, raw: String, fallback: String): String {
        var detail = raw.take(1000).ifBlank { fallback }
        var providerCode = ""
        try {
            val root = JSONObject(raw)
            providerCode = root.optString("code")
            val nested = root.optJSONObject("error")
            detail = sequenceOf(
                nested?.optString("message"), nested?.optString("detail"),
                root.optString("message"), root.optString("detail")
            ).firstOrNull { !it.isNullOrBlank() && it != "null" } ?: detail
        } catch (_: Exception) { }
        val suffix = if (providerCode.isNotBlank() && providerCode != code.toString()) "（服务商代码：" + providerCode + "）" else ""
        return when (code) {
            400 -> "请求参数错误（HTTP 400）：" + detail
            401 -> "身份验证失败（HTTP 401）：" + detail + "。请检查 API Key、认证请求头和接口地址是否属于同一服务商产品。"
            402 -> "账户余额或套餐额度不足（HTTP 402）：" + detail
            403 -> "API 权限不足（HTTP 403）：" + detail + "。请检查模型权限、套餐、余额或 IP 白名单。"
            404 -> "接口路径或模型不存在（HTTP 404）：" + detail + "。请检查基础地址、API 协议和模型 ID。"
            408 -> "服务端处理超时（HTTP 408）：" + detail
            413 -> "请求内容过大：请缩短历史对话或降低上下文长度。" + detail
            415 -> "服务端不接受当前请求格式（HTTP 415）：" + detail
            422 -> "模型或请求参数未通过校验（HTTP 422）：" + detail
            429 -> "请求过于频繁或额度不足（HTTP 429）：" + detail + "。请检查速率限制、并发数和套餐额度。"
            in 500..599 -> "AI 服务商服务器异常（HTTP " + code + "）" + suffix + "：" + detail
            else -> "API 请求失败（HTTP " + code + "）" + suffix + "：" + detail
        }
    }

    private fun authHeader(settings: AppSettings): Pair<String, String>? {
        val mode = settings.authHeaderName.trim()
        val name = when {
            mode.equals("none", true) -> return null
            mode.isNotBlank() && !mode.equals("auto", true) -> mode
            settings.apiProtocol == "anthropic_messages" -> "x-api-key"
            settings.apiProtocol == "gemini_generate" -> "x-goog-api-key"
            settings.provider.contains("Ollama", true) -> return null
            else -> "Authorization"
        }
        val prefix = if (mode.equals("auto", true) || mode.isBlank()) {
            if (name.equals("Authorization", true)) "Bearer" else ""
        } else settings.authHeaderPrefix.trim()
        return name to prefix
    }

    private fun authCandidates(settings: AppSettings, apiKey: String): List<String> {
        if (authHeader(settings) == null) return listOf("")
        val raw = normalizedKey(apiKey)
        require(raw.isNotBlank()) { "当前认证方式需要 API Key，请先填写密钥；本地无认证服务可将认证请求头设为 none" }
        val candidates = mutableListOf(raw)
        val isZhipu = settings.endpoint.contains("open.bigmodel.cn", true) || settings.provider.contains("智谱")
        val split = raw.indexOf('.')
        val secondDot = if (split >= 0) raw.indexOf('.', split + 1) else -1
        if (isZhipu && split in 1 until raw.lastIndex && secondDot == -1) {
            val keyId = raw.substring(0, split)
            val secret = raw.substring(split + 1)
            try {
                val now = System.currentTimeMillis()
                fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                val header = b64("""{"alg":"HS256","sign_type":"SIGN"}""".toByteArray(Charsets.UTF_8))
                val payload = b64(
                    JSONObject().put("api_key", keyId)
                        .put("exp", now + 60L * 60L * 1000L).put("timestamp", now)
                        .toString().toByteArray(Charsets.UTF_8)
                )
                val signingInput = "$header.$payload"
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                candidates.add(signingInput + "." + b64(mac.doFinal(signingInput.toByteArray(Charsets.UTF_8))))
            } catch (_: Exception) { }
        }
        return candidates.distinct()
    }

    private fun applyHeaders(builder: Request.Builder, settings: AppSettings, token: String): Request.Builder {
        val auth = authHeader(settings)
        if (auth != null && token.isNotBlank()) {
            val value = if (auth.second.isBlank()) token else auth.second + " " + token
            builder.header(auth.first, value)
        }
        if (settings.apiProtocol == "anthropic_messages") builder.header("anthropic-version", ANTHROPIC_VERSION)
        builder.header("Accept", "text/event-stream, application/json")
        return builder
    }

    private fun textMessages(messages: List<ChatMessage>): JSONArray =
        JSONArray().apply {
            messages.filter { it.content.isNotBlank() && it.role in listOf("user", "assistant", "system") }
                .takeLast(60)
                .forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
        }

    private fun requestJson(settings: AppSettings, messages: List<ChatMessage>, stream: Boolean): JSONObject {
        val model = settings.model.trim()
        require(model.isNotBlank()) { "请先填写模型 ID" }
        val limit = settings.maxTokens.coerceIn(1, 8192)
        val source = textMessages(messages)
        return when (settings.apiProtocol) {
            "openai_responses" -> JSONObject().put("model", model).put("input", source)
                .put("max_output_tokens", limit).put("stream", stream).apply {
                    if (settings.sendReasoning && settings.reasoning != "auto") {
                        put("reasoning", JSONObject().put("effort", settings.reasoning))
                    }
                }
            "anthropic_messages" -> {
                val system = (0 until source.length()).mapNotNull { i ->
                    val item = source.optJSONObject(i) ?: return@mapNotNull null
                    if (item.optString("role") == "system") item.optString("content") else null
                }.joinToString("\n")
                val userAssistant = JSONArray().apply {
                    for (i in 0 until source.length()) {
                        val item = source.optJSONObject(i) ?: continue
                        if (item.optString("role") in listOf("user", "assistant")) put(item)
                    }
                }
                JSONObject().put("model", model).put("max_tokens", limit)
                    .put("messages", userAssistant).put("stream", stream)
                    .apply {
                        if (system.isNotBlank()) put("system", system)
                        if (settings.sendReasoning && settings.reasoning != "auto") {
                            val normalized = model.lowercase()
                            val adaptiveSupported = normalized.contains("4.6") ||
                                normalized.contains("4-6") || normalized.contains("4.7") ||
                                normalized.contains("4-7") || normalized.contains("5")
                            val legacyThinkingSupported = normalized.contains("4.5") ||
                                normalized.contains("4-5")
                            when {
                                adaptiveSupported -> {
                                    put("thinking", JSONObject().put("type", "adaptive"))
                                    put("output_config", JSONObject().put("effort", settings.reasoning))
                                }
                                legacyThinkingSupported && limit > 1024 -> {
                                    val requestedBudget = when (settings.reasoning) {
                                        "low" -> 1024
                                        "high" -> 4096
                                        else -> 2048
                                    }
                                    val budget = minOf(requestedBudget, limit - 1)
                                    if (budget >= 1024) {
                                        put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
                                    }
                                }
                            }
                        }
                    }
            }
            "gemini_generate" -> {
                val systemParts = JSONArray()
                val contents = JSONArray()
                for (i in 0 until source.length()) {
                    val item = source.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val part = JSONArray().put(JSONObject().put("text", item.optString("content")))
                    if (role == "system") systemParts.put(JSONObject().put("text", item.optString("content")))
                    else contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user").put("parts", part))
                }
                val generationConfig = JSONObject().put("maxOutputTokens", limit)
                if (settings.sendReasoning && settings.reasoning != "auto") {
                    if (model.contains("2.5", ignoreCase = true)) {
                        val requestedBudget = when (settings.reasoning) {
                            "low" -> 1024
                            "high" -> 4096
                            else -> 2048
                        }
                        val budget = minOf(requestedBudget, limit - 1)
                        if (budget >= 1024) {
                            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
                        }
                    } else {
                        generationConfig.put(
                            "thinkingConfig",
                            JSONObject().put("thinkingLevel", settings.reasoning.uppercase())
                        )
                    }
                }
                JSONObject().put("contents", contents)
                    .put("generationConfig", generationConfig)
                    .apply {
                        if (systemParts.length() > 0) put("systemInstruction", JSONObject().put("parts", systemParts))
                    }
            }
            else -> JSONObject().put("model", model).put("messages", source)
                .put("stream", stream).apply {
                    if (settings.provider.contains("Cerebras", ignoreCase = true)) {
                        put("max_completion_tokens", limit)
                    } else {
                        put("max_tokens", limit)
                    }
                    if (settings.sendReasoning && settings.reasoning != "auto") {
                        when {
                            settings.provider.contains("智谱") -> put("thinking", JSONObject().put("type", "enabled"))
                            settings.provider.contains("Cohere", ignoreCase = true) ->
                                put("reasoning_effort", if (settings.reasoning == "high") "high" else "none")
                            else -> put("reasoning_effort", settings.reasoning)
                        }
                    }
                }
        }
    }

    private fun buildRequest(settings: AppSettings, token: String, messages: List<ChatMessage>, stream: Boolean): Request {
        val body = requestJson(settings, messages, stream).toString()
        val request = Request.Builder().url(chatUrl(settings, stream))
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return applyHeaders(request, settings, token).build()
    }

    private fun buildModelsRequest(settings: AppSettings, token: String): Request {
        return applyHeaders(Request.Builder().url(modelsUrl(settings)).get(), settings, token).build()
    }

    private fun executeAuthenticated(settings: AppSettings, apiKey: String, messages: List<ChatMessage>, stream: Boolean): Response {
        val candidates = authCandidates(settings, apiKey)
        for ((index, token) in candidates.withIndex()) {
            val response = client.newCall(buildRequest(settings, token, messages, stream)).execute()
            if (response.code == 401 && index < candidates.lastIndex) {
                response.close()
                continue
            }
            return response
        }
        throw IllegalStateException("API 请求未能建立")
    }

    private fun extractText(item: JSONObject?, key: String = "text"): String {
        val value = item?.opt(key)
        return if (value is String && value.isNotBlank()) value else ""
    }

    private fun parseResponseText(protocol: String, raw: String): String {
        val root = JSONObject(raw)
        return when (protocol) {
            "anthropic_messages" -> {
                val content = root.optJSONArray("content") ?: JSONArray()
                (0 until content.length()).mapNotNull { i ->
                    val item = content.optJSONObject(i) ?: return@mapNotNull null
                    if (item.optString("type") == "text") extractText(item) else null
                }.joinToString("")
            }
            "gemini_generate" -> {
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                (0 until parts.length()).mapNotNull { i -> extractText(parts.optJSONObject(i)) }.joinToString("")
            }
            "openai_responses" -> {
                val direct = root.optString("output_text")
                if (direct.isNotBlank() && direct != "null") direct else {
                    val output = root.optJSONArray("output") ?: JSONArray()
                    buildString {
                        for (i in 0 until output.length()) {
                            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                val item = content.optJSONObject(j) ?: continue
                                if (item.optString("type") == "output_text") append(item.optString("text"))
                            }
                        }
                    }
                }
            }
            else -> {
                val content = root.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.opt("content")
                when (content) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { i ->
                        extractText(content.optJSONObject(i))
                    }.joinToString("")
                    else -> ""
                }
            }
        }
    }

    private fun sseChunk(protocol: String, eventName: String, raw: String): String {
        if (raw == "[DONE]" || raw.isBlank()) return ""
        val json = try { JSONObject(raw) } catch (_: Exception) { return "" }
        // Several gateways report provider-side failures as an SSE event while
        // keeping the HTTP status at 200. Surface that real error instead of
        // ending with the misleading "no displayable text" message.
        if (eventName.equals("error", true) || json.has("error")) {
            val error = json.optJSONObject("error")
            val detail = sequenceOf(
                error?.optString("message"),
                json.optString("message"),
                error?.optString("type")
            ).firstOrNull { !it.isNullOrBlank() && it != "null" }
                ?: raw.take(500)
            throw IllegalStateException("服务商流式接口返回错误：$detail")
        }
        return when (protocol) {
            "anthropic_messages" -> {
                val type = json.optString("type").ifBlank { eventName }
                if (type == "content_block_delta") {
                    val delta = json.optJSONObject("delta")
                    if (delta?.optString("type") == "text_delta") delta.optString("text") else ""
                } else ""
            }
            "gemini_generate" -> {
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                (0 until parts.length()).mapNotNull { i -> extractText(parts.optJSONObject(i)) }.joinToString("")
            }
            "openai_responses" -> {
                val type = json.optString("type").ifBlank { eventName }
                if (type == "response.output_text.delta") json.optString("delta") else ""
            }
            else -> {
                val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                when (val content = delta?.opt("content")) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { i -> extractText(content.optJSONObject(i)) }.joinToString("")
                    else -> ""
                }
            }
        }
    }

    private suspend fun readStream(response: Response, protocol: String, onChunk: suspend (String) -> Unit): Boolean {
        val body = response.body ?: throw IllegalStateException("API 返回了空响应")
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/event-stream", true)) {
            val text = try { parseResponseText(protocol, body.string()) } catch (_: Exception) { "" }
            if (text.isNotBlank()) onChunk(text)
            return text.isNotBlank()
        }
        val source = body.source()
        var eventName = ""
        val dataLines = mutableListOf<String>()
        var received = false
        suspend fun flushEvent() {
            if (dataLines.isEmpty()) {
                eventName = ""
                return
            }
            val chunk = sseChunk(protocol, eventName, dataLines.joinToString("\n"))
            if (chunk.isNotEmpty()) {
                received = true
                onChunk(chunk)
            }
            dataLines.clear()
            eventName = ""
        }
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> flushEvent()
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> eventName = line.substringAfter("event:").trim()
                line.startsWith("data:") -> dataLines.add(line.substringAfter("data:").trimStart())
            }
        }
        flushEvent()
        return received
    }

    suspend fun streamChat(
        settings: AppSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onChunk: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        executeAuthenticated(settings, apiKey, messages.filter { it.content.isNotBlank() }.takeLast(60), true).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessage(response.code, response.body?.string().orEmpty(), response.message))
            }
            if (!readStream(response, settings.apiProtocol, onChunk)) {
                throw IllegalStateException("服务器已连接，但没有返回可显示的文本。请检查 API 协议、模型 ID，以及服务商是否支持流式输出。")
            }
        }
    }

    suspend fun complete(settings: AppSettings, apiKey: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            executeAuthenticated(settings, apiKey, listOf(ChatMessage("user", prompt)), false).use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(errorMessage(response.code, raw, response.message))
                parseResponseText(settings.apiProtocol, raw).takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("请求成功，但模型未返回可显示的文本。请检查 API 协议和模型能力。")
            }
        }

    suspend fun fetchModels(settings: AppSettings, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val candidates = authCandidates(settings, apiKey)
            var finalResponse: Response? = null
            for ((index, token) in candidates.withIndex()) {
                val response = client.newCall(buildModelsRequest(settings, token)).execute()
                if (response.code == 401 && index < candidates.lastIndex) {
                    response.close()
                    continue
                }
                finalResponse = response
                break
            }
            val response = finalResponse ?: throw IllegalStateException("模型列表请求未能建立")
            response.use {
                val raw = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw IllegalStateException(errorMessage(it.code, raw, it.message))
                val root = JSONObject(raw)
                val values = when (settings.apiProtocol) {
                    "anthropic_messages" -> root.optJSONArray("data")
                    "gemini_generate" -> root.optJSONArray("models")
                    else -> root.optJSONArray("data") ?: root.optJSONObject("data")?.optJSONArray("models")
                } ?: throw IllegalStateException("服务商没有公开标准模型列表接口。请直接手动填写模型 ID。")
                val ids = (0 until values.length()).mapNotNull { i ->
                    val item = values.optJSONObject(i) ?: return@mapNotNull null
                    val rawId = if (settings.apiProtocol == "gemini_generate") item.optString("name") else item.optString("id")
                    rawId.takeIf { it.isNotBlank() && it != "null" }?.removePrefix("models/")
                }.distinct().sorted()
                if (ids.isEmpty()) throw IllegalStateException("服务商返回了空模型列表。你可以手动填写模型 ID。")
                ids
            }
        }
}
