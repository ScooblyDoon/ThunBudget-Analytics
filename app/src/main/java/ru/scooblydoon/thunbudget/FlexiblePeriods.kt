package ru.scooblydoon.thunbudget

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * A saved custom reporting period. Period definitions contain dates only:
 * transactions remain in the existing ledger and are assigned by transaction date.
 */
data class FlexiblePeriodDefinition(
    val id: String,
    val name: String,
    val start: LocalDate,
    val end: LocalDate
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(!end.isBefore(start))
    }

    val dayCount: Long
        get() = ChronoUnit.DAYS.between(start, end) + 1L
}

object FlexiblePeriodRules {
    /**
     * The user-defined cycle is a consecutive sequence of custom periods.
     * Its total duration may use the number of days in one touched calendar
     * month, or in two adjacent months when the sequence crosses a month edge.
     */
    fun validateSequence(periods: List<FlexiblePeriodDefinition>): List<FlexiblePeriodDefinition> {
        require(periods.map { it.id }.distinct().size == periods.size) {
            "Повторяется идентификатор периода."
        }
        periods.forEach {
            require(it.id.isNotBlank() && it.name.isNotBlank())
            require(!it.end.isBefore(it.start))
        }
        val ordered = periods.sortedBy { it.start }
        ordered.zipWithNext().forEach { (previous, next) ->
            require(previous.end.plusDays(1) == next.start) {
                "Периоды должны идти подряд: без пересечений и пропущенных дней."
            }
        }
        if (ordered.isNotEmpty()) {
            val startMonth = YearMonth.from(ordered.first().start)
            val endMonth = YearMonth.from(ordered.last().end)
            val touchedMonths = ChronoUnit.MONTHS.between(startMonth, endMonth) + 1L
            require(touchedMonths in 1L..2L) {
                "Одна последовательность может затрагивать не более двух календарных месяцев."
            }
            val maximumDays = maximumAllowedDays(ordered.first().start, ordered.last().end)
            val totalDays = ordered.sumOf { it.dayCount }
            require(totalDays <= maximumDays) {
                "Общая длительность периодов не может превышать $maximumDays календарных дней."
            }
        }
        return ordered
    }

    /**
     * When the sequence crosses one month boundary, use the longer of the
     * two calendar months. This permits a 31-day cycle such as Nov 16–Dec 16,
     * while February-only sequences remain limited to 28 or 29 days.
     */
    fun maximumAllowedDays(start: LocalDate, end: LocalDate): Long {
        require(!end.isBefore(start))
        val firstMonth = YearMonth.from(start)
        val lastMonth = YearMonth.from(end)
        val touchedMonths = ChronoUnit.MONTHS.between(firstMonth, lastMonth) + 1L
        require(touchedMonths in 1L..2L) {
            "Последовательность может затрагивать не более двух календарных месяцев."
        }
        return maxOf(firstMonth.lengthOfMonth(), lastMonth.lengthOfMonth()).toLong()
    }

    fun totalDays(periods: List<FlexiblePeriodDefinition>): Long =
        validateSequence(periods).sumOf { it.dayCount }

    fun remainingDays(periods: List<FlexiblePeriodDefinition>): Long {
        val ordered = validateSequence(periods)
        if (ordered.isEmpty()) return 0L
        val maximum = maximumAllowedDays(ordered.first().start, ordered.last().end)
        return (maximum - ordered.sumOf { it.dayCount }).coerceAtLeast(0L)
    }

    /**
     * Periods are appended in chronological order. A new period always starts
     * the day after the previous one, so users cannot create gaps or overlaps.
     */
    fun add(
        periods: List<FlexiblePeriodDefinition>,
        period: FlexiblePeriodDefinition
    ): List<FlexiblePeriodDefinition> {
        val ordered = validateSequence(periods)
        require(ordered.none { it.id == period.id }) {
            "Период с таким идентификатором уже существует."
        }
        if (ordered.isNotEmpty()) {
            require(ordered.last().end.plusDays(1) == period.start) {
                "Новый период должен начинаться на следующий день после предыдущего."
            }
        }
        return validateSequence(ordered + period)
    }

    fun replace(
        periods: List<FlexiblePeriodDefinition>,
        replacement: FlexiblePeriodDefinition
    ): List<FlexiblePeriodDefinition> {
        require(periods.any { it.id == replacement.id }) {
            "Изменяемый период не найден."
        }
        return validateSequence(periods.map { if (it.id == replacement.id) replacement else it })
    }

    fun remove(
        periods: List<FlexiblePeriodDefinition>,
        id: String
    ): List<FlexiblePeriodDefinition> {
        val ordered = validateSequence(periods)
        require(ordered.any { it.id == id }) { "Период не найден." }
        val index = ordered.indexOfFirst { it.id == id }
        require(index == 0 || index == ordered.lastIndex) {
            "Удалить можно только первый или последний период последовательности."
        }
        return ordered.filterNot { it.id == id }
    }

