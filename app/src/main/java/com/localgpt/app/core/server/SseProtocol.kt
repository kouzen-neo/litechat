package com.localgpt.app.core.server
import com.localgpt.app.data.ChatConstants

import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds Server-Sent Events payloads in OpenAI `chat.completion.chunk` format.
 * JSON is assembled through Gson objects so string content is always escaped
 * correctly.
 */
object SseProtocol {
    private val counter = AtomicLong(0L)

    fun newId(): String = "chatcmpl-litechat-${System.currentTimeMillis()}-${counter.incrementAndGet()}"

    fun chunk(
        id: String,
        model: String,
        deltaContent: String?,
        finishReason: String? = null,
    ): String {
        val delta = JsonObject()
        if (deltaContent != null) delta.addProperty("content", deltaContent)

        val choice =
            JsonObject().apply {
                addProperty("index", 0)
                add("delta", delta)
                if (finishReason != null) addProperty("finish_reason", finishReason)
            }

        val root =
            JsonObject().apply {
                addProperty("id", id)
                addProperty("object", "chat.completion.chunk")
                addProperty("created", System.currentTimeMillis() / 1000)
                addProperty("model", model)
                add("choices", com.google.gson.JsonArray().apply { add(choice) })
            }
        return "data: $root"
    }

    fun roleChunk(
        id: String,
        model: String,
    ): String {
        val delta = JsonObject().apply { addProperty("role", ChatConstants.ROLE_ASSISTANT) }
        val choice =
            JsonObject().apply {
                addProperty("index", 0)
                add("delta", delta)
            }
        val root =
            JsonObject().apply {
                addProperty("id", id)
                addProperty("object", "chat.completion.chunk")
                addProperty("created", System.currentTimeMillis() / 1000)
                addProperty("model", model)
                add("choices", com.google.gson.JsonArray().apply { add(choice) })
            }
        return "data: $root"
    }

    fun done(): String = "data: [DONE]"

    /**
     * Full non-streaming response body in `chat.completion` format.
     */
    fun completion(
        id: String,
        model: String,
        content: String,
    ): JsonObject {
        val message =
            JsonObject().apply {
                addProperty("role", ChatConstants.ROLE_ASSISTANT)
                addProperty("content", content)
            }
        val choice =
            JsonObject().apply {
                addProperty("index", 0)
                add("message", message)
                addProperty("finish_reason", "stop")
            }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion")
            addProperty("created", System.currentTimeMillis() / 1000)
            addProperty("model", model)
            add("choices", com.google.gson.JsonArray().apply { add(choice) })
            add(
                "usage",
                JsonObject().apply {
                    addProperty("prompt_tokens", 0)
                    addProperty("completion_tokens", 0)
                    addProperty("total_tokens", 0)
                },
            )
        }
    }
}
