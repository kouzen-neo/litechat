package com.localgpt.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTagsTest {

    @Test
    fun testSanitizeTagsTrimsDedupesAndCaps() {
        val out = sanitizeTags(listOf("  Work ", "work", "", "   ", "a".repeat(40), "b"))
        assertEquals(listOf("Work", "work", "a".repeat(24), "b"), out)
    }

    @Test
    fun testSanitizeTagsNullAndEmpty() {
        assertTrue(sanitizeTags(null).isEmpty())
        assertTrue(sanitizeTags(emptyList()).isEmpty())
    }

    @Test
    fun testSanitizeTagsMaxTen() {
        val out = sanitizeTags((1..15).map { "t$it" })
        assertEquals(10, out.size)
        assertEquals("t1", out.first())
    }

    @Test
    fun testNormalizeRepairsLegacyNulls() {
        // Simulates Gson parsing JSON that predates folder/tags (JVM nulls).
        val conv = Conversation(id = "x", title = "old")
        val tagsField = Conversation::class.java.getDeclaredField("tags").apply { isAccessible = true }
        val folderField = Conversation::class.java.getDeclaredField("folder").apply { isAccessible = true }
        tagsField.set(conv, null)
        folderField.set(conv, null)
        ChatRepository.normalize(conv)
        assertEquals("", conv.folder)
        assertTrue(conv.tags.isEmpty())
    }

    @Test
    fun testHeaderCarriesSanitizedTags() {
        val conv = Conversation(id = "y", title = "t", tags = listOf(" a ", "", "b", "a"))
        val header = conv.toHeader()
        assertEquals(listOf("a", "b"), header.tags)
    }
}
