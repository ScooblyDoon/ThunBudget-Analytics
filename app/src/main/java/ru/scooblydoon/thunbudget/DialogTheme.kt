package ru.scooblydoon.thunbudget

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

/**
 * One explicit dialog implementation shared by every modal in the app.
 * We intentionally use Dialog + Surface instead of Material3 AlertDialog:
 * the dialog surface and its content colors are now set directly and cannot
 * fall back to the platform/default light AlertDialog container.
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
    modifier: Modifier = Modifier,
    dismissButton: @Composable () -> Unit = {},
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = themedDialogContainerColor()
) {
    val base = MaterialTheme.colorScheme
    val isDark = base.background.luminance() < 0.5f
    val surfaceColor = if (isDark) Color(0xFF262630) else Color.White
    val foreground = if (isDark) Color(0xFFF4F2FF) else Color(0xFF24212B)
    val secondary = if (isDark) Color(0xFFB8B4C5) else Color(0xFF6D6875)
    val rowSurface = if (isDark) Color(0xFF373741) else Color(0xFFF0ECF2)
    val primary = if (isDark) Color(0xFFB7A0FF) else Color(0xFF6556A4)
    val error = if (isDark) Color(0xFFFF6464) else Color(0xFFC74444)
    val outline = if (isDark) Color(0xFF686575) else Color(0xFF898591)

    val dialogColors = base.copy(
        primary = primary,
        onPrimary = if (isDark) Color(0xFF241B3D) else Color.White,
        surface = surfaceColor,
        onSurface = foreground,
        surfaceVariant = rowSurface,
        onSurfaceVariant = secondary,
        error = error,
        outline = outline,
        outlineVariant = outline.copy(alpha = 0.58f)
    )

    MaterialTheme(
        colorScheme = dialogColors,
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(50),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(30.dp)
        ),
        typography = MaterialTheme.typography
    ) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true
            )
        ) {
            Surface(
                modifier = modifier
                    .fillMaxWidth(0.90f)
                    .widthIn(max = 560.dp),
                shape = RoundedCornerShape(30.dp),
                color = containerColor.takeIf { it != Color.Unspecified } ?: surfaceColor,
                contentColor = foreground,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 24.dp,
                        top = 22.dp,
                        end = 24.dp,
                        bottom = 18.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (icon != null) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) { icon() }
                        }
                    }
                    if (title != null) {
                        ProvideTextStyle(
                            MaterialTheme.typography.titleLarge.copy(
                                fontSize = 24.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = foreground
                            )
                        ) { title() }
                    }
                    if (text != null) {
                        ProvideTextStyle(
                            MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                color = foreground
                            )
                        ) { text() }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton()
                        Spacer(modifier = Modifier.widthIn(min = 8.dp))
                        confirmButton()
                    }
                }
            }
        }
    }
}
