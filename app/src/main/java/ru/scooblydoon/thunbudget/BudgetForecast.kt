package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.pow

data class ForecastExpense(
    val category: String,
    val amount: Double,
    val date: LocalDate
)

data class CategoryForecast(
    val category: String,
    val averageMonthlySpend: Double,
    val forecastForNextMonth: Double,
    /** Forecast for future days only; actual-to-date is added by the UI once. */
    val forecastForPeriod: Double,
    val historyPeriods: Int,
    val hasHistory: Boolean,
    val averagePeriodSpend: Double
) {
    val historyMonths: Int get() = historyPeriods
}

data class BudgetForecast(
    val historyFrom: YearMonth?,
    val historyTo: YearMonth?,
    val historyPeriods: Int,
    val categories: List<CategoryForecast>,
    val totalMonthlyForecast: Double,
    /** Forecast for future days only; excludes transactions already made in the target period. */
    val totalPeriodForecast: Double,
    val reportingPeriods: List<ReportingDateRange>
) {
    val historyMonths: Int get() = historyPeriods
}

object BudgetForecastCalculator {
    private const val RECENCY_DECAY = 0.85
    private const val MAX_CURRENT_PACE_WEIGHT = 0.50

    /**
     * Uses the full available history of completed reporting periods as a
     * recency-weighted baseline. Current-period actuals are a separate signal:
     * they adjust the historical daily rate gradually, but are never added to
     * the historical numerator. This prevents a new transaction from being
     * counted once as actual and again as if it were a full forecast amount.
     *
     * [monthsToAnalyze] is retained for source compatibility; all available
     * history is used, with recent periods receiving greater weight.
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

        val validExpenses = expenses.filter { it.amount.isFinite() && it.amount > 0.0 && !it.date.isAfter(asOf) }
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
        val completedExpenses = validExpenses.filter { row ->
            val range = reportingPeriodFor(row.date, firstStartDay, firstEndDay)
            range != null && completedPeriods.any { it.start == range.start && it.end == range.end }
        }

        val completedDays = completedPeriods.sumOf { daysIn(it) }
        val activeElapsedDays = if (activePeriod != null && activeExpenses.isNotEmpty()) {
            ChronoUnit.DAYS.between(activePeriod.start, asOf) + 1L
        } else 0L
        val activeTotalDays = activePeriod?.let(::daysIn) ?: 0L
        val activeWeight = if (activeElapsedDays > 0L && activeTotalDays > 0L) {
            (MAX_CURRENT_PACE_WEIGHT * activeElapsedDays.toDouble() / activeTotalDays.toDouble())
                .coerceIn(0.0, MAX_CURRENT_PACE_WEIGHT)
        } else 0.0

        val averagePeriodDays = when {
            completedPeriods.isNotEmpty() -> completedDays.toDouble() / completedPeriods.size
            activeTotalDays > 0L -> activeTotalDays.toDouble()
            else -> 0.0
        }
        val targetDays = nextPeriodDays?.coerceAtLeast(0)?.toDouble() ?: averagePeriodDays
        val monthDays = 365.2425 / 12.0
        val observedPeriods = completedPeriods.size + if (activeExpenses.isNotEmpty()) 1 else 0
        val historyStart = completedPeriods.firstOrNull()?.start ?: activePeriod?.start
        val historyEnd = if (activeExpenses.isNotEmpty()) asOf else completedPeriods.lastOrNull()?.end
        val observedExpenses = completedExpenses + activeExpenses

        val forecasts = categories.distinct().map { category ->
            val periodRates = completedPeriods.map { period ->
                val spent = completedExpenses.asSequence()
                    .filter { it.category.equals(category, ignoreCase = true) }
                    .filter { !it.date.isBefore(period.start) && !it.date.isAfter(period.end) }
                    .sumOf { it.amount }
                spent / daysIn(period).toDouble()
            }
            val hasCompletedHistory = completedPeriods.isNotEmpty() &&
                completedExpenses.any { it.category.equals(category, ignoreCase = true) }
            val historicalRate = if (completedPeriods.isEmpty()) 0.0 else {
                val cappedRates = capHighOutliers(periodRates)
                weightedAverage(cappedRates)
            }

            val currentCategoryExpenses = activeExpenses.filter {
                it.category.equals(category, ignoreCase = true)
            }
            val hasCurrentData = currentCategoryExpenses.isNotEmpty() && activeElapsedDays > 0L
            val currentRate = if (hasCurrentData) {
                currentCategoryExpenses.sumOf { it.amount } / activeElapsedDays.toDouble()
            } else 0.0

            val dailyRate = when {
                hasCompletedHistory && hasCurrentData ->
                    historicalRate * (1.0 - activeWeight) + currentRate * activeWeight
                hasCompletedHistory -> historicalRate
                hasCurrentData -> currentRate
                else -> 0.0
            }.coerceAtLeast(0.0)

            val monthlyForecast = dailyRate * monthDays
            val periodForecast = dailyRate * targetDays
            val historicalPeriodAverage = if (completedPeriods.isNotEmpty()) historicalRate * averagePeriodDays else dailyRate * averagePeriodDays
            val hasHistory = observedExpenses.any { it.category.equals(category, ignoreCase = true) }

            CategoryForecast(
                category = category,
                averageMonthlySpend = monthlyForecast,
                forecastForNextMonth = monthlyForecast,
                forecastForPeriod = periodForecast,
                historyPeriods = observedPeriods,
                hasHistory = hasHistory,
                averagePeriodSpend = historicalPeriodAverage
            )
        }

        return BudgetForecast(
            historyFrom = historyStart?.let { YearMonth.from(it) },
            historyTo = historyEnd?.let { YearMonth.from(it) },
            historyPeriods = observedPeriods,
            categories = forecasts,
            totalMonthlyForecast = forecasts.sumOf { it.forecastForNextMonth },
            totalPeriodForecast = forecasts.sumOf { it.forecastForPeriod },
            reportingPeriods = completedPeriods
        )
    }

    private fun capHighOutliers(rates: List<Double>): List<Double> {
        if (rates.size < 5) return rates
        val sorted = rates.sorted()
        val p90Index = ((sorted.size - 1) * 0.90).toInt()
        val ceiling = sorted[p90Index]
        return rates.map { minOf(it, ceiling) }
    }

    private fun weightedAverage(rates: List<Double>): Double {
        if (rates.isEmpty()) return 0.0
        var weightedTotal = 0.0
        var totalWeight = 0.0
        rates.forEachIndexed { index, rate ->
            val weight = RECENCY_DECAY.pow((rates.lastIndex - index).toDouble())
            weightedTotal += rate * weight
            totalWeight += weight
        }
        return if (totalWeight > 0.0) weightedTotal / totalWeight else 0.0
    }

    private fun daysIn(range: ReportingDateRange): Long =
        ChronoUnit.DAYS.between(range.start, range.end) + 1L

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
