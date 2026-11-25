package ru.scooblydoon.thunbudget

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual contract for every dialog in the application.
 *
 * Dark mode follows the graphite reference surface. Light mode uses a white
 * surface. Content surfaces, text, fields, outlines and actions are themed
 * together so a dialog cannot accidentally retain the light palette.
 */
@Composable
internal fun themedDialogContainerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF262630)
    } else {
        Color.White
    }

@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    dismissButton: @Composable () -> Unit = {},
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = themedDialogContainerColor()
) {
    val baseColors = MaterialTheme.colorScheme
    val baseShapes = MaterialTheme.shapes
    val baseTypography = MaterialTheme.typography
    val isDark = baseColors.background.luminance() < 0.5f

    val dialogSurface = if (isDark) Color(0xFF262630) else Color.White
    val dialogText = if (isDark) Color(0xFFF4F2FF) else Color(0xFF24212B)
    val dialogSecondaryText = if (isDark) Color(0xFFB8B4C5) else Color(0xFF6D6875)
    val dialogRowSurface = if (isDark) Color(0xFF373741) else Color(0xFFF0ECF2)
    val dialogPrimary = if (isDark) Color(0xFFB7A0FF) else Color(0xFF6556A4)
    val dialogError = if (isDark) Color(0xFFFF6464) else Color(0xFFC74444)
    val dialogOutline = if (isDark) Color(0xFF686575) else Color(0xFF898591)

    val dialogColors = baseColors.copy(
        primary = dialogPrimary,
        onPrimary = if (isDark) Color(0xFF241B3D) else Color.White,
        surface = dialogSurface,
        onSurface = dialogText,
        surfaceVariant = dialogRowSurface,
        onSurfaceVariant = dialogSecondaryText,
        error = dialogError,
        outline = dialogOutline,
        outlineVariant = dialogOutline.copy(alpha = 0.58f)
    )

    MaterialTheme(
        colorScheme = dialogColors,
        shapes = baseShapes.copy(
            small = RoundedCornerShape(50),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(30.dp)
        ),
        typography = baseTypography
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title?.let {
                {
                    androidx.compose.material3.ProvideTextStyle(
                        MaterialTheme.typography.titleLarge.copy(
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = dialogText
                        )
                    ) { it() }
                }
            },
            text = text?.let {
                {
                    androidx.compose.material3.ProvideTextStyle(
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            color = dialogText
                        )
                    ) { it() }
                }
            },
            shape = RoundedCornerShape(30.dp),
            containerColor = containerColor.takeIf { it != Color.Unspecified } ?: dialogSurface,
            iconContentColor = dialogPrimary,
            titleContentColor = dialogText,
            textContentColor = dialogText,
            tonalElevation = 0.dp
        )
    }
}
