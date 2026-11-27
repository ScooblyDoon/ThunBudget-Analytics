package ru.scooblydoon.thunbudget

import androidx.compose.ui.graphics.Color

/** Shared Balance Pro palette for the light and dark application surfaces. */
internal data class ThunBudgetThemePalette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
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
    val budgetCardContent: Color
)

internal object ThunBudgetPalette {
    val Light = ThunBudgetThemePalette(
        background = Color(0xFFF4F6F8),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE6E0E9),
        primary = Color(0xFF445365),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE6E0E9),
        onPrimaryContainer = Color(0xFF445365),
        secondary = Color(0xFF168974),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE6E0E9),
        onSecondaryContainer = Color(0xFF445365),
        tertiary = Color(0xFFDFA44B),
        onTertiary = Color(0xFF1F2937),
        positive = Color(0xFF168974),
        warning = Color(0xFFDFA44B),
        danger = Color(0xFFD96B76),
        outline = Color(0xFFD9DEE5),
        onSurface = Color(0xFF1F2937),
        onSurfaceVariant = Color(0xFF6B7280),
        dialogSurface = Color(0xFFFFFFFF),
        dialogField = Color(0xFFF0F2F5),
        dialogOutline = Color(0xFFD9DEE5),
        budgetCardSurface = Color(0xFF445365),
        budgetCardContent = Color(0xFFFFFFFF)
    )

    val Dark = ThunBudgetThemePalette(
        background = Color(0xFF090A10),
        surface = Color(0xFF171821),
        surfaceVariant = Color(0xFF242631),
        primary = Color(0xFF89849A),
        onPrimary = Color(0xFFF3F2F8),
        primaryContainer = Color(0xFF343440),
        onPrimaryContainer = Color(0xFFF3F2F8),
        secondary = Color(0xFF6BA88E),
        onSecondary = Color(0xFF10251D),
        secondaryContainer = Color(0xFF343440),
        onSecondaryContainer = Color(0xFFF3F2F8),
        tertiary = Color(0xFFDFA44B),
        onTertiary = Color(0xFF171821),
        positive = Color(0xFF6BA88E),
        warning = Color(0xFFDFA44B),
        danger = Color(0xFFFF817A),
        outline = Color(0xFF50535F),
        onSurface = Color(0xFFF3F2F8),
        onSurfaceVariant = Color(0xFFB4B7C2),
        dialogSurface = Color(0xFF21222D),
        dialogField = Color(0xFF343440),
        dialogOutline = Color(0xFF50535F),
        budgetCardSurface = Color(0xFF242631),
        budgetCardContent = Color(0xFFF3F2F8)
    )

    /** One stable category palette reused by period, month, year and flexible-period charts. */
    val ChartSeries = listOf(
        Color(0xFF445365),
        Color(0xFF7189A3),
        Color(0xFF168974),
        Color(0xFFDFA44B),
        Color(0xFFD96B76),
        Color(0xFF5C9DB8),
        Color(0xFF8B9A45),
        Color(0xFF7A8290)
    )
}
