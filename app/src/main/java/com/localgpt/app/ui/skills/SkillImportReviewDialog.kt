package com.localgpt.app.ui.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.localgpt.app.skills.Skill

/**
 * Review dialog shown right after a skill is imported (B9).
 *
 * Imported skills are untrusted prompt text, so they are stored DISABLED.
 * This dialog shows an instruction preview and asks the user to confirm
 * before the skill is allowed into the prompt context.
 */
@Composable
fun SkillImportReviewDialog(
    skill: Skill,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review imported skill") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = skill.name.ifBlank { "Untitled skill" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Instructions preview:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = skill.instructions.take(1200).ifBlank { "(empty)" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This skill is disabled by default. Enabling it injects the instructions above into the AI prompt. Only enable skills from sources you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text("Enable skill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep disabled")
            }
        },
    )
}