    fun periodFor(
        periods: List<FlexiblePeriodDefinition>,
        date: LocalDate
    ): FlexiblePeriodDefinition? =
        validateSequence(periods).singleOrNull { !date.isBefore(it.start) && !date.isAfter(it.end) }
}
class FlexiblePeriodRepository(private val preferences: SharedPreferences) {
    companion object {
        const val KEY = "flexible_periods_v1"
    }

    fun load(): List<FlexiblePeriodDefinition> {
        val raw = preferences.getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        val periods = (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            FlexiblePeriodDefinition(
                id = row.getString("id"),
                name = row.getString("name"),
                start = LocalDate.parse(row.getString("start")),
                end = LocalDate.parse(row.getString("end"))
            )
        }
        return FlexiblePeriodRules.validateSequence(periods)
    }

    fun save(periods: List<FlexiblePeriodDefinition>) {
        val ordered = FlexiblePeriodRules.validateSequence(periods)
        val array = JSONArray()
        ordered.forEach { period ->
            array.put(
                JSONObject()
                    .put("id", period.id)
                    .put("name", period.name)
                    .put("start", period.start.toString())
                    .put("end", period.end.toString())
            )
        }
        check(preferences.edit().putString(KEY, array.toString()).commit()) {
            "Не удалось сохранить гибкие периоды."
        }
    }
}

data class FlexiblePeriodExpense(
    val id: Long,
    val title: String,
    val category: String,
    val amount: Double,
    val date: LocalDate
)

data class FlexiblePeriodIncome(
    val amount: Double,
    val date: LocalDate,
    val isCarryover: Boolean = false,
    val isAdditional: Boolean = false
)

data class FlexiblePeriodCategoryPlan(
    val name: String,
    val percent: Double
)

data class FlexiblePeriodForecast(
    /** Variable-spend forecast for future dates only; actuals are added separately. */
    val variable: Double = 0.0,
    val low: Double = variable,
    val high: Double = variable,
    /** Expected recurring payments not already entered as future transactions. */
    val recurring: Double = 0.0
) {
    init {
        require(variable.isFinite() && variable >= 0.0)
        require(low.isFinite() && low >= 0.0)
        require(high.isFinite() && high >= low)
        require(recurring.isFinite() && recurring >= 0.0)
    }
}

data class FlexiblePeriodCategoryResult(
    val category: String,
    val planned: Double,
    val actualToDate: Double,
    val futureEntered: Double,
    val variableForecast: Double,
    val recurringForecast: Double,
    val projectedTotal: Double,
    val projectedLow: Double,
    val projectedHigh: Double,
    val remainingToPlan: Double,
    val actualExecutionPercent: Double?,
    val projectedExecutionPercent: Double?
)

data class FlexiblePeriodReport(
    val period: FlexiblePeriodDefinition,
    val totalDays: Long,
    val elapsedDays: Long,
    val remainingDays: Long,
    val isFuture: Boolean,
    val isCompleted: Boolean,
    val earnedIncome: Double,
    val carryoverIncome: Double,
    val budgetIncome: Double,
    val plannedTotal: Double,
    val actualTotal: Double,
    val futureEnteredTotal: Double,
    val variableForecastTotal: Double,
    val recurringForecastTotal: Double,
    val projectedTotal: Double,
    val projectedLow: Double,
    val projectedHigh: Double,
    val remainingToPlan: Double,
    val averageActualPerElapsedDay: Double?,
    val requiredPerRemainingDay: Double?,
    val categories: List<FlexiblePeriodCategoryResult>,
    val additionalIncome: Double = 0.0
)

object FlexiblePeriodAnalyticsCalculator {
    private const val TRANSFER_CATEGORY = "Перенос средств"

