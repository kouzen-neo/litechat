package com.localgpt.app

import com.localgpt.app.skills.ToolCallParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ToolCallParserTest {
    @Test
    fun testXmlToolCall() {
        val text = """
            <think>
            I need to check the scores of the English Premier League.
            </think>
            <tool_call>
            {"name": "web_search", "arguments": {"query": "skor pertandingan liga inggris terbaru"}}
            </tool_call>
        """.trimIndent()

        val parsed = ToolCallParser.parse(text)
        assertNotNull(parsed)
        assertEquals("web_search", parsed?.name)
        assertEquals("skor pertandingan liga inggris terbaru", parsed?.query)
    }

    @Test
    fun testMarkdownToolCall() {
        val text = """
            ```tool_call
            {"name": "web_search", "arguments": {"query": "berita teknologi terbaru"}}
            ```
        """.trimIndent()

        val parsed = ToolCallParser.parse(text)
        assertNotNull(parsed)
        assertEquals("web_search", parsed?.name)
        assertEquals("berita teknologi terbaru", parsed?.query)
    }

    @Test
    fun testFunctionStyleToolCall() {
        val text = "call:web_search(query=\"jadwal liga champions\")"
        val parsed = ToolCallParser.parse(text)
        assertNotNull(parsed)
        assertEquals("web_search", parsed?.name)
        assertEquals("jadwal liga champions", parsed?.query)
    }

    @Test
    fun testIdAndEnToolCalls() {
        val textId = "<tool_call>{\"name\": \"web_search_id\", \"arguments\": {\"query\": \"presiden indonesia 2024\"}}</tool_call>"
        val parsedId = ToolCallParser.parse(textId)
        assertNotNull(parsedId)
        assertEquals("web_search_id", parsedId?.name)
        assertEquals("id", parsedId?.lang)
        assertEquals("presiden indonesia 2024", parsedId?.query)

        val textEn = "<tool_call>{\"name\": \"web_search_en\", \"arguments\": {\"query\": \"latest Premier League results\"}}</tool_call>"
        val parsedEn = ToolCallParser.parse(textEn)
        assertNotNull(parsedEn)
        assertEquals("web_search_en", parsedEn?.name)
        assertEquals("en", parsedEn?.lang)
        assertEquals("latest Premier League results", parsedEn?.query)
    }
}

