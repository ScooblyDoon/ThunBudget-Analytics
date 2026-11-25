package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Forecasts are based on completed reporting periods, not calendar months.
 * The current unfinished period is excluded; all older completed periods
 * between the first observed expense and today are included.
 */
data class ForecastExpense(
    val category: String,
    val amount: Double,
    val date: LocalDate
)

data class CategoryForecast(
    val category: String,
    val averageMonthlySpend: Double,
    val forecastForNextMonth: Double,
    val forecastForPeriod: Double,
    val historyPeriods: Int,
    val hasHistory: Boolean,
    val averagePeriodSpend: Double
) {
    // Kept as a compatibility alias for older UI/tests.
    val historyMonths: Int get() = historyPeriods
}

data class BudgetForecast(
    val historyFrom: YearMonth?,
    val historyTo: YearMonth?,
    val historyPeriods: Int,
    val categories: List<CategoryForecast>,
    val totalMonthlyForecast: Double,
    val totalPeriodForecast: Double,
    val reportingPeriods: List<ReportingDateRange>
) {
    // Kept as a compatibility alias for older UI/tests.
    val historyMonths: Int get() = historyPeriods
}

object BudgetForecastCalculator {
    /**
     * [monthsToAnalyze] is retained for source compatibility. Forecasting now
     * uses the full available history of completed reporting periods.
     */
    @Suppress("UNUSED_PARAMETER")
    fun calculate(
        categories: List<String>,
        expenses: List<ForecastExpense>,
        asOf: LocalDate,
        firstStartDay: Int,
        firstEndDay: Int,
        monthsToAnalyze: Int,
        nextPeriodDays: Int? = null
    ): BudgetForecast {
        require(firstStartDay in 1..31 && firstEndDay in firstStartDay..31)

        val validExpenses = expenses.filter { it.amount.isFinite() && it.amount > 0.0 }
        val earliestPeriod = validExpenses.mapNotNull {
            reportingPeriodFor(it.date, firstStartDay, firstEndDay)
        }.minByOrNull { it.start }

        val completedPeriods = if (earliestPeriod == null) emptyList() else {
            completedPeriodsBetween(earliestPeriod, asOf, firstStartDay, firstEndDay)
        }
        val activePeriod = reportingPeriodFor(asOf, firstStartDay, firstEndDay)
            ?.takeIf { it.end.isAfter(asOf) }
        val activeExpenses = if (activePeriod == null) emptyList() else validExpenses.filter { row ->
            !row.date.isBefore(activePeriod.start) && !row.date.isAfter(asOf)
        }
        val hasActivePeriodData = activeExpenses.isNotEmpty()
        val observedPeriodCount = completedPeriods.size + if (hasActivePeriodData) 1 else 0
        val historyStart = completedPeriods.firstOrNull()?.start ?: activePeriod?.start
        val historyEnd = if (hasActivePeriodData) asOf else completedPeriods.lastOrNull()?.end
        val completedDays = completedPeriods.sumOf {
            ChronoUnit.DAYS.between(it.start, it.end) + 1L
        }
        val activeElapsedDays = if (hasActivePeriodData && activePeriod != null) {
            ChronoUnit.DAYS.between(activePeriod.start, asOf) + 1L
        } else 0L
        val observedDays = completedDays + activeElapsedDays
        val averagePeriodDays = when {
            completedPeriods.isNotEmpty() -> completedDays.toDouble() / completedPeriods.size
            activePeriod != null -> (ChronoUnit.DAYS.between(activePeriod.start, activePeriod.end) + 1L).toDouble()
            else -> 0.0
        }
        val averageMonthDays = 365.2425 / 12.0

        val completedExpenses = validExpenses.filter { row ->
            val range = reportingPeriodFor(row.date, firstStartDay, firstEndDay)
            range != null && completedPeriods.any { it.start == range.start && it.end == range.end }
        }
        val expensesInHistory = completedExpenses + activeExpenses

        val forecasts = categories.distinct().map { category ->
            val categoryTotal = expensesInHistory
                .filter { it.category.equals(category, ignoreCase = true) }
                .sumOf { it.amount }
            val averagePerDay = if (observedDays > 0L) categoryTotal / observedDays.toDouble() else 0.0
            val averagePerPeriod = averagePerDay * averagePeriodDays
            val averagePerMonth = averagePerDay * averageMonthDays
            val targetDays = nextPeriodDays?.coerceAtLeast(0) ?: averagePeriodDays.toInt()
            val periodForecast = if (observedDays > 0L) averagePerDay * targetDays else 0.0
            val hasHistory = expensesInHistory.any { it.category.equals(category, ignoreCase = true) }

            CategoryForecast(
                category = category,
                averageMonthlySpend = averagePerMonth,
                forecastForNextMonth = averagePerMonth,
                forecastForPeriod = periodForecast,
                historyPeriods = observedPeriodCount,
                hasHistory = hasHistory,
                averagePeriodSpend = averagePerPeriod
            )
        }

        return BudgetForecast(
            historyFrom = historyStart?.let { YearMonth.from(it) },
            historyTo = historyEnd?.let { YearMonth.from(it) },
            historyPeriods = observedPeriodCount,
            categories = forecasts,
            totalMonthlyForecast = forecasts.sumOf { it.forecastForNextMonth },
            totalPeriodForecast = forecasts.sumOf { it.forecastForPeriod },
            reportingPeriods = completedPeriods
        )
    }

    private fun reportingPeriodFor(
        date: LocalDate,
        firstStartDay: Int,
        firstEndDay: Int
    ): ReportingDateRange? {
        val candidateMonths = listOf(YearMonth.from(date), YearMonth.from(date).minusMonths(1))
        return candidateMonths.asSequence().mapNotNull { month ->
            runCatching { ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay) }.getOrNull()
        }.flatMap { proposal -> sequenceOf(proposal.first, proposal.second) }
            .firstOrNull { !date.isBefore(it.start) && !date.isAfter(it.end) }
    }

    private fun completedPeriodsBetween(
        earliest: ReportingDateRange,
        asOf: LocalDate,
        firstStartDay: Int,
        firstEndDay: Int
    ): List<ReportingDateRange> {
        if (asOf.isBefore(earliest.end)) return emptyList()
        val firstMonth = YearMonth.from(earliest.start)
        val lastMonth = YearMonth.from(asOf)
        val monthCount = ChronoUnit.MONTHS.between(firstMonth, lastMonth).coerceAtLeast(0L)
        return (0L..monthCount).flatMap { offset ->
            val month = firstMonth.plusMonths(offset)
            runCatching { ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay) }
                .getOrNull()
                ?.let { listOf(it.first, it.second) }
                .orEmpty()
        }.asSequence()
            .filter { it.start >= earliest.start && !it.end.isAfter(asOf) }
            .distinctBy { it.start to it.end }
            .sortedBy { it.start }
            .toList()
    }
}
