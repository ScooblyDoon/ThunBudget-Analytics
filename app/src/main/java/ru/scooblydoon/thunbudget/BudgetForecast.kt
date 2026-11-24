package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth

/**
 * Forecasts based on every available completed reporting period.
 * The current unfinished reporting month is excluded; older partial history is retained.
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
        require(firstStartDay in 1..31 && firstEndDay in firstStartDay..31)

        val datedExpenses = expenses.filter { it.amount.isFinite() && it.amount > 0.0 }
        val observedMonths = datedExpenses.mapNotNull { reportingMonthFor(it.date, firstStartDay, firstEndDay) }.distinct().sorted()
        val earliestObserved = observedMonths.firstOrNull()
        val completed = completedReportingMonths(asOf, firstStartDay, firstEndDay, earliestObserved)
        val latestCompleted = completed.lastOrNull()
        val historyEnd = latestCompleted?.takeIf { earliestObserved != null && !it.isBefore(earliestObserved) }
        val historyStart = historyEnd?.let { end ->
            earliestObserved!!
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
                // A reporting period may cross two calendar months (e.g. 24th–8th).
                // Scale the monthly average by the exact number of days in the next
                // configured reporting period, using the observed average cycle length.
                val nextCycle = ReportingPeriodCalculator.propose(
                    latestCompleted!!.plusMonths(1), firstStartDay, firstEndDay
                )
                val fullTargetDays = (java.time.temporal.ChronoUnit.DAYS.between(
                    nextCycle.first.start, nextCycle.second.end
                ) + 1L).toInt()
                val targetDays = nextPeriodDays.coerceAtLeast(0).coerceAtMost(fullTargetDays)
                val historyDays = history.sumOf { month ->
                    val cycle = ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay)
                    java.time.temporal.ChronoUnit.DAYS.between(cycle.first.start, cycle.second.end) + 1
                }
                val averageCycleDays = if (history.isNotEmpty()) historyDays.toDouble() / history.size else targetDays.toDouble()
                if (averageCycleDays > 0.0) average / averageCycleDays * targetDays else 0.0
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
        firstEndDay: Int,
        earliestObserved: YearMonth?
    ): List<YearMonth> {
        val current = YearMonth.from(asOf)
        val start = earliestObserved ?: return emptyList()
        if (start.isAfter(current)) return emptyList()
        return generateSequence(start) { month ->
            if (month.isBefore(current)) month.plusMonths(1) else null
        }.filter { month ->
            val proposal = ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay)
            !asOf.isBefore(proposal.second.end)
        }.toList()
    }
}
