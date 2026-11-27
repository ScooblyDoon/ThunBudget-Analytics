package ru.scooblydoon.thunbudget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
internal fun FlexiblePeriodsScreen(
    periods: List<FlexiblePeriodDefinition>,
    onPeriodsChange: (List<FlexiblePeriodDefinition>) -> Unit,
    expenses: List<Expense>,
    incomeEntries: List<IncomeEntry>,
    categories: List<BudgetCategory>,
    monthlyIncome: Double,
    recurringPayments: List<RecurringPayment>,
    firstPeriodStartDay: Int,
    firstPeriodEndDay: Int
) {
    val today = LocalDate.now()
    var showAdd by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val ordered = remember(periods) {
        runCatching { FlexiblePeriodRules.validateSequence(periods) }.getOrDefault(emptyList())
    }
    val selected = ordered.firstOrNull { it.id == selectedId }
        ?: ordered.firstOrNull { !today.isBefore(it.start) && !today.isAfter(it.end) }
        ?: ordered.lastOrNull()
    val colors = ThunBudgetPalette.ChartSeries
    val report = if (selected != null) {
        remember(
            selected, expenses, incomeEntries, categories, monthlyIncome, recurringPayments,
            today, firstPeriodStartDay, firstPeriodEndDay
        ) {
            buildFlexiblePeriodReport(
                selected, expenses, incomeEntries, categories, monthlyIncome,
                recurringPayments, today, firstPeriodStartDay, firstPeriodEndDay
            )
        }
    } else null
    val selectedIndex = if (selected != null) ordered.indexOfFirst { it.id == selected.id } else -1
    val previousPeriod = if (selectedIndex > 0) ordered.getOrNull(selectedIndex - 1) else null
    val previousReport = if (previousPeriod != null) {
        remember(previousPeriod, expenses, incomeEntries, categories, monthlyIncome, today) {
            FlexiblePeriodAnalyticsCalculator.calculate(
                period = previousPeriod,
                categories = categories.map { category -> FlexiblePeriodCategoryPlan(category.name, category.percent) },
                expenses = expenses.mapNotNull { row ->
                    runCatching {
                        FlexiblePeriodExpense(row.id, row.title, row.category, row.amount, LocalDate.parse(row.date))
                    }.getOrNull()
                },
                incomes = incomeEntries.mapNotNull { entry ->
                    runCatching {
                        FlexiblePeriodIncome(entry.amount, LocalDate.parse(entry.date), entry.isCarryover)
                    }.getOrNull()
                },
                monthlyIncomeFallback = monthlyIncome,
                asOf = today
            )
        }
    } else null


    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 28.dp)
    ) {
        item {
            Text("Периоды", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Последовательные интервалы общей длительностью до 30 или 31 календарного дня.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            val total = ordered.sumOf { it.dayCount }
            val maxDays = ordered.firstOrNull()?.let { first ->
                ordered.lastOrNull()?.let { last ->
                    runCatching { FlexiblePeriodRules.maximumAllowedDays(first.start, last.end) }.getOrNull()
                }
            }
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Настройка последовательности", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (ordered.isEmpty()) "Периоды пока не созданы."
                        else "Использовано: " + total + " из " + (maxDays ?: "—") + " дней · " + ordered.size + " период(ов)"
                    )
                    if (maxDays != null) {
                        LinearProgressIndicator(
                            progress = { (total.toDouble() / maxDays.toDouble()).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "Следующий период начинается на следующий день после предыдущего. Пересечения, пропуски и последовательности длиннее двух календарных месяцев запрещены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ordered.isEmpty()) "Создать первый период" else "Добавить следующий период")
                    }
                    if (message.isNotBlank()) {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (ordered.isNotEmpty()) {
            item { Text("Мои периоды", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(ordered, key = { it.id }) { period ->
                val chosen = selected?.id == period.id
                Card(onClick = { selectedId = period.id }, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(period.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            if (chosen) Text("Выбран", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(
                            period.start.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " — " +
                                period.end.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " · " + period.dayCount + " дн.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    period.end.isBefore(today) -> "Завершён"
                                    period.start.isAfter(today) -> "Будущий"
                                    else -> "Идёт сейчас"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall
                            )
                            TextButton(
                                enabled = ordered.indexOf(period) == 0 || ordered.lastOrNull()?.id == period.id,
                                onClick = {
                                    runCatching { FlexiblePeriodRules.remove(ordered, period.id) }
                                        .onSuccess {
                                            onPeriodsChange(it)
                                            selectedId = it.lastOrNull()?.id.orEmpty()
                                            message = ""
                                        }
                                        .onFailure { message = it.message ?: "Не удалось удалить период." }
                                }
                            ) { Text("Удалить") }
                        }
                    }
                }
            }
        } else {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("После создания периода здесь появятся план, факт, прогноз, диаграмма категорий и сравнение с предыдущим интервалом.")
                    }
                }
            }
        }

        if (selected != null && report != null) {
            item { Divider(); Text("Аналитика периода", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                val execution = if (report.plannedTotal > 0.0) report.projectedTotal / report.plannedTotal else 0.0
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            selected.start.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " — " +
                                selected.end.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " · " + report.totalDays + " дней",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlexibleMetric("Доход", report.budgetIncome, Modifier.weight(1f))
                            FlexibleMetric("План", report.plannedTotal, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlexibleMetric("Факт", report.actualTotal, Modifier.weight(1f))
                            FlexibleMetric("Прогноз", report.projectedTotal, Modifier.weight(1f))
                        }
                        LinearProgressIndicator(
                            progress = { execution.toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (execution > 1.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            trackColor = thunBudgetProgressTrackColor()
                        )
                        Text(
                            "Ожидаемое исполнение бюджета: " + formatFlexiblePercent(if (report.plannedTotal > 0.0) execution * 100.0 else null),
                            fontWeight = FontWeight.SemiBold,
                            color = if (execution > 1.0) Color(0xFFC74444) else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            when {
                                report.isCompleted -> "Период завершён. Показаны только фактические расходы."
                                report.isFuture -> "Период ещё не начался. Показаны план и прогноз; фактические расходы равны нулю."
                                else -> "Осталось дней: " + report.remainingDays + ". Прогноз учитывает будущие записи и неоплаченные регулярные платежи."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Диапазон прогноза: " + formatFlexibleMoney(report.projectedLow) + " — " + formatFlexibleMoney(report.projectedHigh),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Средний расход в день: " + (report.averageActualPerElapsedDay?.let(::formatFlexibleMoney) ?: "—") +
                                " · Необходимый темп: " + (report.requiredPerRemainingDay?.let(::formatFlexibleMoney) ?: "—") + " в день",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (previousReport != null && previousPeriod != null) {
                item {
                    val currentAverage = report.averageActualPerElapsedDay
                    val previousAverage = previousReport.averageActualPerElapsedDay
                    val difference = if (currentAverage != null && previousAverage != null && previousAverage > 0.0) {
                        (currentAverage - previousAverage) / previousAverage * 100.0
                    } else null
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Сравнение с предыдущим периодом", fontWeight = FontWeight.SemiBold)
                            Text(previousPeriod.name + " · " + previousPeriod.dayCount + " дней", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (difference == null) "Недостаточно данных для сравнения среднего дневного расхода."
                                else "Средний расход в день: " + formatFlexibleMoney(currentAverage!!) + " против " +
                                    formatFlexibleMoney(previousAverage!!) + " · " +
                                    String.format(Locale("ru", "RU"), "%+.1f%%", difference),
                                color = if ((difference ?: 0.0) > 0.0) Color(0xFFC74444) else Color(0xFF168A72)
                            )
                        }
                    }
                }
            }
            item { Text("Структура фактических расходов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                val values = report.categories.map { it.category to it.actualToDate }
                    .filter { it.second > 0.0 }.sortedByDescending { it.second }
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (values.isEmpty()) {
                            Text("Фактических расходов за выбранный период пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            BoxWithConstraints(contentAlignment = Alignment.Center) {
                                val diameter = minOf(210.dp, maxWidth * 0.68f)
                                Canvas(Modifier.size(diameter)) {
                                    var angle = -90f
                                    val sum = values.sumOf { it.second }.toFloat().coerceAtLeast(1f)
                                    val width = size.minDimension * 0.22f
                                    val inset = width / 2f
                                    val arc = androidx.compose.ui.geometry.Size(size.width - width, size.height - width)
                                    values.forEachIndexed { i, pair ->
                                        val sweep = pair.second.toFloat() / sum * 360f
                                        drawArc(colors[i % colors.size], angle, sweep, false, androidx.compose.ui.geometry.Offset(inset, inset), arc, style = Stroke(width))
                                        angle += sweep
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatFlexibleMoney(report.actualTotal), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                    Text("Факт", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            report.categories.sortedByDescending { it.actualToDate }.forEachIndexed { i, category ->
                                if (category.actualToDate > 0.0) {
                                    val share = if (report.actualTotal > 0.0) category.actualToDate / report.actualTotal * 100.0 else 0.0
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                        Box(Modifier.size(10.dp).background(colors[i % colors.size], RoundedCornerShape(3.dp)))
                                        Spacer(Modifier.size(8.dp))
                                        Text(category.category, modifier = Modifier.weight(1f), maxLines = 2)
                                        Text(
                                            formatFlexibleMoney(category.actualToDate) + " · " + String.format(Locale("ru", "RU"), "%.1f%%", share),
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Text("План и исполнение по категориям", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(report.categories, key = { "flex-" + selected.id + "-" + it.category }) { category ->
                val actualPercent = category.actualExecutionPercent ?: 0.0
                val projectedPercent = category.projectedExecutionPercent ?: 0.0
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = thunBudgetCategoryCardSurface()), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(category.category, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(formatFlexibleMoney(category.actualToDate) + " / " + formatFlexibleMoney(category.planned), fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator(
                            progress = { (actualPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (actualPercent > 100.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            trackColor = thunBudgetProgressTrackColor()
                        )
                        Text(
                            "Факт: " + formatFlexiblePercent(category.actualExecutionPercent) +
                                " · Прогноз: " + formatFlexiblePercent(category.projectedExecutionPercent),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (projectedPercent > 100.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (category.remainingToPlan >= 0.0) "Остаток плана: " + formatFlexibleMoney(category.remainingToPlan)
                            else "Превышение факта: " + formatFlexibleMoney(-category.remainingToPlan),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (category.remainingToPlan < 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                        )
                        if (!report.isCompleted && projectedPercent > 100.0) {
                            Text(
                                "Риск превышения: ожидается выше плана примерно на " + formatFlexibleMoney(category.projectedTotal - category.planned) + ".",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFC74444)
                            )
                        }
                    }
                }
            }
            if (!report.isCompleted && report.categories.any { (it.projectedExecutionPercent ?: 0.0) > 100.0 }) {
                item {
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Рекомендации", fontWeight = FontWeight.SemiBold)
                            val risky = report.categories.filter { (it.projectedExecutionPercent ?: 0.0) > 100.0 }
                            Text(
                                "Проверь категории: " + risky.joinToString { it.category } +
                                    ". Сравни остаток бюджета с числом оставшихся дней и сократи необязательные расходы в категориях с прогнозируемым превышением.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            report.requiredPerRemainingDay?.let {
                                Text("Ориентир на оставшиеся дни: не более " + formatFlexibleMoney(it) + " в день.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        FlexiblePeriodEditorDialog(
            periods = ordered,
            onDismiss = { showAdd = false },
            onSave = { name, start, end ->
                runCatching {
                    val id = "flex-" + start.toString() + "-" + end.toString() + "-" + System.currentTimeMillis()
                    FlexiblePeriodRules.add(ordered, FlexiblePeriodDefinition(id, name, start, end))
                }.onSuccess {
                    onPeriodsChange(it)
                    selectedId = it.lastOrNull()?.id.orEmpty()
                    message = ""
                    showAdd = false
                }.onFailure { message = it.message ?: "Не удалось добавить период." }
            }
        )
    }
}

@Composable
private fun FlexibleMetric(label: String, value: Double, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatFlexibleMoney(value), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun FlexiblePeriodEditorDialog(
    periods: List<FlexiblePeriodDefinition>,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate, LocalDate) -> Unit
) {
    var name by remember(periods) { mutableStateOf("Период " + (periods.size + 1)) }
    var startText by remember(periods) {
        mutableStateOf(
            if (periods.isEmpty()) LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"))
            else periods.last().end.plusDays(1).format(DateTimeFormatter.ofPattern("ddMMyyyy"))
        )
    }
    var daysText by remember(periods) { mutableStateOf("1") }
    val start = if (periods.isEmpty()) parseFlexibleDate(startText) else periods.last().end.plusDays(1)
    val days = daysText.toIntOrNull()?.takeIf { it in 1..31 }
    val end = if (start != null && days != null) start.plusDays(days.toLong() - 1L) else null
    val sequenceStart = periods.firstOrNull()?.start ?: start
    val maximum = if (sequenceStart != null && end != null) {
        runCatching { FlexiblePeriodRules.maximumAllowedDays(sequenceStart, end) }.getOrNull()
    } else null
    val candidateTotal = periods.sumOf { it.dayCount } + (days ?: 0)
    val valid = name.isNotBlank() && start != null && end != null && maximum != null && candidateTotal <= maximum

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (periods.isEmpty()) "Первый период" else "Следующий период") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = themedDialogOutlinedTextFieldColors())
                OutlinedTextField(
                    startText,
                    { if (periods.isEmpty()) startText = it.filter(Char::isDigit).take(8) },
                    readOnly = periods.isNotEmpty(),
                    label = { Text("Начало (ДД.ММ.ГГГГ)") },
                    visualTransformation = DateDigitsVisualTransformation,
                    placeholder = { Text("ДД.ММ.ГГГГ") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = themedDialogOutlinedTextFieldColors()
                )
                OutlinedTextField(
                    daysText, { daysText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Длительность, дней") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = themedDialogOutlinedTextFieldColors()
                )
                if (end != null) {
                    Text("Окончание: " + end.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                }
                if (maximum != null) {
                    Text(
                        "Всего в последовательности: " + candidateTotal + " из " + maximum + " дней",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (candidateTotal <= maximum) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                } else {
                    Text("Последовательность может затрагивать не более двух календарных месяцев.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (periods.isNotEmpty() && start != null) {
                    Text(
                        "Начало назначено автоматически: " + start.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (start != null && end != null) onSave(name.trim(), start, end) }, enabled = valid) {
                Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

internal fun buildFlexiblePeriodReport(
    period: FlexiblePeriodDefinition,
    expenses: List<Expense>,
    incomeEntries: List<IncomeEntry>,
    categories: List<BudgetCategory>,
    monthlyIncome: Double,
    recurringPayments: List<RecurringPayment>,
    today: LocalDate,
    firstPeriodStartDay: Int,
    firstPeriodEndDay: Int
): FlexiblePeriodReport {
    val forecastCategories = categories.filterNot { it.name.equals("Перенос средств", true) }.map { it.name }
    val forecastExpenses = expenses.mapNotNull { row ->
        runCatching { ForecastExpense(row.category, row.amount, LocalDate.parse(row.date)) }.getOrNull()
    }.filterNot { it.category.equals("Перенос средств", true) }
    val calibration = BudgetForecastBacktester.evaluate(
        forecastCategories, forecastExpenses, today, firstPeriodStartDay, firstPeriodEndDay
    ).calibration
    val forecastStart = if (period.end.isAfter(today)) maxOf(period.start, today.plusDays(1)) else null
    val forecastDays = if (forecastStart == null) 0 else (ChronoUnit.DAYS.between(forecastStart, period.end) + 1L).toInt()
    val forecast = BudgetForecastCalculator.calculate(
        categories = forecastCategories,
        expenses = forecastExpenses,
        asOf = today,
        firstStartDay = firstPeriodStartDay,
        firstEndDay = firstPeriodEndDay,
        monthsToAnalyze = 0,
        nextPeriodDays = forecastDays,
        calibration = calibration,
        forecastStartDate = forecastStart,
        forecastEndDate = if (forecastStart == null) null else period.end
    )
    val recurringByCategory = mutableMapOf<String, Double>()
    recurringPayments.filter { it.active }.forEach { payment ->
        var month = YearMonth.from(period.start)
        val finalMonth = YearMonth.from(period.end)
        while (!month.isAfter(finalMonth)) {
            val due = payment.dueDate(month)
            val alreadyRecorded = expenses.any { row ->
                row.title.equals(payment.title, true) && row.category.equals(payment.category, true) && row.date == due.toString()
            }
            if (!due.isBefore(period.start) && !due.isAfter(period.end) && due.isAfter(today) &&
                !payment.isCompleted(month) && !alreadyRecorded
            ) {
                recurringByCategory[payment.category] = (recurringByCategory[payment.category] ?: 0.0) + payment.amount
            }
            month = month.plusMonths(1)
        }
    }
    val forecastMap = forecast.categories.associate { category ->
        category.category to FlexiblePeriodForecast(
            variable = category.forecastForPeriod,
            low = category.forecastLowForPeriod,
            high = category.forecastHighForPeriod,
            recurring = recurringByCategory[category.category] ?: 0.0
        )
    }
    return FlexiblePeriodAnalyticsCalculator.calculate(
        period = period,
        categories = categories.map { FlexiblePeriodCategoryPlan(it.name, it.percent) },
        expenses = expenses.mapNotNull { row ->
            runCatching { FlexiblePeriodExpense(row.id, row.title, row.category, row.amount, LocalDate.parse(row.date)) }.getOrNull()
        },
        incomes = incomeEntries.mapNotNull { row ->
            runCatching { FlexiblePeriodIncome(row.amount, LocalDate.parse(row.date), row.isCarryover) }.getOrNull()
        },
        monthlyIncomeFallback = monthlyIncome,
        asOf = today,
        forecasts = forecastMap
    )
}

internal fun parseFlexibleDate(raw: String): LocalDate? = parseUserDate(raw)

private fun formatFlexibleMoney(value: Double): String =
    String.format(Locale("ru", "RU"), "%,.0f ₽", value)

private fun formatFlexiblePercent(value: Double?): String =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale("ru", "RU"), "%.1f%%", it) } ?: "—"
