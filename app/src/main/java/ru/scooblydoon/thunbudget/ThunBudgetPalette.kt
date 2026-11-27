package ru.scooblydoon.thunbudget

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance

/** Shared Balance Pro palette for light/dark surfaces, controls, progress and charts. */
internal data class ThunBudgetThemePalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val positive: Color,
    val warning: Color,
    val danger: Color,
    val outline: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val dialogSurface: Color,
    val dialogField: Color,
    val dialogOutline: Color,
    val budgetCardSurface: Color,
    val budgetCardContent: Color,
    val budgetCardProgress: Color,
    val budgetCardProgressTrack: Color,
    val categoryCardSurface: Color,
    val progressTrack: Color,
    val chartPrimary: Color
)

internal object ThunBudgetPalette {
    val Light = ThunBudgetThemePalette(
        background = Color(0xFFF4F6F8),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE6E0E9),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F6F8),
        surfaceContainer = Color(0xFFECE8EF),
        surfaceContainerHigh = Color(0xFFE6E0E9),
        surfaceContainerHighest = Color(0xFFDDD8E1),
        primary = Color(0xFF445365),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE6E0E9),
        onPrimaryContainer = Color(0xFF263445),
        secondary = Color(0xFF168974),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE6E0E9),
        onSecondaryContainer = Color(0xFF445365),
        tertiary = Color(0xFFDFA44B),
        onTertiary = Color(0xFF1F2937),
        tertiaryContainer = Color(0xFFF3DCE7),
        onTertiaryContainer = Color(0xFF4A263A),
        positive = Color(0xFF168974),
        warning = Color(0xFFDFA44B),
        danger = Color(0xFFD36F83),
        outline = Color(0xFFDDD5E5),
        onSurface = Color(0xFF202A35),
        onSurfaceVariant = Color(0xFF66717E),
        dialogSurface = Color(0xFFFFFFFF),
        dialogField = Color(0xFFF0F2F5),
        dialogOutline = Color(0xFFD9DEE5),
        budgetCardSurface = Color(0xFF445365),
        budgetCardContent = Color(0xFFFFFFFF),
        budgetCardProgress = Color(0xFFFFFFFF),
        budgetCardProgressTrack = Color(0xFF8290A0),
        categoryCardSurface = Color(0xFFE6E0E9),
        progressTrack = Color(0xFFD8D2DC),
        chartPrimary = Color(0xFF445365)
    )

    val Dark = ThunBudgetThemePalette(
        background = Color(0xFF090A11),
        surface = Color(0xFF171821),
        surfaceVariant = Color(0xFF36343C),
        surfaceContainerLowest = Color(0xFF090A11),
        surfaceContainerLow = Color(0xFF171821),
        surfaceContainer = Color(0xFF24242D),
        surfaceContainerHigh = Color(0xFF2C2D35),
        surfaceContainerHighest = Color(0xFF36343C),
        primary = Color(0xFFB49BFE),
        onPrimary = Color(0xFF1B1220),
        primaryContainer = Color(0xFF3C344B),
        onPrimaryContainer = Color(0xFFF3F2F9),
        secondary = Color(0xFF168A72),
        onSecondary = Color(0xFF10251D),
        secondaryContainer = Color(0xFF3C344B),
        onSecondaryContainer = Color(0xFFF3F2F9),
        tertiary = Color(0xFFDFA44B),
        onTertiary = Color(0xFF171821),
        tertiaryContainer = Color(0xFF623B48),
        onTertiaryContainer = Color(0xFFF8EAF0),
        positive = Color(0xFF168A72),
        warning = Color(0xFFDFA44B),
        danger = Color(0xFFFF817A),
        outline = Color(0xFF66636D),
        onSurface = Color(0xFFF3F2F9),
        onSurfaceVariant = Color(0xFFB8B6C0),
        dialogSurface = Color(0xFF21222D),
        dialogField = Color(0xFF343440),
        dialogOutline = Color(0xFF585364),
        budgetCardSurface = Color(0xFFB49BFE),
        budgetCardContent = Color(0xFF1B1220),
        budgetCardProgress = Color(0xFF1B1220),
        budgetCardProgressTrack = Color(0xFF927CCE),
        categoryCardSurface = Color(0xFF36343C),
        progressTrack = Color(0xFF383548),
        chartPrimary = Color(0xFFB49BFE)
    )

    /** Established multi-color category chart palette; controls remain violet, chart series stay distinct. */
    val ChartSeries = listOf(
        Color(0xFF315CBA),
        Color(0xFF168A72),
        Color(0xFFF2A541),
        Color(0xFF9A6AD6),
        Color(0xFFE16C70),
        Color(0xFF5C9DB8),
        Color(0xFF8B9A45),
        Color(0xFF7A8290)
    )
}


/** Neutral gray card surface used for category statistics and expense breakdowns. */
@Composable
internal fun thunBudgetCategoryCardSurface(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        ThunBudgetPalette.Dark.categoryCardSurface
    } else {
        ThunBudgetPalette.Light.categoryCardSurface
    }


/** Dark-plum track for actual expense execution indicators. */
@Composable
internal fun thunBudgetProgressTrackColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        ThunBudgetPalette.Dark.progressTrack
    } else {
        ThunBudgetPalette.Light.progressTrack
    }
