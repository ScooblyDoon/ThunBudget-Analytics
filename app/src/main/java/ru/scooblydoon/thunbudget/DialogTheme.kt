package ru.scooblydoon.thunbudget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Composable
internal fun themedDialogContainerColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.White
    }
