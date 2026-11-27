package ru.scooblydoon.thunbudget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThunBudgetPaletteTest {
    @Test
    fun lightThemeMatchesApprovedBalanceProPalette() {
        assertEquals(Color(0xFFF4F6F8), ThunBudgetPalette.Light.background)
        assertEquals(Color(0xFFFFFFFF), ThunBudgetPalette.Light.surface)
        assertEquals(Color(0xFFE6E0E9), ThunBudgetPalette.Light.surfaceVariant)
        assertEquals(Color(0xFF445365), ThunBudgetPalette.Light.primary)
        assertEquals(Color(0xFF168974), ThunBudgetPalette.Light.secondary)
        assertEquals(Color(0xFFDFA44B), ThunBudgetPalette.Light.tertiary)
        assertEquals(Color(0xFFD96B76), ThunBudgetPalette.Light.danger)
        assertEquals(Color(0xFFD9DEE5), ThunBudgetPalette.Light.outline)
    }

    @Test
    fun darkThemeUsesMutedAccentAndPreservesGraphiteDialogs() {
        assertEquals(Color(0xFF090A10), ThunBudgetPalette.Dark.background)
        assertEquals(Color(0xFF171821), ThunBudgetPalette.Dark.surface)
        assertEquals(Color(0xFF242631), ThunBudgetPalette.Dark.surfaceVariant)
        assertEquals(Color(0xFF89849A), ThunBudgetPalette.Dark.primary)
        assertEquals(Color(0xFF171821), ThunBudgetPalette.Dark.onPrimary)
        assertEquals(Color(0xFF21222D), ThunBudgetPalette.Dark.dialogSurface)
        assertEquals(Color(0xFF343440), ThunBudgetPalette.Dark.dialogField)
    }

    @Test
    fun primaryButtonTextHasAccessibleContrastInBothThemes() {
        fun contrast(foreground: Color, background: Color): Double {
            val first = foreground.luminance().toDouble()
            val second = background.luminance().toDouble()
            val lighter = maxOf(first, second)
            val darker = minOf(first, second)
            return (lighter + 0.05) / (darker + 0.05)
        }

        assertTrue(
            "Light primary button contrast must be at least 4.5:1",
            contrast(ThunBudgetPalette.Light.onPrimary, ThunBudgetPalette.Light.primary) >= 4.5
        )
        assertTrue(
            "Dark primary button contrast must be at least 4.5:1",
            contrast(ThunBudgetPalette.Dark.onPrimary, ThunBudgetPalette.Dark.primary) >= 4.5
        )
    }

    @Test
    fun chartSeriesUsesDistinctBalanceProSemanticColors() {
        assertTrue(ThunBudgetPalette.ChartSeries.size >= 5)
        assertEquals(Color(0xFF445365), ThunBudgetPalette.ChartSeries[0])
        assertEquals(Color(0xFF7189A3), ThunBudgetPalette.ChartSeries[1])
        assertEquals(Color(0xFF168974), ThunBudgetPalette.ChartSeries[2])
        assertEquals(Color(0xFFDFA44B), ThunBudgetPalette.ChartSeries[3])
        assertEquals(Color(0xFFD96B76), ThunBudgetPalette.ChartSeries[4])
    }
}
