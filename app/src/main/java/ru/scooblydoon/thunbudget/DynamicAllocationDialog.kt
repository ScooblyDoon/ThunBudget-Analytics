package ru.scooblydoon.thunbudget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun DynamicAllocationDialog(
    categories: List<BudgetCategory>,
    expenses: List<Expense>,
    periodStart: String,
    periodEnd: String,
    onDismiss: () -> Unit,
    onSave: (List<BudgetCategory>) -> Unit
) {
    val regular = categories.filterNot { it.name.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) }
    val facts = remember(expenses, periodStart, periodEnd) {
        expenses.filter { it.date >= periodStart && it.date <= periodEnd }
            .groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
    }
    val proposal = remember(categories, facts) {
        AnalyticsBudgetCalculator.dynamicProposal(regular.map { it.name to it.percent }, facts)
    }
    val values = remember(proposal) {
        mutableStateMapOf<String, String>().apply {
            proposal.percentages.forEach { (name, percent) -> put(name, String.format(Locale.US, "%.1f", percent)) }
        }
    }
    val sum = values.values.sumOf { it.replace(',', '.').toDoubleOrNull() ?: 0.0 }
    val valid = values.values.all { raw -> raw.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Динамический прогноз долей") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                Text("Фактические расходы из раздела «Обзор»: $periodStart — $periodEnd")
                Text("Предложение подстраивает доли под реальные траты. Категории без расходов сохраняют относительную часть прежнего бюджета.", style = MaterialTheme.typography.bodySmall)
                Text("Фактически потрачено: ${String.format(Locale("ru", "RU"), "%,.0f ₽", proposal.totalActualSpend)}", fontWeight = FontWeight.SemiBold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(regular, key = { it.name }) { category ->
                        val spent = facts[category.name] ?: 0.0
                        val proposed = values[category.name].orEmpty()
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(category.name, fontWeight = FontWeight.SemiBold)
                                        Text("Факт: ${String.format(Locale("ru", "RU"), "%,.0f ₽", spent)} · Было: ${String.format(Locale("ru", "RU"), "%.1f", category.percent)}%", style = MaterialTheme.typography.labelSmall)
                                    }
                                    OutlinedTextField(
                                        value = proposed,
                                        onValueChange = { values[category.name] = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) },
                                        modifier = Modifier.width(92.dp),
                                        singleLine = true,
                                        label = { Text("Новая %") }
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Сумма новых долей: ${String.format(Locale("ru", "RU"), "%.1f", sum)}%", color = if (!valid || sum > 100.0001) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text("Резерв: ${String.format(Locale("ru", "RU"), "%.1f", (100.0 - sum).coerceAtLeast(0.0))}%", style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = {
            TextButton(enabled = valid && sum <= 100.0001 && regular.isNotEmpty(), onClick = {
                val updated = regular.map { it.copy(percent = values[it.name]!!.replace(',', '.').toDouble()) }
                val withReserve = BudgetAllocationCalculator.withReserve(updated.map { it.name to it.percent })
                val reserve = withReserve.firstOrNull { it.first == BudgetAllocationCalculator.RESERVE_CATEGORY }?.let { BudgetCategory(it.first, it.second) }
                onSave(updated + listOfNotNull(reserve))
            }) { Text("Применить план") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
