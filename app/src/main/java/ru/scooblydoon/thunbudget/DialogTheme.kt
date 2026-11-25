package ru.scooblydoon.thunbudget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shared visual system for every modal in ThunBudget. */
@Composable
internal fun themedDialogContainerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF262630)
    else Color(0xFFFFFEFF)

@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable () -> Unit = {},
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Unspecified
) {
    val base = MaterialTheme.colorScheme
    val isDark = base.background.luminance() < 0.5f
    val surfaceColor = if (isDark) Color(0xFF262630) else Color(0xFFFFFEFF)
    val foreground = if (isDark) Color(0xFFF4F2FF) else Color(0xFF24212B)
    val secondary = if (isDark) Color(0xFFB8B4C5) else Color(0xFF6D6875)
    val rowSurface = if (isDark) Color(0xFF373741) else Color(0xFFF0ECF2)
    val primary = if (isDark) Color(0xFFB7A0FF) else Color(0xFF6556A4)
    val error = if (isDark) Color(0xFFFF7777) else Color(0xFFC74444)
    val outline = if (isDark) Color(0xFF686575) else Color(0xFF898591)

    val dialogColors = base.copy(
        primary = primary,
        onPrimary = if (isDark) Color(0xFF241B3D) else Color.White,
        secondary = if (isDark) Color(0xFF75D9CF) else base.secondary,
        surface = surfaceColor,
        onSurface = foreground,
        surfaceVariant = rowSurface,
        onSurfaceVariant = secondary,
        error = error,
        outline = outline,
        outlineVariant = outline.copy(alpha = 0.58f)
    )
    val dialogShapes = MaterialTheme.shapes.copy(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(24.dp)
    )

    MaterialTheme(colorScheme = dialogColors, shapes = dialogShapes, typography = MaterialTheme.typography) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
        ) {
            Surface(
                modifier = modifier.fillMaxWidth(0.92f).widthIn(max = 440.dp),
                shape = RoundedCornerShape(24.dp),
                color = containerColor.takeIf { it != Color.Unspecified } ?: surfaceColor,
                contentColor = foreground,
                border = BorderStroke(1.dp, outline.copy(alpha = if (isDark) 0.34f else 0.22f)),
                tonalElevation = 0.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (icon != null) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { icon() }
                        }
                    }
                    if (title != null) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = foreground
                        )) { title() }
                    }
                    if (text != null) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp, lineHeight = 20.sp, color = foreground
                        )) { text() }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)) {
                            dismissButton()
                            Spacer(Modifier.widthIn(min = 8.dp))
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
