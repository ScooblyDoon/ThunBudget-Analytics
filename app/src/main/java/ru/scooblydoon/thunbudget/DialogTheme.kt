package ru.scooblydoon.thunbudget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Keep dialogs visually consistent with category cards. A fixed dark neutral
 * avoids the white platform dialog surface leaking through on dark screens.
 */
@Composable
internal fun themedDialogContainerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF36343B)
    } else {
        Color.White
    }
