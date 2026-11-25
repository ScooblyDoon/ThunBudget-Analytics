package ru.scooblydoon.thunbudget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AssistantChartDialog(
    title: String,
    values: List<Pair<String, Double>>,
    onDismiss: () -> Unit
) {
    val maxValue = values.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    AppAlertDialog(
                onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                values.forEach { (label, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(String.format(java.util.Locale("ru", "RU"), "%,.0f ₽", value), style = MaterialTheme.typography.labelMedium)
                        }
                        Box(Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
                            Box(Modifier.fillMaxWidth((value / maxValue).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
