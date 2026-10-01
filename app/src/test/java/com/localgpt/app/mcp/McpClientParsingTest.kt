package com.localgpt.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class McpClientParsingTest {

    private val client = McpClient(McpServerConfig(name = "t", url = "http://127.0.0.1:9/mcp"))

    @Test
    fun testPlainJsonEnvelope() {
        val body = """{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}"""
        val env = client.extractJsonRpcBody(body)
        assertNotNull(env)
        assertEquals("2.0", env?.get("jsonrpc")?.asString)
    }

    @Test
    fun testSseEnvelopeTakesLastData() {
        val body =
            """
            event: message
            data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}

            data: {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"ping"}]}}
            """.trimIndent()
        val env = client.extractJsonRpcBody(body)
        assertNotNull(env)
        assertEquals("ping", env?.getAsJsonObject("result")?.getAsJsonArray("tools")?.get(0)?.asJsonObject?.get("name")?.asString)
    }

    @Test
    fun testGarbageReturnsNull() {
        assertNull(client.extractJsonRpcBody(""))
        assertNull(client.extractJsonRpcBody("<html>nope</html>"))
        assertNull(client.extractJsonRpcBody("data: not json"))
    }

    @Test
    fun testSseIgnoresNonDataLines() {
        val body = ": keep-alive\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}"
        assertNotNull(client.extractJsonRpcBody(body))
    }
}
