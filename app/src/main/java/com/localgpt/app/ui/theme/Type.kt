package com.localgpt.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.localgpt.app.R

val InterFontFamily =
    FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )

/**
 * M3 Expressive, compact, and polished Typography using the Inter font family.
 * Perfectly tuned sizes for mobile readability without oversized headings.
 */
private fun inter(
    weight: FontWeight,
    fontSize: Int,
    lineHeight: Int,
    letterSpacing: Double = 0.0,
): TextStyle =
    TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = weight,
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing.sp,
    )

val AppTypography =
    Typography(
        displayLarge = inter(FontWeight.Bold, 36, 42, -0.2),
        displayMedium = inter(FontWeight.Bold, 30, 36, -0.1),
        displaySmall = inter(FontWeight.Bold, 24, 30),
        headlineLarge = inter(FontWeight.Bold, 24, 30),
        headlineMedium = inter(FontWeight.Bold, 21, 26, -0.1),
        headlineSmall = inter(FontWeight.SemiBold, 18, 24),
        titleLarge = inter(FontWeight.SemiBold, 17, 22),
        titleMedium = inter(FontWeight.SemiBold, 15, 20, 0.1),
        titleSmall = inter(FontWeight.Medium, 13, 18, 0.1),
        bodyLarge = inter(FontWeight.Normal, 14, 20, 0.15),
        bodyMedium = inter(FontWeight.Normal, 13, 18, 0.15),
        bodySmall = inter(FontWeight.Normal, 11, 15, 0.2),
        labelLarge = inter(FontWeight.Medium, 13, 18, 0.1),
        labelMedium = inter(FontWeight.Medium, 11, 15, 0.3),
        labelSmall = inter(FontWeight.Medium, 10, 14, 0.3),
    )
