package io.github.hatake716.omochi

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IosDesignFoundationTest {
    @Test
    fun tintedGlassKeepsWhiteTextReadableOverWhite() {
        listOf(IosColors.Blue, IosColors.Red, IosColors.Green, IosColors.Orange, IosColors.Indigo)
            .forEach { tint ->
                val fill = readableTintedGlassFill(tint)
                assertEquals(TINTED_GLASS_ALPHA, fill.alpha, 0.01f)
                val contrast = contrastRatio(Color.White, fill.compositeOver(Color.White))
                assertTrue("contrast for $tint was $contrast", contrast >= 4.5f)
            }
    }

    @Test
    fun tintedGlassLeavesAlreadyDarkTintsAlone() {
        val dark = Color(0xFF1C1C1E)
        assertEquals(dark.copy(alpha = TINTED_GLASS_ALPHA), readableTintedGlassFill(dark))
    }

    @Test
    fun tintedGlassOnlyDeepensSystemBlueSlightly() {
        val fill = readableTintedGlassFill(IosColors.Blue)
        // Still clearly blue: the blue channel dominates and red stays near zero.
        assertTrue(fill.blue > 0.7f)
        assertTrue(fill.red < 0.1f)
        assertNotEquals(IosColors.Blue.copy(alpha = TINTED_GLASS_ALPHA), fill)
    }

    @Test
    fun glassTonesUseTheSpecifiedOpacity() {
        assertEquals(0.78f, glassColors(GlassTone.Regular, IosColors.Blue, 1f).fill.alpha, 0.01f)
        assertEquals(0.90f, glassColors(GlassTone.Thick, IosColors.Blue, 1f).fill.alpha, 0.01f)
        assertEquals(0.88f, glassColors(GlassTone.Tinted, IosColors.Blue, 1f).fill.alpha, 0.01f)
        GlassTone.entries.forEach { tone ->
            val colors = glassColors(tone, IosColors.Blue, 1f)
            // The diffusion only brightens the top of the glass.
            assertTrue(colors.top.alpha >= colors.fill.alpha)
            assertTrue(colors.diffusionEnd in 0f..1f)
        }
        assertEquals(0f, glassColors(GlassTone.Regular, IosColors.Blue, 0f).specularStrong, 0f)
    }

    @Test
    fun readableTextColorsMeetBodyTextContrast() {
        val textColors = mapOf(
            "IosColors.RedText" to IosColors.RedText,
            "IosColors.OrangeText" to IosColors.OrangeText,
            "OmochiColors.Red" to OmochiColors.Red,
            "OmochiColors.Yellow" to OmochiColors.Yellow,
            "OmochiColors.Muted" to OmochiColors.Muted,
            "OmochiColors.Ink" to OmochiColors.Ink,
            "OmochiColors.AccentDark" to OmochiColors.AccentDark,
        )
        val backgrounds = listOf(IosColors.SystemBackground, IosColors.GroupedBackground)
        textColors.forEach { (name, color) ->
            backgrounds.forEach { background ->
                val contrast = contrastRatio(color, background)
                assertTrue("$name on $background was $contrast", contrast >= 4.5f)
            }
        }
    }

    @Test
    fun legacyAccentMapsToSystemBlue() {
        assertEquals(IosColors.Blue, OmochiColors.Accent)
        assertEquals(IosColors.Blue, OmochiColors.Blue)
        assertEquals(IosColors.GroupedBackground, OmochiColors.Window)
    }

    @Test
    fun continuousShapesUseAnalyticShadowOutlines() {
        assertEquals(continuousShadowShape(26.dp), glassShadowShape(ContinuousRoundedShape(26.dp)))
        assertTrue(continuousShadowShape(10.dp) is RoundedCornerShape)
        assertSame(CapsuleShape, glassShadowShape(CapsuleShape))
        assertSame(CircleShape, glassShadowShape(CircleShape))
    }

    @Test
    fun continuousShapeEqualityFollowsRadius() {
        assertEquals(ContinuousRoundedShape(26.dp), ContinuousRoundedShape(26.dp))
        assertEquals(ContinuousRoundedShape(26.dp).hashCode(), ContinuousRoundedShape(26.dp).hashCode())
        assertNotEquals(ContinuousRoundedShape(26.dp), ContinuousRoundedShape(12.dp))
    }

    @Test
    fun segmentSeparatorsHideNextToTheKnob() {
        // Three segments, knob resting on the middle one: both separators touch it.
        assertEquals(0f, segmentSeparatorAlpha(1, 1f), 0f)
        assertEquals(0f, segmentSeparatorAlpha(2, 1f), 0f)
        // Knob on the first segment: only the separator after it is hidden.
        assertEquals(0f, segmentSeparatorAlpha(1, 0f), 0f)
        assertEquals(1f, segmentSeparatorAlpha(2, 0f), 0f)
        // Fades in while the knob slides away.
        val midway = segmentSeparatorAlpha(2, 0.9f)
        assertTrue(midway > 0f && midway < 1f)
        assertEquals(1f, segmentSeparatorAlpha(1, 2f), 0f)
    }

    @Test
    fun activityIndicatorSpokesFadeBehindTheLeader() {
        assertEquals(1f, activityIndicatorSpokeAlpha(0), 0f)
        var previous = activityIndicatorSpokeAlpha(0)
        for (k in 1 until SPINNER_SPOKES) {
            val alpha = activityIndicatorSpokeAlpha(k)
            assertTrue(alpha < previous)
            assertTrue(alpha > 0f)
            previous = alpha
        }
        assertEquals(activityIndicatorSpokeAlpha(1), activityIndicatorSpokeAlpha(SPINNER_SPOKES + 1), 0f)
    }

    @Test
    fun contrastRatioMatchesWcagExtremes() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrastRatio(IosColors.Blue, IosColors.Blue), 0.0001f)
    }
}
