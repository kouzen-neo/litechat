package com.localgpt.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

val ACCENT_COLOR_PRESETS: List<Color> =
    listOf(
        Color(0xFF00897B), // Teal (Default)
        Color(0xFFED5564), // Crimson
        Color(0xFF6D5DF6), // Indigo
        Color(0xFF2E7D32), // Emerald
        Color(0xFF1565C0), // Sapphire
        Color(0xFFF9A825), // Amber
        Color(0xFFE91E63), // Magenta
    )

/**
 * Seed-color picker row for Material 3 dynamic color generation (ported from KZKT).
 */
@Composable
fun AccentColorRow(
    selectedColor: Color,
    onSelectColor: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ACCENT_COLOR_PRESETS.forEach { color ->
            val isSelected = color.toArgb() == selectedColor.toArgb()
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color = color, shape = CircleShape)
                        .border(
                            width = if (isSelected) 2.5.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape,
                        ).clickable { onSelectColor(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
