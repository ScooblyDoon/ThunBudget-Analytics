package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth

/**
 * Forecast calculations based on complete two-period reporting months.
 * A reporting month is complete only after the second reporting period ends.
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
    val historyMonths: Int,
    val hasHistory: Boolean
)

data class BudgetForecast(
    val historyFrom: YearMonth?,
    val historyTo: YearMonth?,
    val historyMonths: Int,
    val categories: List<CategoryForecast>,
    val totalMonthlyForecast: Double,
    val totalPeriodForecast: Double
)

object BudgetForecastCalculator {
    /**
     * Uses a rolling window of up to [monthsToAnalyze] completed reporting months.
     * Months between the first and last observed expense month are retained even
     * when a category (or the whole month) has no expenses, so averages are not inflated.
     */
    fun calculate(
        categories: List<String>,
        expenses: List<ForecastExpense>,
        asOf: LocalDate,
        firstStartDay: Int,
        firstEndDay: Int,
        monthsToAnalyze: Int,
        nextPeriodDays: Int? = null
    ): BudgetForecast {
        require(monthsToAnalyze in 1..6)
        require(firstStartDay in 1..31 && firstEndDay in firstStartDay..31)

        val completed = completedReportingMonths(asOf, firstStartDay, firstEndDay)
        val latestCompleted = completed.lastOrNull()
        val datedExpenses = expenses.filter { it.amount.isFinite() && it.amount > 0.0 }
        val observedMonths = datedExpenses.mapNotNull { reportingMonthFor(it.date, firstStartDay, firstEndDay) }.distinct().sorted()
        val earliestObserved = observedMonths.firstOrNull()
        val historyEnd = latestCompleted?.takeIf { earliestObserved != null && !it.isBefore(earliestObserved) }
        val historyStart = historyEnd?.let { end ->
            val availableStart = earliestObserved!!
            val requestedStart = end.minusMonths((monthsToAnalyze - 1).toLong())
            if (availableStart.isAfter(requestedStart)) availableStart else requestedStart
        }
        val history = if (historyStart != null && historyEnd != null) {
            generateSequence(historyEnd) { current ->
                if (current.isAfter(historyStart)) current.minusMonths(1) else null
            }.toList().asReversed()
        } else emptyList()

        val inHistory = datedExpenses.filter { row ->
            val month = reportingMonthFor(row.date, firstStartDay, firstEndDay)
            historyStart != null && historyEnd != null && month != null &&
                !month.isBefore(historyStart) && !month.isAfter(historyEnd)
        }
        val monthCount = history.size
        val categoryForecasts = categories.distinct().map { category ->
            val total = inHistory.filter { it.category.equals(category, ignoreCase = true) }.sumOf { it.amount }
            val average = if (monthCount > 0) total / monthCount else 0.0
            val hasHistory = inHistory.any { it.category.equals(category, ignoreCase = true) }
            val periodForecast = if (monthCount > 0 && nextPeriodDays != null) {
                val targetMonth = latestCompleted!!.plusMonths(1)
                average / targetMonth.lengthOfMonth() * nextPeriodDays.coerceAtLeast(0)
            } else average
            CategoryForecast(category, average, average, periodForecast, monthCount, hasHistory)
        }
        return BudgetForecast(
            historyFrom = historyStart,
            historyTo = historyEnd,
            historyMonths = monthCount,
            categories = categoryForecasts,
            totalMonthlyForecast = categoryForecasts.sumOf { it.forecastForNextMonth },
            totalPeriodForecast = categoryForecasts.sumOf { it.forecastForPeriod }
        )
    }


    private fun reportingMonthFor(date: LocalDate, firstStartDay: Int, firstEndDay: Int): YearMonth? {
        val candidates = listOf(YearMonth.from(date), YearMonth.from(date).minusMonths(1))
        return candidates.firstOrNull { month ->
            val proposal = ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay)
            !date.isBefore(proposal.first.start) && !date.isAfter(proposal.second.end)
        }
    }

    private fun completedReportingMonths(
        asOf: LocalDate,
        firstStartDay: Int,
        firstEndDay: Int
    ): List<YearMonth> {
        val current = YearMonth.from(asOf)
        // Search far enough back to cover six completed months and boundary clamping.
        return (0L..12L).map { current.minusMonths(it) }
            .filter { month ->
                val proposal = ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay)
                asOf.isAfter(proposal.second.end)
            }.sorted()
    }
}
