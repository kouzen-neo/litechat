package com.localgpt.app.core.remote
import com.localgpt.app.data.ChatConstants

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

    suspend fun fetchModels(baseUrl: String, apiKey: String = ""): Result<List<RemoteModelItem>> =
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

    fun streamChat(
        baseUrl: String,
        apiKey: String = "",
        model: String,
        messages: List<Map<String, String>>,
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
                    val msgsArray = JSONArray()
                    for (msg in messages) {
                        msgsArray.put(
                            JSONObject().apply {
                                put("role", msg["role"] ?: ChatConstants.ROLE_USER)
                                put("content", msg["content"] ?: "")
                            },
                        )
                    }
                    put("messages", msgsArray)
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
            currentCoroutineContext().job.invokeOnCancellation {
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
