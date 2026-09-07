package com.localgpt.app.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseProtocolTest {
    @Test
    fun `chunk is valid SSE with escaped content`() {
        val line = SseProtocol.chunk("id1", "mymodel", "say \"hi\"\nline2")
        assertTrue(line.startsWith("data: {"))
        assertTrue(line.contains("\"content\":\"say \\\"hi\\\"\\nline2\""))
        assertTrue(line.contains("\"model\":\"mymodel\""))
        assertTrue(line.contains("chat.completion.chunk"))
    }

    @Test
    fun `role chunk carries assistant role and empty delta`() {
        val line = SseProtocol.roleChunk("id1", "m")
        assertTrue(line.contains("\"role\":\"assistant\""))
    }

    @Test
    fun `finish reason chunk has null content and stop`() {
        val json = SseProtocol.chunk("id1", "m", null, finishReason = "stop").removePrefix("data: ")
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val choice = root.getAsJsonArray("choices").get(0).asJsonObject
        assertTrue(choice.getAsJsonObject("delta").entrySet().isEmpty())
        assertEquals("stop", choice.get("finish_reason").asString)
    }

    @Test
    fun `done marker matches OpenAI convention`() {
        assertEquals("data: [DONE]", SseProtocol.done())
    }

    @Test
    fun `completion object mirrors non-streaming schema`() {
        val json = SseProtocol.completion("id9", "m", "hello world")
        assertEquals("chat.completion", json.get("object").asString)
        val choice = json.getAsJsonArray("choices").get(0).asJsonObject
        assertEquals(
            "hello world",
            choice.getAsJsonObject("message").get("content").asString,
        )
        assertEquals("stop", choice.get("finish_reason").asString)
    }
}
