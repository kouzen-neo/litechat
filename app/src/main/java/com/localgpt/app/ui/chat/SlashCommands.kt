package com.localgpt.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Slash command data class for quick prompt templates.
 */
data class SlashCommand(
    val command: String,
    val title: String,
    val description: String,
    val promptTemplate: String,
    val icon: ImageVector,
)

/**
 * Default slash commands available in the chat input.
 */
val DEFAULT_SLASH_COMMANDS =
    listOf(
        SlashCommand(
            command = "/summarize",
            title = "Summarize",
            description = "Condense text into concise key points",
            promptTemplate = "Summarize the following text concisely, clearly, and in a well-structured format:\n\n",
            icon = Icons.Default.Description,
        ),
        SlashCommand(
            command = "/fix-grammar",
            title = "Fix Grammar",
            description = "Correct grammar, spelling, and phrasing",
            promptTemplate = "Fix the grammar, spelling, and punctuation of the following text without altering its core meaning:\n\n",
            icon = Icons.Default.Edit,
        ),
        SlashCommand(
            command = "/translate",
            title = "Translate",
            description = "Translate text accurately and naturally",
            promptTemplate = "Translate the following text into fluent, natural English:\n\n",
            icon = Icons.Default.Translate,
        ),
        SlashCommand(
            command = "/explain",
            title = "Explain (ELI5)",
            description = "Explain concept with simple analogies",
            promptTemplate = "Explain the following concept in very simple, accessible language with relatable analogies:\n\n",
            icon = Icons.Default.Psychology,
        ),
        SlashCommand(
            command = "/code",
            title = "Write Code",
            description = "Generate modular, clean, and tested code",
            promptTemplate = "Write clean, idiomatic, efficient code for the following task, along with a brief explanation:\n\n",
            icon = Icons.Default.Code,
        ),
        SlashCommand(
            command = "/bullet-points",
            title = "Bullet Points",
            description = "Format into structured bullet points",
            promptTemplate = "Convert the following content into a clean, structured bullet-point list:\n\n",
            icon = Icons.AutoMirrored.Filled.FormatListBulleted,
        ),
        SlashCommand(
            command = "/rewrite",
            title = "Rewrite Pro",
            description = "Rewrite in a professional and clear tone",
            promptTemplate = "Rewrite the following text in a more professional, clear, and persuasive tone:\n\n",
            icon = Icons.Default.AutoAwesome,
        ),
    )
