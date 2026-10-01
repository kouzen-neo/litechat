package com.localgpt.app.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpModelsTest {

    @Test
    fun testServerConfigEndpointNormalization() {
        assertEquals("https://example.com/mcp", McpServerConfig(name = "a", url = "example.com/mcp").endpoint())
        assertEquals("http://192.168.1.10:8000/mcp", McpServerConfig(name = "a", url = "http://192.168.1.10:8000/mcp").endpoint())
        assertNull(McpServerConfig(name = "a", url = "   ").endpoint())
    }

    @Test
    fun testServersJsonRoundTrip() {
        val servers =
            listOf(
                McpServerConfig(id = "id-1", name = "Tools", url = "https://example.com/mcp", authToken = "sekret", enabled = true),
                McpServerConfig(id = "id-2", name = "Lokal", url = "http://192.168.1.5:8000/mcp", enabled = false),
            )
        val json = McpManager.serversToJson(servers)
        val back = McpManager.serversFromJson(json)
        assertEquals(2, back.size)
        assertEquals("id-1", back[0].id)
        assertEquals("Tools", back[0].name)
        assertEquals("sekret", back[0].authToken)
        assertTrue(back[0].enabled)
        assertEquals("id-2", back[1].id)
    }

    @Test
    fun testServersFromJsonMalformedReturnsEmpty() {
        assertTrue(McpManager.serversFromJson("not json{{{").isEmpty())
        assertTrue(McpManager.serversFromJson("").isEmpty())
    }

    @Test
    fun testToolSignature() {
        val schema =
            JsonParser.parseString(
                """{"type":"object","properties":{"path":{"type":"string"},"limit":{"type":"integer"}},"required":["path"]}""",
            ).asJsonObject
        val tool = McpTool(serverId = "s", serverName = "srv", name = "read_file", inputSchema = schema)
        assertEquals("read_file(path: string, limit?: integer)", tool.signature)
    }

    @Test
    fun testToolSignatureEmptySchema() {
        val tool = McpTool(serverId = "s", serverName = "srv", name = "ping", inputSchema = JsonObject())
        assertEquals("ping()", tool.signature)
    }

    @Test
    fun testCallRequestArgumentsJson() {
        val args = JsonObject().apply { addProperty("q", "hello") }
        val req = McpCallRequest(serverId = "s", serverName = "srv", toolName = "t", arguments = args)
        assertTrue(req.argumentsJson.contains("\"q\""))
    }
}
