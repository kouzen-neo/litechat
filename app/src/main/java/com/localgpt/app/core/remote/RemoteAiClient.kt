package com.localgpt.app.core.remote
import com.localgpt.app.data.ChatConstants

import com.localgpt.app.util.KLog
import com.localgpt.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val body = response.body?.string() ?: "{}"
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
            if (!response.isSuccessful) {
                throw IllegalStateException("Remote server error: HTTP ${response.code} (${response.message})")
            }

            val reader = response.body?.byteStream()?.bufferedReader() ?: return@flow
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data: ")) {
                        val data = trimmed.removePrefix("data: ").trim()
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
                        } catch (_: Exception) {
                        }
                    }
                }
            } finally {
                reader.close()
                response.close()
            }
        }.flowOn(Dispatchers.IO)
}
