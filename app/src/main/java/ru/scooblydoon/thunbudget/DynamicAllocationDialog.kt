package ru.scooblydoon.thunbudget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

@Composable
internal fun DynamicAllocationDialog(
    categories: List<BudgetCategory>,
    expenses: List<Expense>,
    periodStart: String,
    periodEnd: String,
    firstPeriodStartDay: Int = 9,
    firstPeriodEndDay: Int = 23,
    onDismiss: () -> Unit,
    onSave: (List<BudgetCategory>) -> Unit
) {
    val regular = categories.filterNot { it.name.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) }
    var monthsToAnalyze by remember { mutableIntStateOf(3) }
    val forecast = remember(expenses, categories, firstPeriodStartDay, firstPeriodEndDay, monthsToAnalyze) {
        BudgetForecastCalculator.calculate(
            categories = regular.map { it.name },
            expenses = expenses.mapNotNull { row ->
                runCatching { ForecastExpense(row.category, row.amount, LocalDate.parse(row.date)) }.getOrNull()
            },
            asOf = LocalDate.now(),
            firstStartDay = firstPeriodStartDay,
            firstEndDay = firstPeriodEndDay,
            monthsToAnalyze = monthsToAnalyze
        )
    }
    val availableMonths = forecast.historyMonths
    val averageFacts = remember(forecast) {
        forecast.categories.associate { it.category to it.averageMonthlySpend }
    }
    val proposal = remember(categories, averageFacts, availableMonths) {
        AnalyticsBudgetCalculator.dynamicProposal(regular.map { it.name to it.percent }, averageFacts)
    }
    val values = remember(proposal, categories) {
        mutableStateMapOf<String, String>().apply {
            proposal.percentages.forEach { (name, percent) -> put(name, String.format(Locale.US, "%.1f", percent)) }
        }
    }
    val accepted = remember(categories) {
        mutableStateMapOf<String, Boolean>().apply { regular.forEach { put(it.name, true) } }
    }
    val resultingShares = regular.associate { category ->
        category.name to if (accepted[category.name] == true)
            (values[category.name]?.replace(',', '.')?.toDoubleOrNull() ?: category.percent)
        else category.percent
    }
    val sum = resultingShares.values.sum()
    val inputsValid = regular.all { category ->
        if (accepted[category.name] != true) true
        else values[category.name]?.replace(',', '.')?.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true
    }
    val valid = inputsValid && resultingShares.values.all { it.isFinite() && it in 0.0..100.0 } && sum <= 100.0001
    val totalAverage = averageFacts.values.sum()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Динамический прогноз долей") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 560.dp)) {
                Text("Прогноз расходов по полной истории отчётных периодов", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..6).forEach { count ->
                        FilterChip(selected = monthsToAnalyze == count, onClick = { monthsToAnalyze = count }, label = { Text("$count") })
                    }
                }
                Text(
                    if (availableMonths == 0) "За выбранную историю расходов нет."
                    else "Учтено месяцев с расходами: $availableMonths из $monthsToAnalyze · Средний расход: ${String.format(Locale("ru", "RU"), "%,.0f ₽", forecast.totalMonthlyForecast)} на следующий месяц",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Для каждой категории можно отдельно принять рекомендацию, оставить прежнюю долю или изменить предложенный процент.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(regular, key = { it.name }) { category ->
                        val spent = averageFacts[category.name] ?: 0.0
                        val proposed = values[category.name].orEmpty()
                        val isAccepted = accepted[category.name] == true
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = isAccepted, onCheckedChange = { accepted[category.name] = it })
                                    Column(Modifier.weight(1f)) {
                                        Text(category.name, fontWeight = FontWeight.SemiBold)
                                        Text("Прогноз на месяц: ${String.format(Locale("ru", "RU"), "%,.0f ₽", spent)} · Было: ${String.format(Locale("ru", "RU"), "%.1f", category.percent)}%", style = MaterialTheme.typography.labelSmall)
                                    }
                                    OutlinedTextField(
                                        value = proposed,
                                        onValueChange = { values[category.name] = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) },
                                        modifier = Modifier.width(92.dp),
                                        enabled = isAccepted,
                                        singleLine = true,
                                        label = { Text("Новая %") }
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Итоговая сумма долей: ${String.format(Locale("ru", "RU"), "%.1f", sum)}%", color = if (!valid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                Text("Резерв: ${String.format(Locale("ru", "RU"), "%.1f", (100.0 - sum).coerceAtLeast(0.0))}%", style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = {
            TextButton(enabled = valid && regular.isNotEmpty() && availableMonths > 0, onClick = {
                val updated = regular.map { category ->
                    category.copy(percent = resultingShares[category.name] ?: category.percent)
                }
                val withReserve = BudgetAllocationCalculator.withReserve(updated.map { it.name to it.percent })
                val reserve = withReserve.firstOrNull { it.first == BudgetAllocationCalculator.RESERVE_CATEGORY }?.let { BudgetCategory(it.first, it.second) }
                onSave(updated + listOfNotNull(reserve))
            }) { Text("Применить выбранные") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Отклонить всё") }
            }
        }
    )
}