    fun calculate(
        period: FlexiblePeriodDefinition,
        categories: List<FlexiblePeriodCategoryPlan>,
        expenses: List<FlexiblePeriodExpense>,
        incomes: List<FlexiblePeriodIncome>,
        monthlyIncomeFallback: Double,
        asOf: LocalDate,
        forecasts: Map<String, FlexiblePeriodForecast> = emptyMap()
    ): FlexiblePeriodReport {
        require(monthlyIncomeFallback.isFinite() && monthlyIncomeFallback >= 0.0)
        require(categories.map { it.name.trim().lowercase() }.distinct().size == categories.size)
        require(categories.all { it.name.isNotBlank() && it.percent.isFinite() && it.percent >= 0.0 })

        val periodExpenses = expenses.filter {
            it.date >= period.start && it.date <= period.end &&
                it.amount.isFinite() && it.amount > 0.0 &&
                !it.category.equals(TRANSFER_CATEGORY, ignoreCase = true)
        }
        val periodIncomes = incomes.filter {
            it.date >= period.start && it.date <= period.end &&
                it.amount.isFinite() && it.amount > 0.0
        }

        require(periodIncomes.none { it.isCarryover && it.isAdditional }) {
            "Перенос не может одновременно считаться дополнительным доходом."
        }
        val earnedIncome = periodIncomes.filterNot { it.isCarryover }.sumOf { it.amount }
        val additionalIncome = periodIncomes.filter { it.isAdditional }.sumOf { it.amount }
        val carryoverIncome = periodIncomes.filter { it.isCarryover }.sumOf { it.amount }
        val ledgerIncome = earnedIncome + carryoverIncome
        val budgetCategories = categories.filterNot {
            it.name.equals(TRANSFER_CATEGORY, ignoreCase = true)
        }

        // When no dated income exists for the range, spread configured monthly income
        // over the actual calendar days covered, including partial months and leap years.
        val proratedMonthlyIncome = proratedMonthlyIncome(
            monthlyIncomeFallback,
            period.start,
            period.end
        )
        val budgetIncome = if (ledgerIncome > 0.0) ledgerIncome else proratedMonthlyIncome
        val totalPercent = budgetCategories.sumOf { it.percent }
        val plannedTotal = budgetIncome * totalPercent / 100.0

        val isFuture = period.start.isAfter(asOf)
        val isCompleted = period.end.isBefore(asOf)
        val elapsedDays = when {
            isFuture -> 0L
            else -> ChronoUnit.DAYS.between(period.start, minOf(asOf, period.end)) + 1L
        }.coerceAtLeast(0L)
        val remainingDays = when {
            isCompleted -> 0L
            isFuture -> period.dayCount
            else -> ChronoUnit.DAYS.between(asOf.plusDays(1), period.end) + 1L
        }.coerceAtLeast(0L)

        val categoryResults = budgetCategories.map { category ->
                val categoryExpenses = periodExpenses.filter {
                    it.category.equals(category.name, ignoreCase = true)
                }
                val actual = categoryExpenses.filter { !it.date.isAfter(asOf) }.sumOf { it.amount }
                val futureEntered = categoryExpenses.filter { it.date.isAfter(asOf) }.sumOf { it.amount }
                val forecast = if (isCompleted) FlexiblePeriodForecast() else
                    forecasts.entries.firstOrNull { it.key.equals(category.name, ignoreCase = true) }?.value
                        ?: FlexiblePeriodForecast()

                val planned = budgetIncome * category.percent / 100.0
                val projected = actual + futureEntered + forecast.variable + forecast.recurring
                val low = actual + futureEntered + forecast.low + forecast.recurring
                val high = actual + futureEntered + forecast.high + forecast.recurring

                FlexiblePeriodCategoryResult(
                    category = category.name,
                    planned = planned,
                    actualToDate = actual,
                    futureEntered = futureEntered,
                    variableForecast = forecast.variable,
                    recurringForecast = forecast.recurring,
                    projectedTotal = projected,
                    projectedLow = low,
                    projectedHigh = high,
                    remainingToPlan = planned - actual,
                    actualExecutionPercent = if (planned > 0.0) actual / planned * 100.0 else null,
                    projectedExecutionPercent = if (planned > 0.0) projected / planned * 100.0 else null
                )
            }

        val actualTotal = periodExpenses.filter { !it.date.isAfter(asOf) }.sumOf { it.amount }
        val futureEnteredTotal = periodExpenses.filter { it.date.isAfter(asOf) }.sumOf { it.amount }
        val variableForecastTotal = categoryResults.sumOf { it.variableForecast }
        val recurringForecastTotal = categoryResults.sumOf { it.recurringForecast }
        val projectedTotal = actualTotal + futureEnteredTotal + variableForecastTotal + recurringForecastTotal
        val projectedLow = actualTotal + futureEnteredTotal +
            categoryResults.sumOf { it.projectedLow - it.actualToDate - it.futureEntered }
        val projectedHigh = actualTotal + futureEnteredTotal +
            categoryResults.sumOf { it.projectedHigh - it.actualToDate - it.futureEntered }
        val remainingToPlan = plannedTotal - actualTotal

        return FlexiblePeriodReport(
            period = period,
            totalDays = period.dayCount,
            elapsedDays = elapsedDays,
            remainingDays = remainingDays,
            isFuture = isFuture,
            isCompleted = isCompleted,
            earnedIncome = earnedIncome,
            carryoverIncome = carryoverIncome,
            budgetIncome = budgetIncome,
            plannedTotal = plannedTotal,
            actualTotal = actualTotal,
            futureEnteredTotal = futureEnteredTotal,
            variableForecastTotal = variableForecastTotal,
            recurringForecastTotal = recurringForecastTotal,
            projectedTotal = projectedTotal,
            projectedLow = projectedLow,
            projectedHigh = projectedHigh,
            remainingToPlan = remainingToPlan,
            averageActualPerElapsedDay = if (elapsedDays > 0L) actualTotal / elapsedDays else null,
            requiredPerRemainingDay = if (remainingDays > 0L) {
                (plannedTotal - actualTotal).coerceAtLeast(0.0) / remainingDays
            } else null,
            categories = categoryResults,
            additionalIncome = additionalIncome
        )
    }

    fun proratedMonthlyIncome(
        monthlyIncome: Double,
        start: LocalDate,
        end: LocalDate
    ): Double {
        require(monthlyIncome.isFinite() && monthlyIncome >= 0.0)
        require(!end.isBefore(start))
        var date = start
        var total = 0.0
        while (!date.isAfter(end)) {
            total += monthlyIncome / YearMonth.from(date).lengthOfMonth().toDouble()
            date = date.plusDays(1)
        }
        return total
    }
}
