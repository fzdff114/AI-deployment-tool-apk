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
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun streamChat(
        settings: AppSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onChunk: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先填写 API Key" }
        val endpoint = settings.endpoint.trim().trimEnd('/')
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) { "API 地址必须以 http:// 或 https:// 开头" }
        val body = JSONObject()
            .put("model", settings.model.trim())
            .put("messages", JSONArray().apply {
                messages.takeLast(40).forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
            .put("stream", true)
            .put("max_tokens", settings.maxTokens)
        if (settings.sendReasoning && settings.reasoning != "auto") {
            body.put("reasoning_effort", settings.reasoning)
        }

        val request = Request.Builder()
            .url("$endpoint/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorText = response.body?.string().orEmpty()
                val message = try {
                    JSONObject(errorText).optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() } ?: errorText.take(600)
                } catch (_: Exception) { errorText.take(600) }
                throw IllegalStateException("API 请求失败（HTTP ${response.code}）：${message.ifBlank { response.message }}")
            }

            val source = response.body?.source() ?: throw IllegalStateException("API 返回了空响应")
            var sawEvent = false
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank()) continue
                if (payload == "[DONE]") break
                val json = try { JSONObject(payload) } catch (_: Exception) { continue }
                val delta = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta") ?: continue
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    sawEvent = true
                    onChunk(content)
                }
            }
            if (!sawEvent) throw IllegalStateException("连接成功，但服务没有返回文本内容。请检查模型 ID、API 兼容性和额度设置。")
        }
    }

    suspend fun complete(
        settings: AppSettings,
        apiKey: String,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        val endpoint = settings.endpoint.trim().trimEnd('/')
        val body = JSONObject()
            .put("model", settings.model.trim())
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("stream", false)
            .put("max_tokens", settings.maxTokens)
        if (settings.sendReasoning && settings.reasoning != "auto") body.put("reasoning_effort", settings.reasoning)
        val request = Request.Builder().url("$endpoint/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("API 请求失败（HTTP ${response.code}）：${raw.take(600)}")
            JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("模型没有返回内容")
        }
    }
}
