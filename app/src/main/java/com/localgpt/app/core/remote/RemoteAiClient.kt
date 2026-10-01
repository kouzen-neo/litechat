package com.localgpt.app.core.remote

import com.localgpt.app.util.KLog
import com.localgpt.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

data class RemoteModelItem(
    val id: String,
    val name: String,
    val provider: String = "Remote",
    val description: String = "",
)

/**
 * A single chat message for a remote OpenAI-compatible request.
 *
 * [imageDataUrls] holds `data:image/...;base64,...` URLs for vision-capable
 * models. They are sent as OpenAI-style `image_url` content parts; when empty
 * the message is sent as a plain text `content` string.
 */
data class RemoteChatMessage(
    val role: String,
    val text: String,
    val imageDataUrls: List<String> = emptyList(),
)

/**
 * High-performance client for remote OpenAI-compatible backends (Ollama LAN, Groq, OpenRouter, DeepSeek).
 * Supports model discovery via GET /v1/models and SSE streaming token generation via POST /v1/chat/completions.
 */
object RemoteAiClient {
    private val client = NetworkUtils.HttpClient.streamingClient

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Max chars buffered for a single SSE line; overlong lines are truncated. */
    private const val MAX_SSE_LINE_CHARS = 1_000_000

    /**
     * Reads one line, capping the buffered length at [MAX_SSE_LINE_CHARS] so a
     * malicious server cannot OOM the client with an unbounded line. Overlong
     * lines are still consumed (the stream stays aligned) but truncated.
     * Returns null only at end-of-stream with no pending characters.
     */
    private fun BufferedReader.readBoundedLine(maxChars: Int = MAX_SSE_LINE_CHARS): String? {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            val ch = c.toChar()
            if (ch == '\n') return sb.toString()
            if (ch == '\r') continue
            if (sb.length < maxChars) sb.append(ch)
        }
    }

    /**
     * Builds the `messages` JSON array for `/v1/chat/completions`.
     * Messages carrying [RemoteChatMessage.imageDataUrls] use the OpenAI
     * multi-part content format (`type: text` + `type: image_url`); the rest
     * use a plain string `content`.
     */
    internal fun buildMessagesJson(messages: List<RemoteChatMessage>): JSONArray {
        val msgsArray = JSONArray()
        for (msg in messages) {
            msgsArray.put(
                JSONObject().apply {
                    put("role", msg.role)
                    if (msg.imageDataUrls.isEmpty()) {
                        put("content", msg.text)
                    } else {
                        val parts = JSONArray()
                        if (msg.text.isNotBlank()) {
                            parts.put(
                                JSONObject().apply {
                                    put("type", "text")
                                    put("text", msg.text)
                                },
                            )
                        }
                        for (dataUrl in msg.imageDataUrls) {
                            parts.put(
                                JSONObject().apply {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        JSONObject().apply { put("url", dataUrl) },
                                    )
                                },
                            )
                        }
                        put("content", parts)
                    }
                },
            )
        }
        return msgsArray
    }

    /**
     * Heuristic: does this remote model id look like a vision-capable model?
     * Used to warn before sending images to a text-only model. Conservative
     * by design — unknown ids return false so the warning shows.
     */
    fun isLikelyVisionModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        if (id.isBlank()) return false
        val visionMarkers =
            listOf(
                "vision", "-vl", "_vl", "vl-", "llava", "moondream", "bakllava",
                "minicpm-v", "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl",
                "gemma-3", "pixtral", "idefics", "cogvlm", "deepseek-vl",
                "gpt-4o", "gpt-4-vision", "gpt-4-turbo", "o1", "claude-3",
                "claude-4", "sonnet", "opus", "haiku", "llama-3.2-vision",
                "llama-4", "maverick", "scout", "internvl", "phi-3-vision",
                "phi-4-multimodal",
            )
        return visionMarkers.any { id.contains(it) }
    }

    /**
     * Best-effort detection of a remote model's context window.
     *
     * The OpenAI-compatible `/v1/models` endpoint does not report context
     * length, so this uses provider-specific endpoints:
     * - Ollama: POST `{root}/api/show` → `model_info` keys ending in
     *   `.context_length` (e.g. `llama.context_length`).
     * - OpenRouter: GET `https://openrouter.ai/api/v1/models` →
     *   `data[].context_length` matched by model id.
     *
     * Returns null when the provider or model is unknown.
     */
    suspend fun detectContextWindow(
        baseUrl: String,
        apiKey: String = "",
        modelId: String,
    ): Int? =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trimEnd('/')
                val lower = cleanUrl.lowercase()
                if (lower.contains("openrouter.ai")) {
                    val body = getJson("https://openrouter.ai/api/v1/models", apiKey) ?: return@withContext null
                    return@withContext parseOpenRouterContextWindow(body, modelId)
                }
                // Assume Ollama-style (covers Ollama, LM Studio hosts, etc.).
                // /api/show lives on the server root, not under /v1.
                val root = if (cleanUrl.endsWith("/v1")) cleanUrl.dropLast(3) else cleanUrl
                val body =
                    postJson(
                        "$root/api/show",
                        apiKey,
                        JSONObject().apply { put("name", modelId) }.toString(),
                    ) ?: return@withContext null
                parseOllamaContextWindow(body)
            } catch (e: Exception) {
                KLog.d("RemoteAiClient", "Context window detection failed: ${e.message}")
                null
            }
        }

    /** Parses Ollama `/api/show` output for a `*.context_length` entry. */
    internal fun parseOllamaContextWindow(showJson: String): Int? {
        return try {
            val info = JSONObject(showJson).optJSONObject("model_info") ?: return null
            val keys = info.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.endsWith(".context_length")) {
                    val v = info.optInt(key, -1)
                    if (v > 0) return v
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Parses OpenRouter `/api/v1/models` output for a matching model id. */
    internal fun parseOpenRouterContextWindow(modelsJson: String, modelId: String): Int? {
        return try {
            val data = JSONObject(modelsJson).optJSONArray("data") ?: return null
            val want = modelId.lowercase()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val id = obj.optString("id", "").lowercase()
                // Accept exact id or the short name after the org prefix.
                if (id == want || id.substringAfterLast('/') == want.substringAfterLast('/')) {
                    val v = obj.optInt("context_length", -1)
                    if (v > 0) return v
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getJson(url: String, apiKey: String): String? {
        val reqBuilder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun postJson(url: String, apiKey: String, jsonBody: String): String? {
        val reqBuilder =
            Request.Builder().url(url).post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = baseUrl.trimEnd('/')
                val url = if (cleanUrl.endsWith("/v1")) "$cleanUrl/models" else "$cleanUrl/v1/models"

                val reqBuilder = Request.Builder().url(url).get()
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                val response = client.newCall(reqBuilder.build()).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
                    }

                    val body = resp.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val dataArray = json.optJSONArray("data") ?: JSONArray()
                    val models = mutableListOf<RemoteModelItem>()
                    for (i in 0 until dataArray.length()) {
                        val obj = dataArray.getJSONObject(i)
                        val id = obj.optString("id", "")
                        if (id.isNotBlank()) {
                            models.add(
                                RemoteModelItem(
                                    id = id,
                                    name = id,
                                    provider = "Remote",
                                    description = "Remote model on $cleanUrl",
                                ),
                            )
                        }
                    }
                    Result.success(models)
                }
            } catch (e: Exception) {
                KLog.e("RemoteAiClient", "Failed to fetch models: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * Streams a chat completion. Messages with [RemoteChatMessage.imageDataUrls]
     * are sent in OpenAI multi-part format so vision-capable remote models can
     * read attached images.
     */
    fun streamChat(
        baseUrl: String,
        apiKey: String = "",
        model: String,
        messages: List<RemoteChatMessage>,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = 2048,
    ): Flow<String> =
        flow {
            val cleanUrl = baseUrl.trimEnd('/')
            val url = if (cleanUrl.endsWith("/v1")) "$cleanUrl/chat/completions" else "$cleanUrl/v1/chat/completions"

            val requestJson =
                JSONObject().apply {
                    put("model", model)
                    put("messages", buildMessagesJson(messages))
                    put("temperature", temperature.toDouble())
                    put("top_p", topP.toDouble())
                    put("max_tokens", maxTokens)
                    put("stream", true)
                }

            val reqBuilder =
                Request.Builder()
                    .url(url)
                    .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))

            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = client.newCall(reqBuilder.build()).execute()
            // Close the connection promptly when the collecting coroutine is
            // cancelled, instead of blocking in readLine() until the next
            // chunk or socket timeout.
            currentCoroutineContext()[Job]?.invokeOnCompletion {
                try {
                    response.close()
                } catch (_: Exception) {
                }
            }
            try {
                if (!response.isSuccessful) {
                    throw IllegalStateException("Remote server error: HTTP ${response.code} (${response.message})")
                }

                val reader = response.body?.byteStream()?.bufferedReader() ?: return@flow
                try {
                    while (true) {
                        val line = reader.readBoundedLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            val data =
                                trimmed.removePrefix("data:").let {
                                    if (it.startsWith(" ") || it.startsWith("\t")) it.drop(1) else it
                                }
                            if (data == "[DONE]") break
                            try {
                                val chunkJson = JSONObject(data)
                                val choices = chunkJson.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content", "") ?: ""
                                    if (content.isNotEmpty()) {
                                        emit(content)
                                    }
                                }
                            } catch (e: Exception) {
                                KLog.d("RemoteAiClient", "Skipping malformed SSE chunk: ${e.message}")
                            }
                        }
                    }
                } finally {
                    reader.close()
                }
            } finally {
                response.close()
            }
        }.flowOn(Dispatchers.IO)
}

/**
 * Hard-caps a remote request history to the model's context window.
 *
 * Keeps a leading system message and then the most recent messages that fit
 * within [windowTokens] minus [reserveTokens] headroom for the reply. The
 * newest message is always kept, even if it alone exceeds the budget.
 * Pure function — token counts are supplied by the caller.
 */
internal fun capMessagesToWindow(
    messages: List<RemoteChatMessage>,
    tokenCounts: List<Int>,
    windowTokens: Int,
    reserveTokens: Int,
): List<RemoteChatMessage> {
    if (messages.isEmpty() || tokenCounts.size != messages.size) return messages
    val budget = (windowTokens - reserveTokens).coerceAtLeast(1)
    if (tokenCounts.sum() <= budget) return messages
    val hasSystem = messages.first().role == "system"
    val systemMsg = if (hasSystem) messages.first() else null
    val systemTokens = if (hasSystem) tokenCounts.first() else 0
    val rest = if (hasSystem) messages.drop(1) else messages
    val restCounts = if (hasSystem) tokenCounts.drop(1) else tokenCounts
    val kept = ArrayDeque<RemoteChatMessage>()
    var used = 0
    for (i in rest.indices.reversed()) {
        if (used + restCounts[i] > budget - systemTokens && kept.isNotEmpty()) break
        kept.addFirst(rest[i])
        used += restCounts[i]
    }
    return if (systemMsg != null && systemTokens <= budget) {
        listOf(systemMsg) + kept.toList()
    } else {
        kept.toList()
    }
}
