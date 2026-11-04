package io.github.justcallmesu.aristotle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import io.github.justcallmesu.aristotle.data.TextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeScaleTest {
    private fun Typography.slots(): List<TextStyle> = listOf(
        displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall, titleLarge, titleMedium,
        titleSmall, bodyLarge, bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall,
    )

    private fun everyStyle(size: TextSize) = aristotleTypography(size.scale).slots() + AristotleStyles(size.scale).all

    @Test
    fun textSizesAreOrderedAndDefaultIsTheDesignTable() {
        assertEquals(listOf(TextSize.Small, TextSize.Default, TextSize.Large), TextSize.entries.sortedBy { it.scale })
        val type = aristotleTypography(TextSize.Default.scale)
        assertEquals(16f, type.bodyLarge.fontSize.value, 0f)
        assertEquals(24f, type.bodyLarge.lineHeight.value, 0f)
        assertEquals(11.5f, AristotleStyles(1f).toolOutput.fontSize.value, 0f)
    }

    @Test
    fun noStyleRendersBelowElevenSpAtAnyTextSize() {
        for (size in TextSize.entries) {
            for (style in everyStyle(size)) {
                assertTrue("$size: ${style.fontSize}", style.fontSize.value >= MinTextSize.value)
            }
        }
    }

    @Test
    fun smallClampsAtTheFloorAndKeepsProportions() {
        val small = aristotleTypography(TextSize.Small.scale)
        assertEquals(14.4f, small.bodyLarge.fontSize.value, 0.001f)
        assertEquals(21.6f, small.bodyLarge.lineHeight.value, 0.001f)
        // labelSmall is 11 sp already: the floor holds it, line height unchanged.
        assertEquals(11f, small.labelSmall.fontSize.value, 0.001f)
        assertEquals(16f, small.labelSmall.lineHeight.value, 0.001f)
        // Tool output 11.5 sp would be 10.35: lifted to 11, its line height by the same factor.
        val output = AristotleStyles(TextSize.Small.scale).toolOutput
        assertEquals(11f, output.fontSize.value, 0.001f)
        assertEquals(17f * 11f / 11.5f, output.lineHeight.value, 0.001f)
    }

    @Test
    fun largeScalesEveryStyle() {
        val default = everyStyle(TextSize.Default)
        val large = everyStyle(TextSize.Large)
        default.zip(large).forEach { (d, l) -> assertEquals(d.fontSize.value * 1.15f, l.fontSize.value, 0.001f) }
        assertEquals(AristotleStyles(1f).chatText.fontFamily, AristotleStyles(1.15f).chatText.fontFamily)
    }
}
