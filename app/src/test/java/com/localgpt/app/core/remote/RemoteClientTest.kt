package com.localgpt.app.core.remote

import com.localgpt.app.data.effectiveRemoteContextWindow
import com.localgpt.app.data.parseRemoteModelContextWindows
import com.localgpt.app.data.remoteModelContextWindowsToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for remote-backend vision support and context-window handling:
 * - OpenAI-style multi-part message building (text + image_url parts)
 * - vision-model heuristic
 * - Ollama /api/show and OpenRouter context-length parsing
 * - per-model remote context-window overrides
 * - client-side history capping
 */
class RemoteClientTest {

    // ── buildMessagesJson ──────────────────────────────────────────────

    @Test
    fun `text-only message builds plain string content`() {
        val arr =
            RemoteAiClient.buildMessagesJson(
                listOf(RemoteChatMessage(role = "user", text = "hello")),
            )
        assertEquals(1, arr.length())
        val obj = arr.getJSONObject(0)
        assertEquals("user", obj.getString("role"))
        assertEquals("hello", obj.getString("content"))
    }

    @Test
    fun `message with image builds multi-part content`() {
        val dataUrl = "data:image/jpeg;base64,AAAA"
        val arr =
            RemoteAiClient.buildMessagesJson(
                listOf(RemoteChatMessage(role = "user", text = "what is this?", imageDataUrls = listOf(dataUrl))),
            )
        val obj = arr.getJSONObject(0)
        val parts = obj.getJSONArray("content")
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("what is this?", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals(dataUrl, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `image-only message omits empty text part`() {
        val arr =
            RemoteAiClient.buildMessagesJson(
                listOf(RemoteChatMessage(role = "user", text = "", imageDataUrls = listOf("data:image/jpeg;base64,AAAA"))),
            )
        val parts = arr.getJSONObject(0).getJSONArray("content")
        assertEquals(1, parts.length())
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
    }

    @Test
    fun `multiple images become multiple image_url parts`() {
        val arr =
            RemoteAiClient.buildMessagesJson(
                listOf(
                    RemoteChatMessage(
                        role = "user",
                        text = "compare",
                        imageDataUrls = listOf("data:image/jpeg;base64,AAAA", "data:image/jpeg;base64,BBBB"),
                    ),
                ),
            )
        assertEquals(3, arr.getJSONObject(0).getJSONArray("content").length())
    }

    // ── isLikelyVisionModel ────────────────────────────────────────────

    @Test
    fun `vision markers are detected`() {
        assertTrue(RemoteAiClient.isLikelyVisionModel("qwen2-vl:7b"))
        assertTrue(RemoteAiClient.isLikelyVisionModel("llava:13b"))
        assertTrue(RemoteAiClient.isLikelyVisionModel("gemma-3-12b-it"))
        assertTrue(RemoteAiClient.isLikelyVisionModel("gpt-4o"))
        assertTrue(RemoteAiClient.isLikelyVisionModel("claude-3-5-sonnet-20241022"))
        assertTrue(RemoteAiClient.isLikelyVisionModel("Qwen2.5-VL-7B-Instruct"))
    }

    @Test
    fun `text-only models are not vision`() {
        assertFalse(RemoteAiClient.isLikelyVisionModel("llama3.1:8b"))
        assertFalse(RemoteAiClient.isLikelyVisionModel("deepseek-r1:14b"))
        assertFalse(RemoteAiClient.isLikelyVisionModel("gemma2:9b"))
        assertFalse(RemoteAiClient.isLikelyVisionModel(""))
    }

    // ── context window detection parsers ──────────────────────────────

    @Test
    fun `ollama show output yields context_length`() {
        val json =
            """{"model_info":{"general.architecture":"llama","llama.context_length":8192,"llama.embedding_length":4096}}"""
        assertEquals(8192, RemoteAiClient.parseOllamaContextWindow(json))
    }

    @Test
    fun `ollama show without context_length yields null`() {
        assertNull(RemoteAiClient.parseOllamaContextWindow("""{"model_info":{"a.b":1}}"""))
        assertNull(RemoteAiClient.parseOllamaContextWindow("""{}"""))
        assertNull(RemoteAiClient.parseOllamaContextWindow("not json"))
    }

    @Test
    fun `openrouter models output yields context_length`() {
        val json =
            """{"data":[{"id":"openai/gpt-4o","context_length":128000},{"id":"qwen/qwen-2.5-vl-72b-instruct","context_length":32768}]}"""
        assertEquals(128000, RemoteAiClient.parseOpenRouterContextWindow(json, "openai/gpt-4o"))
        // Short-name matching also works.
        assertEquals(32768, RemoteAiClient.parseOpenRouterContextWindow(json, "qwen-2.5-vl-72b-instruct"))
    }

    @Test
    fun `openrouter unknown model yields null`() {
        val json = """{"data":[{"id":"openai/gpt-4o","context_length":128000}]}"""
        assertNull(RemoteAiClient.parseOpenRouterContextWindow(json, "nope/model"))
        assertNull(RemoteAiClient.parseOpenRouterContextWindow("garbage", "openai/gpt-4o"))
    }

    // ── per-model overrides ────────────────────────────────────────────

    @Test
    fun `override json round-trips`() {
        val map = mapOf("qwen2-vl:7b" to 32768, "llava:13b" to 4096)
        val json = remoteModelContextWindowsToJson(map)
        assertEquals(map, parseRemoteModelContextWindows(json))
    }

    @Test
    fun `malformed override json yields empty map`() {
        assertTrue(parseRemoteModelContextWindows("not json").isEmpty())
        assertTrue(parseRemoteModelContextWindows("{}").isEmpty())
    }

    @Test
    fun `effective window prefers per-model override`() {
        val settings =
            com.localgpt.app.data.Settings(
                contextWindowTokens = 2048,
                remoteContextWindowTokens = 8192,
                remoteModelContextWindowsJson = remoteModelContextWindowsToJson(mapOf("m1" to 32768)),
            )
        assertEquals(32768, settings.effectiveRemoteContextWindow("m1"))
        assertEquals(8192, settings.effectiveRemoteContextWindow("other"))
        // On-device setting is never consulted for remote.
        assertEquals(2048, settings.contextWindowTokens)
    }

    @Test
    fun `effective window clamps to minimum`() {
        val settings = com.localgpt.app.data.Settings(remoteContextWindowTokens = 100)
        assertEquals(1024, settings.effectiveRemoteContextWindow("m"))
    }

    // ── capMessagesToWindow ────────────────────────────────────────────

    private fun msg(role: String, text: String) = RemoteChatMessage(role = role, text = text)

    @Test
    fun `fitting history is returned unchanged`() {
        val msgs = listOf(msg("system", "sys"), msg("user", "hi"), msg("assistant", "hello"))
        val capped = capMessagesToWindow(msgs, listOf(10, 20, 20), windowTokens = 8192, reserveTokens = 512)
        assertEquals(msgs, capped)
    }

    @Test
    fun `oldest messages are dropped first, system kept`() {
        val msgs =
            listOf(
                msg("system", "sys"),
                msg("user", "old1"),
                msg("assistant", "old2"),
                msg("user", "new"),
            )
        // Budget 100: system(10) + new(60) fit; old1/old2 (50 each) do not.
        val capped = capMessagesToWindow(msgs, listOf(10, 50, 50, 60), windowTokens = 612, reserveTokens = 512)
        assertEquals(listOf(msgs[0], msgs[3]), capped)
    }

    @Test
    fun `newest message is always kept even when oversized`() {
        val msgs = listOf(msg("user", "huge"))
        val capped = capMessagesToWindow(msgs, listOf(99999), windowTokens = 4096, reserveTokens = 512)
        assertEquals(msgs, capped)
    }

    @Test
    fun `mismatched counts return input unchanged`() {
        val msgs = listOf(msg("user", "hi"))
        assertEquals(msgs, capMessagesToWindow(msgs, emptyList(), 4096, 512))
        assertEquals(emptyList<RemoteChatMessage>(), capMessagesToWindow(emptyList(), emptyList(), 4096, 512))
    }
}
