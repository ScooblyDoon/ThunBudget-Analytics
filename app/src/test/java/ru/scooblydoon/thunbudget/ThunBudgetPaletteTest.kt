package ru.scooblydoon.thunbudget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThunBudgetPaletteTest {
    @Test
    fun lightThemeRestoresSlateBudgetCardAndGraySurfaces() {
        assertEquals(Color(0xFFF4F6F8), ThunBudgetPalette.Light.background)
        assertEquals(Color(0xFFE6E0E9), ThunBudgetPalette.Light.surfaceVariant)
        assertEquals(Color(0xFF445365), ThunBudgetPalette.Light.primary)
        assertEquals(Color(0xFFE6E0E9), ThunBudgetPalette.Light.secondaryContainer)
        assertEquals(Color(0xFFF3DCE7), ThunBudgetPalette.Light.tertiaryContainer)
        assertEquals(Color(0xFF445365), ThunBudgetPalette.Light.budgetCardSurface)
        assertEquals(Color(0xFFFFFFFF), ThunBudgetPalette.Light.budgetCardContent)
        assertEquals(Color(0xFFFFFFFF), ThunBudgetPalette.Light.budgetCardProgress)
        assertEquals(Color(0xFF8290A0), ThunBudgetPalette.Light.budgetCardProgressTrack)
        assertEquals(Color(0xFFE6E0E9), ThunBudgetPalette.Light.categoryCardSurface)
        assertEquals(Color(0xFFD8D2DC), ThunBudgetPalette.Light.progressTrack)
    }

    @Test
    fun darkThemeRestoresBrightVioletBudgetCardAndGraphiteSurfaces() {
        assertEquals(Color(0xFF090A11), ThunBudgetPalette.Dark.background)
        assertEquals(Color(0xFF171821), ThunBudgetPalette.Dark.surface)
        assertEquals(Color(0xFF36343C), ThunBudgetPalette.Dark.surfaceVariant)
        assertEquals(Color(0xFFB49BFE), ThunBudgetPalette.Dark.primary)
        assertEquals(Color(0xFF3C344B), ThunBudgetPalette.Dark.primaryContainer)
        assertEquals(Color(0xFF3C344B), ThunBudgetPalette.Dark.secondaryContainer)
        assertEquals(Color(0xFF623B48), ThunBudgetPalette.Dark.tertiaryContainer)
        assertEquals(Color(0xFFB49BFE), ThunBudgetPalette.Dark.budgetCardSurface)
        assertEquals(Color(0xFF1B1220), ThunBudgetPalette.Dark.budgetCardContent)
        assertEquals(Color(0xFF1B1220), ThunBudgetPalette.Dark.budgetCardProgress)
        assertEquals(Color(0xFF927CCE), ThunBudgetPalette.Dark.budgetCardProgressTrack)
        assertEquals(Color(0xFF36343C), ThunBudgetPalette.Dark.categoryCardSurface)
        assertEquals(Color(0xFF21222D), ThunBudgetPalette.Dark.dialogSurface)
        assertEquals(Color(0xFF343440), ThunBudgetPalette.Dark.dialogField)
    }

    @Test
    fun controlsUseThemeAccentWhileFinancialResultsRemainSemantic() {
        assertEquals(Color(0xFFE6E0E9), ThunBudgetPalette.Light.secondaryContainer)
        assertEquals(Color(0xFF3C344B), ThunBudgetPalette.Dark.secondaryContainer)
        assertTrue(ThunBudgetPalette.Light.secondaryContainer != ThunBudgetPalette.Light.positive)
        assertTrue(ThunBudgetPalette.Dark.secondaryContainer != ThunBudgetPalette.Dark.positive)
        assertEquals(Color(0xFF168974), ThunBudgetPalette.Light.positive)
        assertEquals(Color(0xFF168A72), ThunBudgetPalette.Dark.positive)
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
    fun chartSeriesUsesTheApprovedMultiColorCategoryPalette() {
        assertTrue(ThunBudgetPalette.ChartSeries.size >= 5)
        assertEquals(Color(0xFF315CBA), ThunBudgetPalette.ChartSeries.first())
        assertEquals(ThunBudgetPalette.ChartSeries.size, ThunBudgetPalette.ChartSeries.distinct().size)
        assertEquals(Color(0xFF168A72), ThunBudgetPalette.ChartSeries[1])
        assertEquals(Color(0xFFF2A541), ThunBudgetPalette.ChartSeries[2])
        assertEquals(Color(0xFF9A6AD6), ThunBudgetPalette.ChartSeries[3])
    }
}
