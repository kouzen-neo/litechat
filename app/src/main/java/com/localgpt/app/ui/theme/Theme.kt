package com.localgpt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

// Default seed color (teal) used for the Material You palette.
val DefaultThemeColor = Color(0xFF00897B)

/**
 * Consistent, high-contrast Material 3 theme powered by MaterialKolor.
 * Guarantees 100% proper Dark Mode, Light Mode, and Pure Black OLED palettes
 * across all Android OEMs (including MIUI / HyperOS) without color clashing or crashes.
 */
@Composable
fun LiteChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    val seed = if (themeColor == Color.Unspecified) DefaultThemeColor else themeColor

    val colorScheme =
        rememberDynamicColorScheme(
            seedColor = seed,
            isDark = darkTheme,
            isAmoled = (darkTheme && pureBlack),
            style = PaletteStyle.TonalSpot,
        )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
