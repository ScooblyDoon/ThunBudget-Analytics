package ru.scooblydoon.thunbudget

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One visual contract for every app dialog:
 * - dark mode: graphite surface with light text and violet primary actions;
 * - light mode: white surface with dark text;
 * - identical geometry, typography hierarchy and pill-shaped actions in both modes.
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
    val baseShapes = MaterialTheme.shapes
    MaterialTheme(
        shapes = baseShapes.copy(
            small = RoundedCornerShape(50),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(30.dp)
        )
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
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    ) { it() }
                }
            },
            text = text?.let {
                {
                    androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                }
            },
            shape = RoundedCornerShape(30.dp),
            containerColor = containerColor,
            iconContentColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp
        )
    }
}
