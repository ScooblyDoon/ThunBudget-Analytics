package ru.scooblydoon.thunbudget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun AllocationProposalDialog(
    categories: List<BudgetCategory>,
    expenses: List<Expense>,
    onDismiss: () -> Unit,
    onSave: (List<BudgetCategory>) -> Unit
) {
    val regular = categories.filterNot { it.name.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) }
    val allocationExpenses = remember(expenses) {
        expenses.mapNotNull { e ->
            runCatching { AllocationExpense(e.category, e.amount, LocalDate.parse(e.date)) }.getOrNull()
        }
    }
    val suggestions = remember(categories, allocationExpenses) {
        BudgetAllocationCalculator.suggest(
            regular.map { it.name },
            allocationExpenses,
            LocalDate.now()
        ).associateBy { it.category }
    }
    val window = remember(allocationExpenses) { BudgetAllocationCalculator.windowFor(LocalDate.now(), allocationExpenses) }
    val fmt = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    val values = remember(categories, expenses) {
        mutableStateMapOf<String, String>().apply {
            regular.forEach { c ->
                val suggested = suggestions[c.name]?.suggestedPercent
                put(c.name, String.format(java.util.Locale.US, "%.1f", suggested ?: 0.0))
            }
        }
    }
    val editing = remember { mutableStateMapOf<String, Boolean>() }
    val sum = values.values.sumOf { it.replace(',', '.').toDoubleOrNull() ?: 0.0 }
    val valid = values.values.all {
        val v = it.replace(',', '.').toDoubleOrNull()
        v != null && v.isFinite() && v in 0.0..100.0
    }
    val reserve = (100.0 - sum).coerceAtLeast(0.0)
    AlertDialog(
        containerColor = themedDialogContainerColor(),
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text("Распределение бюджета") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 540.dp)) {
                Text("Расчёт по доступной истории: ${window.from.format(fmt)} — ${window.to.format(fmt)}", style = MaterialTheme.typography.bodySmall)
                Text("Карандаш рядом с каждой долей открывает ручное редактирование. Категории без расходов выделены.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(regular, key = { it.name }) { category ->
                        val suggestion = suggestions[category.name]
                        val noHistory = suggestion?.hasExpenseHistory != true
                        val isEditing = editing[category.name] == true
                        Card(colors = CardDefaults.cardColors(containerColor = if (noHistory) MaterialTheme.colorScheme.errorContainer.copy(alpha = .42f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(category.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (noHistory) "Нет расходов за выбранный период — укажи долю вручную"
                                        else "Факт за период: ${String.format(java.util.Locale("ru", "RU"), "%,.0f ₽", suggestion!!.totalSpent)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (noHistory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isEditing) {
                                    OutlinedTextField(
                                        value = values[category.name].orEmpty(),
                                        onValueChange = { v -> values[category.name] = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                                        modifier = Modifier.width(82.dp),
                                        singleLine = true,
                                        label = { Text("%") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                    )
                                    TextButton(onClick = { editing[category.name] = false }) { Text("ОК") }
                                } else {
                                    Text("${values[category.name] ?: "0"}%", fontWeight = FontWeight.Bold)
                                    TextButton(onClick = { editing[category.name] = true }) { Text("✎") }
                                }
                            }
                        }
                    }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Резервные средства", fontWeight = FontWeight.SemiBold)
                        Text("Автоматически заполняет остаток до 100%", style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${String.format(java.util.Locale("ru", "RU"), "%.1f", reserve)}%", fontWeight = FontWeight.Bold)
                }
                Text("Сумма долей: ${String.format(java.util.Locale("ru", "RU"), "%.1f", sum)}%", style = MaterialTheme.typography.labelMedium, color = if (!valid || sum > 100.0001) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(enabled = valid && sum <= 100.0001 && regular.isNotEmpty(), onClick = {
                val updated = regular.map { it.copy(percent = values[it.name]!!.replace(',', '.').toDouble()) }
                val withReserve = BudgetAllocationCalculator.withReserve(updated.map { it.name to it.percent })
                val reserveRow = withReserve.firstOrNull { it.first == BudgetAllocationCalculator.RESERVE_CATEGORY }?.let { BudgetCategory(it.first, it.second) }
                onSave(updated + listOfNotNull(reserveRow))
            }) { Text("Сохранить распределение") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
