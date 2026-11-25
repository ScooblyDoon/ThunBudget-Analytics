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
import java.util.Locale

@Composable
internal fun DynamicAllocationDialog(
    categories: List<BudgetCategory>,
    expenses: List<Expense>,
    periodStart: String,
    periodEnd: String,
    firstPeriodStartDay: Int = 9,
    firstPeriodEndDay: Int = 23,
    periodIncomeByRange: Map<Pair<String, String>, Double> = emptyMap(),
    fallbackPeriodIncome: Double = 0.0,
    onDismiss: () -> Unit,
    onSave: (List<BudgetCategory>) -> Unit
) {
    val regular = categories.filterNot { it.name.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) }
    val forecast = remember(expenses, categories, firstPeriodStartDay, firstPeriodEndDay) {
        BudgetForecastCalculator.calculate(
            categories = regular.map { it.name },
            expenses = expenses.mapNotNull { row ->
                runCatching { ForecastExpense(row.category, row.amount, LocalDate.parse(row.date)) }.getOrNull()
            },
            asOf = LocalDate.now(),
            firstStartDay = firstPeriodStartDay,
            firstEndDay = firstPeriodEndDay,
            monthsToAnalyze = 6
        )
    }
    val pairedIncomePeriods = remember(forecast.reportingPeriods, periodIncomeByRange) {
        forecast.reportingPeriods.mapNotNull { range ->
            val key = range.start.toString() to range.end.toString()
            periodIncomeByRange[key]?.takeIf { it.isFinite() && it > 0.0 }?.let { range to it }
        }
    }
    val forecastPercentages = remember(forecast, pairedIncomePeriods, expenses, fallbackPeriodIncome) {
        regular.associate { category ->
            val ratios = pairedIncomePeriods.map { (range, income) ->
                val spent = expenses.asSequence()
                    .filter { it.category.equals(category.name, ignoreCase = true) }
                    .filter { it.date >= range.start.toString() && it.date <= range.end.toString() }
                    .sumOf { it.amount.coerceAtLeast(0.0) }
                spent / income * 100.0
            }
            val averagePercent = when {
                ratios.isNotEmpty() -> ratios.average()
                fallbackPeriodIncome > 0.0 -> {
                    (forecast.categories.firstOrNull { it.category == category.name }?.averagePeriodSpend ?: 0.0) /
                        fallbackPeriodIncome * 100.0
                }
                else -> null
            }
            category.name to averagePercent
        }
    }
    val rawRecommendations = regular.associate { category ->
        val predicted = forecastPercentages[category.name]
        val protectedReserve = category.name.equals("Непредвиденные расходы", true) ||
            category.name.equals("Перенос средств", true)
        val suggested = when {
            protectedReserve || predicted == null -> category.percent
            predicted < category.percent -> minOf(category.percent, predicted + 2.0)
            else -> predicted
        }.coerceIn(0.0, 100.0)
        category.name to suggested
    }
    val rawTotal = rawRecommendations.values.sum()
    val normalization = if (rawTotal > 100.0) 100.0 / rawTotal else 1.0
    val recommendations = rawRecommendations.mapValues { (_, value) -> value * normalization }
    val values = remember(recommendations, categories) {
        mutableStateMapOf<String, String>().apply {
            recommendations.forEach { (name, percent) -> put(name, String.format(Locale.US, "%.1f", percent)) }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Динамический прогноз долей") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 560.dp)) {
                Text("Прогноз по завершённым отчётным периодам", fontWeight = FontWeight.SemiBold)
                Text(
                    if (forecast.historyPeriods == 0) "История расходов пока пуста. Можно сохранить текущие доли или задать их вручную."
                    else "Учтено отчётных периодов: ${forecast.historyPeriods} · Средний расход: ${String.format(Locale("ru", "RU"), "%,.0f ₽", forecast.totalMonthlyForecast)} в месяц",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Проценты рассчитываются относительно фактического дохода за соответствующие периоды. Для категорий с запасом сохраняется дополнительный резерв 2% дохода.",
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(regular, key = { it.name }) { category ->
                        val averageSpend = forecast.categories.firstOrNull { it.category == category.name }?.averagePeriodSpend ?: 0.0
                        val proposed = values[category.name].orEmpty()
                        val isAccepted = accepted[category.name] == true
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = isAccepted, onCheckedChange = { accepted[category.name] = it })
                                    Column(Modifier.weight(1f)) {
                                        Text(category.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Средний расход за период: ${String.format(Locale("ru", "RU"), "%,.0f ₽", averageSpend)} · Было: ${String.format(Locale("ru", "RU"), "%.1f", category.percent)}%",
                                            style = MaterialTheme.typography.labelSmall
                                        )
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
            TextButton(enabled = valid && regular.isNotEmpty(), onClick = {
                val updated = regular.map { category ->
                    category.copy(percent = resultingShares[category.name] ?: category.percent)
                }
                val withReserve = BudgetAllocationCalculator.withReserve(updated.map { it.name to it.percent })
                val reserve = withReserve.firstOrNull { it.first == BudgetAllocationCalculator.RESERVE_CATEGORY }
                    ?.let { BudgetCategory(it.first, it.second) }
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
