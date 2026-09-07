package com.localgpt.app.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun `builds prompt with system and dialogue`() {
        val prompt =
            PromptBuilder.build(
                listOf(
                    PromptBuilder.ChatMessage("system", "Be terse."),
                    PromptBuilder.ChatMessage("user", "Hi"),
                    PromptBuilder.ChatMessage("assistant", "Hello!"),
                    PromptBuilder.ChatMessage("user", "How are you?"),
                ),
                "fallback system",
                templateFormat = "raw",
            )
        assertTrue(prompt.startsWith("[System]\nBe terse."))
        assertTrue(prompt.contains("User: Hi"))
        assertTrue(prompt.contains("Assistant: Hello!"))
        assertTrue(prompt.trimEnd().endsWith("Assistant:"))
        assertFalse(prompt.contains("fallback system"))
    }

    @Test
    fun `uses fallback system prompt when none provided`() {
        val prompt =
            PromptBuilder.build(
                listOf(PromptBuilder.ChatMessage("user", "Hi")),
                "You are offline.",
                templateFormat = "raw",
            )
        assertTrue(prompt.contains("[System]\nYou are offline."))
    }

    @Test
    fun `drops oldest turns when history exceeds budget`() {
        val filler = "x".repeat(2000)
        val messages =
            buildList {
                add(PromptBuilder.ChatMessage("system", "sys"))
                repeat(30) { i ->
                    add(PromptBuilder.ChatMessage("user", "$i $filler"))
                    add(PromptBuilder.ChatMessage("assistant", "$i ok"))
                }
                add(PromptBuilder.ChatMessage("user", "final question"))
            }
        val prompt = PromptBuilder.build(messages, "", templateFormat = "raw")
        // The final question must survive; early turns must be dropped.
        assertTrue(prompt.contains("final question"))
        assertFalse(prompt.contains("0 ${"x".repeat(50)}"))
    }

    @Test
    fun `empty dialogue still yields assistant cue`() {
        val prompt = PromptBuilder.build(emptyList(), "sys", templateFormat = "raw")
        assertTrue(prompt.trimEnd().endsWith("Assistant:"))
    }

    @Test
    fun `buildsGemmaPrompt correctly formats special tokens`() {
        val prompt =
            PromptBuilder.build(
                listOf(PromptBuilder.ChatMessage("user", "Hello Gemma")),
                systemPrompt = "You are helpful.",
                templateFormat = "gemma",
            )
        assertTrue(prompt.contains("<start_of_turn>user"))
        assertTrue(prompt.contains("<end_of_turn>"))
        assertTrue(prompt.trimEnd().endsWith("<start_of_turn>model"))
    }

    @Test
    fun `buildsChatMLPrompt correctly formats im_start and im_end`() {
        val prompt =
            PromptBuilder.build(
                listOf(PromptBuilder.ChatMessage("user", "Hello ChatML")),
                systemPrompt = "You are helpful.",
                templateFormat = "chatml",
            )
        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("<|im_start|>user\nHello ChatML<|im_end|>"))
        assertTrue(prompt.trimEnd().endsWith("<|im_start|>assistant"))
    }

    @Test
    fun `buildsLlama3Prompt correctly formats header_id tokens`() {
        val prompt =
            PromptBuilder.build(
                listOf(PromptBuilder.ChatMessage("user", "Hello Llama")),
                systemPrompt = "You are helpful.",
                templateFormat = "llama3",
            )
        assertTrue(prompt.contains("<|begin_of_text|>"))
        assertTrue(prompt.contains("<|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }
}
