package ru.scooblydoon.thunbudget

import androidx.compose.ui.graphics.Color

/**
 * Centralized visual tokens for the Balance Pro design system.
 *
 * Values in this first extraction match the current shipped theme. The
 * Balance Pro acceptance tests intentionally describe the approved target
 * palette; the implementation pass will move these tokens to that target.
 */
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
    val dialogOutline: Color
)

internal object ThunBudgetPalette {
    val Light = ThunBudgetThemePalette(
        background = Color(0xFFF4F6F8),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F2F5),
        primary = Color(0xFF445264),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE9E3F2),
        onPrimaryContainer = Color(0xFF40364F),
        secondary = Color(0xFF6BA88E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEAE4F2),
        onSecondaryContainer = Color(0xFF493E59),
        tertiary = Color(0xFF6B7280),
        onTertiary = Color(0xFFFFFFFF),
        positive = Color(0xFF168A72),
        warning = Color(0xFFF2A541),
        danger = Color(0xFFD77A72),
        outline = Color(0xFFD9DEE5),
        onSurface = Color(0xFF1F2937),
        onSurfaceVariant = Color(0xFF6B7280),
        dialogSurface = Color(0xFFFFFFFF),
        dialogField = Color(0xFFF0F2F5),
        dialogOutline = Color(0xFFD9DEE5)
    )

    val Dark = ThunBudgetThemePalette(
        background = Color(0xFF090A10),
        surface = Color(0xFF171821),
        surfaceVariant = Color(0xFF242631),
        primary = Color(0xFFB59AFF),
        onPrimary = Color(0xFF171321),
        primaryContainer = Color(0xFF443B58),
        onPrimaryContainer = Color(0xFFEDE5FF),
        secondary = Color(0xFF6BA88E),
        onSecondary = Color(0xFF10251D),
        secondaryContainer = Color(0xFF3B344B),
        onSecondaryContainer = Color(0xFFE6DDF8),
        tertiary = Color(0xFF9AA3B2),
        onTertiary = Color(0xFF171821),
        positive = Color(0xFF6BA88E),
        warning = Color(0xFFF2A541),
        danger = Color(0xFFFF817A),
        outline = Color(0xFF50535F),
        onSurface = Color(0xFFF3F2F8),
        onSurfaceVariant = Color(0xFFB4B7C2),
        dialogSurface = Color(0xFF21222D),
        dialogField = Color(0xFF343440),
        dialogOutline = Color(0xFF50535F)
    )

    /** Shared category palette, ordered and reused by period/month/year charts. */
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
