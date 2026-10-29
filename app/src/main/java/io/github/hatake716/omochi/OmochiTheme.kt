package io.github.hatake716.omochi

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS system colors, light appearance. Translucent labels, separators and fills are
 * meant to be drawn over [SystemBackground] / [SecondaryGroupedBackground] or glass.
 */
object IosColors {
    val SystemBackground = Color(0xFFFFFFFF)
    val SecondarySystemBackground = Color(0xFFF2F2F7)
    val GroupedBackground = Color(0xFFF2F2F7)
    val SecondaryGroupedBackground = Color(0xFFFFFFFF)

    val Label = Color(0xFF000000)
    /** #3C3C43 at 60%. */
    val SecondaryLabel = Color(0x993C3C43)
    /** #3C3C43 at 30%. */
    val TertiaryLabel = Color(0x4D3C3C43)
    /** #3C3C43 at 18%. */
    val QuaternaryLabel = Color(0x2E3C3C43)

    /** #3C3C43 at 29%. */
    val Separator = Color(0x4A3C3C43)
    val OpaqueSeparator = Color(0xFFC6C6C8)

    /** #787880 at 20%. */
    val SystemFill = Color(0x33787880)
    /** #787880 at 16%. */
    val SecondarySystemFill = Color(0x29787880)
    /** #767680 at 12%. */
    val TertiarySystemFill = Color(0x1F767680)
    /** #747480 at 8%. */
    val QuaternarySystemFill = Color(0x14747480)

    val Blue = Color(0xFF007AFF)
    val BluePressed = Color(0xFF0062CC)
    val Green = Color(0xFF34C759)
    val Red = Color(0xFFFF3B30)
    val Orange = Color(0xFFFF9500)
    val Yellow = Color(0xFFFFCC00)
    val Teal = Color(0xFF30B0C7)
    val Indigo = Color(0xFF5856D6)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)

    val Gray = Color(0xFF8E8E93)
    val Gray2 = Color(0xFFAEAEB2)
    val Gray3 = Color(0xFFC7C7CC)
    val Gray4 = Color(0xFFD1D1D6)
    val Gray5 = Color(0xFFE5E5EA)
    val Gray6 = Color(0xFFF2F2F7)

    /** Body-size error text: at least 4.5:1 on white and on [GroupedBackground]. */
    val RedText = Color(0xFFD70015)
    /** Body-size warning text: at least 4.5:1 on white and on [GroupedBackground]. */
    val OrangeText = Color(0xFFC93400)

    /** Background of the touch key tray, like the light iOS keyboard. */
    val KeyboardTray = Color(0xFFD1D4DA)
    val KeyCap = Color(0xFFFFFFFF)
    /** Pressed / latched key, and the darker iOS function-key shade. */
    val KeyCapPressed = Color(0xFFACB1BA)
}

/**
 * iOS Dynamic Type at the default ("Large") content size, in sp so that the Android
 * font scale still applies. The family is the system sans-serif; Japanese falls back
 * to the system CJK font. Colors are left unspecified so `LocalContentColor` applies.
 */
object IosType {
    val LargeTitle = iosTextStyle(34, 41, FontWeight.Bold)
    val Title1 = iosTextStyle(28, 34)
    val Title2 = iosTextStyle(22, 28)
    val Title3 = iosTextStyle(20, 25)
    val Headline = iosTextStyle(17, 22, FontWeight.SemiBold)
    val Body = iosTextStyle(17, 22)
    val Callout = iosTextStyle(16, 21)
    val Subheadline = iosTextStyle(15, 20)
    val Footnote = iosTextStyle(13, 18)
    val Caption1 = iosTextStyle(12, 16)
    val Caption2 = iosTextStyle(11, 13)
}

// Centering the glyphs in the fixed line height keeps CJK and Latin text on the same
// optical center inside buttons and rows.
private val IosLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun iosTextStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    lineHeightStyle = IosLineHeightStyle,
)

