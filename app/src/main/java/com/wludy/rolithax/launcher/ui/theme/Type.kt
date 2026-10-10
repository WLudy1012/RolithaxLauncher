package com.wludy.rolithax.launcher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val baseTypography = Typography()

/** 全局字型：统一字重与行高，让标题层级和正文可读性在深浅色下保持一致。 */
val Typography = baseTypography.copy(
    displaySmall = baseTypography.displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = baseTypography.headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = baseTypography.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = baseTypography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = baseTypography.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = baseTypography.bodyLarge.copy(
        lineHeight = 24.sp,
    ),
    bodyMedium = baseTypography.bodyMedium.copy(
        lineHeight = 21.sp,
    ),
    bodySmall = baseTypography.bodySmall.copy(
        lineHeight = 18.sp,
    ),
    labelLarge = baseTypography.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = baseTypography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
    ),
)

val ConsoleFont = FontFamily.Monospace

val ConsoleTextStyle = TextStyle(
    fontFamily = ConsoleFont,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Normal,
)
