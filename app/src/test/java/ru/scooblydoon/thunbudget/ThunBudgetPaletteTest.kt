package ru.scooblydoon.thunbudget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThunBudgetPaletteTest {
    @Test
    fun lightThemeUsesCalmVioletSurfacesAndControls() {
        assertEquals(Color(0xFFF5F2F8), ThunBudgetPalette.Light.background)
        assertEquals(Color(0xFFEEE8F4), ThunBudgetPalette.Light.surfaceVariant)
        assertEquals(Color(0xFF6F5B89), ThunBudgetPalette.Light.primary)
        assertEquals(Color(0xFF6F5B89), ThunBudgetPalette.Light.budgetCardSurface)
        assertEquals(Color(0xFF168974), ThunBudgetPalette.Light.positive)
        assertEquals(Color(0xFFDFA44B), ThunBudgetPalette.Light.warning)
        assertEquals(Color(0xFFD36F83), ThunBudgetPalette.Light.danger)
    }

    @Test
    fun darkThemeUsesCalmVioletAndKeepsGraphiteDialogs() {
        assertEquals(Color(0xFF090A10), ThunBudgetPalette.Dark.background)
        assertEquals(Color(0xFF17141F), ThunBudgetPalette.Dark.surface)
        assertEquals(Color(0xFF2B2536), ThunBudgetPalette.Dark.surfaceVariant)
        assertEquals(Color(0xFFA58EC6), ThunBudgetPalette.Dark.primary)
        assertEquals(Color(0xFF30283B), ThunBudgetPalette.Dark.budgetCardSurface)
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
        assertTrue(
            "Budget progress fill must be distinct from its track",
            ThunBudgetPalette.Dark.budgetCardProgress != ThunBudgetPalette.Dark.budgetCardProgressTrack
        )
    }

    @Test
    fun chartSeriesStartsWithVioletAndKeepsCategoryColorsDistinct() {
        assertTrue(ThunBudgetPalette.ChartSeries.size >= 5)
        assertEquals(Color(0xFFA58EC6), ThunBudgetPalette.ChartSeries.first())
        assertEquals(ThunBudgetPalette.ChartSeries.size, ThunBudgetPalette.ChartSeries.distinct().size)
        assertEquals(Color(0xFF7F9DB8), ThunBudgetPalette.ChartSeries[1])
        assertEquals(Color(0xFF70A58F), ThunBudgetPalette.ChartSeries[2])
        assertEquals(Color(0xFFD6A55F), ThunBudgetPalette.ChartSeries[3])
    }
}