/**
 * Legacy palette kept for screens that have not moved to [IosColors] yet. Every name is
 * mapped onto the iOS palette; the text colors are the readable variants.
 */
object OmochiColors {
    val Window = IosColors.GroupedBackground
    val Surface = IosColors.SecondaryGroupedBackground
    val Raised = IosColors.SystemBackground
    val Border = IosColors.OpaqueSeparator
    val Divider = IosColors.Gray5
    val Ink = IosColors.Label
    /** Opaque secondary label that keeps 4.5:1 on white and on [Window] for small text. */
    val Muted = Color(0xFF6E6E73)
    val Accent = IosColors.Blue
    val AccentDark = IosColors.BluePressed
    /** System blue at 12% over white. */
    val AccentSoft = Color(0xFFE0EFFF)
    val Blue = IosColors.Blue
    val Green = IosColors.Green
    /** Used for warning text and the "starting" dot, so it is a readable amber. */
    val Yellow = Color(0xFFA05A00)
    val Red = IosColors.RedText
    /** Dark tray kept until the touch key panel moves to [IosColors.KeyboardTray]; its text is white. */
    val Terminal = Color(0xFF1C1C1E)
}

private val OmochiScheme: ColorScheme = lightColorScheme(
    primary = IosColors.Blue,
    onPrimary = Color.White,
    primaryContainer = OmochiColors.AccentSoft,
    onPrimaryContainer = IosColors.BluePressed,
    inversePrimary = Color(0xFF0A84FF),
    secondary = IosColors.Gray,
    onSecondary = Color.White,
    secondaryContainer = IosColors.Gray5,
    onSecondaryContainer = IosColors.Label,
    tertiary = IosColors.Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF3F7),
    onTertiaryContainer = Color(0xFF0A5C6B),
    background = IosColors.GroupedBackground,
    onBackground = IosColors.Label,
    surface = IosColors.SecondaryGroupedBackground,
    onSurface = IosColors.Label,
    surfaceVariant = IosColors.Gray6,
    onSurfaceVariant = OmochiColors.Muted,
    // No tonal tint: iOS surfaces stay white at every elevation.
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color.White,
    error = IosColors.RedText,
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = IosColors.RedText,
    outline = IosColors.OpaqueSeparator,
    outlineVariant = IosColors.Gray5,
    scrim = Color.Black,
    surfaceBright = IosColors.SystemBackground,
    surfaceDim = IosColors.Gray5,
    surfaceContainerLowest = IosColors.SystemBackground,
    surfaceContainerLow = IosColors.SystemBackground,
    surfaceContainer = Color(0xFFF9F9FB),
    surfaceContainerHigh = IosColors.SystemBackground,
    surfaceContainerHighest = IosColors.Gray6,
)

private val OmochiTypography = Typography(
    displayLarge = IosType.LargeTitle,
    displayMedium = IosType.LargeTitle,
    displaySmall = IosType.LargeTitle,
    headlineLarge = IosType.Title1.copy(fontWeight = FontWeight.Bold),
    headlineMedium = IosType.Title2.copy(fontWeight = FontWeight.Bold),
    headlineSmall = IosType.Title2.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = IosType.Title3.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = IosType.Headline,
    titleSmall = IosType.Subheadline.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = IosType.Body,
    bodyMedium = IosType.Subheadline,
    bodySmall = IosType.Footnote,
    labelLarge = IosType.Callout.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = IosType.Caption1.copy(fontWeight = FontWeight.Medium),
    labelSmall = IosType.Caption2.copy(fontWeight = FontWeight.Medium),
)

// Circular-arc corners keep Material components on HWUI's fast rounded-rect path;
// glass and grouped sections use ContinuousRoundedShape explicitly.
private val OmochiShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(38.dp),
)

@Composable
fun OmochiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmochiScheme,
        typography = OmochiTypography,
        shapes = OmochiShapes,
        content = content,
    )
}
