package io.github.hatake716.omochi

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object OmochiColors {
    val Window = Color(0xFFF4F1ED)
    val Surface = Color(0xFFFEFCFA)
    val Raised = Color(0xFFFFFFFF)
    val Border = Color(0xFFD8D1CB)
    val Divider = Color(0xFFE5DFDA)
    val Ink = Color(0xFF24262B)
    val Muted = Color(0xFF74747B)
    val Accent = Color(0xFFC96954)
    val AccentDark = Color(0xFFAA4F3D)
    val AccentSoft = Color(0xFFF2DDD7)
    val Blue = Color(0xFF3178C6)
    val Green = Color(0xFF28C840)
    val Yellow = Color(0xFFFEBC2E)
    val Red = Color(0xFFFF5F57)
    val Terminal = Color(0xFF1E2024)
}

private val OmochiScheme: ColorScheme = lightColorScheme(
    primary = OmochiColors.Accent,
    onPrimary = Color.White,
    primaryContainer = OmochiColors.AccentSoft,
    onPrimaryContainer = OmochiColors.Ink,
    secondary = OmochiColors.Blue,
    onSecondary = Color.White,
    background = OmochiColors.Window,
    onBackground = OmochiColors.Ink,
    surface = OmochiColors.Surface,
    onSurface = OmochiColors.Ink,
    surfaceVariant = Color(0xFFECE7E2),
    onSurfaceVariant = OmochiColors.Muted,
    outline = OmochiColors.Border,
    error = OmochiColors.Red,
)

private val OmochiTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
)

@Composable
fun OmochiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmochiScheme,
        typography = OmochiTypography,
        content = content,
    )
}
