package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.GraphicsEnvironment

private val lightColors = lightColorScheme(
    primary = Color(0xFF005FB8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EAFB),
    onPrimaryContainer = Color(0xFF00335F),
    secondary = Color(0xFF005FB8),
    secondaryContainer = Color(0xFFE5EFF9),
    onSecondaryContainer = Color(0xFF153E64),
    background = Color(0xFFF3F3F3),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFF505050),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF2F2F2),
    outline = Color(0xFF777777),
    outlineVariant = Color(0xFFD5D5D5),
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF60CDFF),
    onPrimary = Color(0xFF003453),
    primaryContainer = Color(0xFF004B73),
    onPrimaryContainer = Color(0xFFC7E7FF),
    secondary = Color(0xFF60CDFF),
    secondaryContainer = Color(0xFF333F48),
    onSecondaryContainer = Color(0xFFEDF6FD),
    background = Color(0xFF202020),
    onBackground = Color(0xFFF4F4F4),
    surface = Color(0xFF2B2B2B),
    onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF383838),
    onSurfaceVariant = Color(0xFFC8C8C8),
    surfaceContainerLow = Color(0xFF252525),
    surfaceContainer = Color(0xFF303030),
    outline = Color(0xFF999999),
    outlineVariant = Color(0xFF505050),
)

private val windowsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

private val defaultTypography = Typography()
private val windowsFontNames = if (System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
    try { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() } catch (_: Exception) { emptySet() }
} else emptySet()

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun windowsFont(name: String, fallback: FontFamily): FontFamily =
    if (windowsFontNames.any { it.equals(name, ignoreCase = true) }) FontFamily(name) else fallback

private val desktopUiFont = windowsFont("Segoe UI", windowsFont("Microsoft YaHei UI", FontFamily.SansSerif))
internal val desktopMonospaceFont = windowsFont("Consolas", FontFamily.Monospace)

private val windowsTypography = defaultTypography.copy(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = desktopUiFont),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = desktopUiFont),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = desktopUiFont),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = desktopUiFont),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = desktopUiFont),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = desktopUiFont),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = desktopUiFont),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = desktopUiFont, fontSize = 16.sp, lineHeight = 23.sp),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = desktopUiFont, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = desktopUiFont, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = desktopUiFont, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = desktopUiFont, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = desktopUiFont, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = desktopUiFont, fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = desktopUiFont, fontSize = 13.sp, lineHeight = 19.sp),
)

@Composable
internal fun RolithaxWindowsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) darkColors else lightColors,
        shapes = windowsShapes,
        typography = windowsTypography,
        content = content,
    )
}
