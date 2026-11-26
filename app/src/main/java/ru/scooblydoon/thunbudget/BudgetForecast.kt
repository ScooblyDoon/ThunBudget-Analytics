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
    val averagePeriodSpend: Double,
    val profile: String = "Недостаточно данных",
    val forecastLowForPeriod: Double = forecastForPeriod,
    val forecastHighForPeriod: Double = forecastForPeriod,
    val trendPercent: Double = 0.0,
    val volatilityPercent: Double = 0.0,
    val evidenceQuality: String = "Низкая",
    val positiveHistoryPeriods: Int = 0,
    val currentPeriodAnomaly: Boolean = false,
    val explanation: String = ""
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
    private const val MAX_CURRENT_PACE_WEIGHT = 0.35
    private const val MAX_CURRENT_PACE_MULTIPLIER = 2.0

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
            val periodAmounts = completedPeriods.map { period ->
                completedExpenses.asSequence()
                    .filter { it.category.equals(category, ignoreCase = true) }
                    .filter { !it.date.isBefore(period.start) && !it.date.isAfter(period.end) }
                    .sumOf { it.amount }
            }
            val periodRates = periodAmounts.mapIndexed { index, amount ->
                amount / daysIn(completedPeriods[index]).toDouble()
            }
            val positiveRates = periodRates.filter { it > 0.0 }
            val recentRates = periodRates.takeLast(12)
            val positiveFrequency = if (recentRates.isEmpty()) 0.0
                else recentRates.count { it > 0.0 }.toDouble() / recentRates.size.toDouble()
            val hasCompletedHistory = positiveRates.isNotEmpty()
            val historicalRateRaw = if (completedPeriods.isEmpty()) 0.0 else {
                weightedAverage(capHighOutliers(periodRates))
            }
            val typicalPositiveRate = median(positiveRates)
            var profile = when {
                periodRates.isEmpty() || positiveRates.size < 2 -> "Недостаточно данных"
                positiveFrequency <= 0.35 -> "Редкие расходы"
                positiveFrequency >= 0.70 -> "Регулярные расходы"
                else -> "Переменные расходы"
            }
            // For intermittent categories, estimate expected spend as
            // typical event size multiplied by the probability of an event.
            val historicalRate = if (
                profile == "Редкие расходы" && positiveRates.size >= 2 && averagePeriodDays > 0.0
            ) {
                // Croston-style estimate: smooth event size and the interval
                // between events separately, then divide to get expected spend
                // per reporting period and convert it to a daily rate.
                crostonPeriodForecast(periodAmounts.takeLast(12)) / averagePeriodDays
            } else historicalRateRaw
            val trendFactor = dampedTrendFactor(periodRates)
            val trendAdjustedRate = historicalRate * trendFactor
            val currentCategoryExpenses = activeExpenses.filter {
                it.category.equals(category, ignoreCase = true)
            }
            val hasCurrentData = currentCategoryExpenses.isNotEmpty() && activeElapsedDays > 0L
            val currentRate = if (hasCurrentData) {
                currentCategoryExpenses.sumOf { it.amount } / activeElapsedDays.toDouble()
            } else 0.0

            // The current pace is a secondary signal. A single unusually large
            // purchase is kept in actuals but cannot become the new baseline.
            val cappedCurrentRate = if (hasCompletedHistory && historicalRate > 0.0) {
                minOf(currentRate, historicalRate * MAX_CURRENT_PACE_MULTIPLIER)
            } else currentRate
            val enoughNewCategoryEvidence = currentCategoryExpenses.size >= 2 ||
                (activeTotalDays > 0L && activeElapsedDays.toDouble() / activeTotalDays.toDouble() >= 0.5)
            if (!hasCompletedHistory && hasCurrentData && enoughNewCategoryEvidence) {
                profile = "Новая категория"
            }
            val dailyRate = when {
                hasCompletedHistory && hasCurrentData ->
                    trendAdjustedRate * (1.0 - activeWeight) + cappedCurrentRate * activeWeight
                hasCompletedHistory -> trendAdjustedRate
                hasCurrentData && enoughNewCategoryEvidence -> currentRate
                else -> 0.0
            }.coerceAtLeast(0.0)

            val volatility = robustVolatility(periodRates)
            val volatilityPercent = volatility * 100.0
            val evidenceQuality = when {
                periodRates.size >= 12 && positiveRates.size >= 8 && volatility <= 0.45 -> "Высокая"
                periodRates.size >= 6 && positiveRates.size >= 4 -> "Средняя"
                else -> "Низкая"
            }
            // A transparent planning range, not a statistically calibrated
            // confidence interval. It widens when history is short or unstable.
            var uncertainty = (0.15 + volatility * 0.45 +
                if (periodRates.size < 6) 0.25 else 0.0).coerceIn(0.15, 0.85)
            if (profile == "Редкие расходы") {
                uncertainty = maxOf(uncertainty, (1.0 - positiveFrequency) * 0.65)
            }
            val monthlyForecast = dailyRate * monthDays
            val periodForecast = dailyRate * targetDays
            val forecastLow = periodForecast * (1.0 - uncertainty).coerceAtLeast(0.0)
            val forecastHigh = periodForecast * (1.0 + uncertainty)
            val historicalPeriodAverage = if (completedPeriods.isNotEmpty()) {
                historicalRate * averagePeriodDays
            } else dailyRate * averagePeriodDays

            val enoughNewCategoryHistory = hasCurrentData && enoughNewCategoryEvidence
            val hasHistory = hasCompletedHistory || enoughNewCategoryHistory
            val positiveMedian = median(positiveRates)
            val positiveMad = median(positiveRates.map { kotlin.math.abs(it - positiveMedian) })
            val anomalyThreshold = if (positiveMedian > 0.0) {
                positiveMedian + maxOf(positiveMedian * 0.8, positiveMad * 3.0 * 1.4826)
            } else Double.POSITIVE_INFINITY
            val currentPeriodAnomaly = hasCompletedHistory && hasCurrentData &&
                currentRate > anomalyThreshold
            val trendPercent = (trendFactor - 1.0) * 100.0
            val trendText = when {
                trendPercent >= 5.0 -> "тренд повышает расчётную ставку на ${trendPercent.toInt()}%"
                trendPercent <= -5.0 -> "тренд снижает расчётную ставку на ${kotlin.math.abs(trendPercent).toInt()}%"
                else -> "выраженного тренда нет"
            }
            val frequencyText = "${(positiveFrequency * 100.0).toInt()}% последних периодов с расходами"
            val explanation = when {
                !hasHistory -> "Недостаточно повторяющихся данных для прогноза"
                currentPeriodAnomaly -> "Трата в текущем периоде заметно выше обычного темпа; она учтена в факте, но ограниченно влияет на прогноз"
                profile == "Редкие расходы" -> "Редкие траты: учтены частота появления и типичный размер; $frequencyText"
                profile == "Новая категория" -> "Новая категория: оценка основана на текущих данных; накопленной истории пока нет"
                else -> "Профиль: $profile; $trendText; $frequencyText"
            }

            CategoryForecast(
                category = category,
                averageMonthlySpend = monthlyForecast,
                forecastForNextMonth = monthlyForecast,
                forecastForPeriod = periodForecast,
                historyPeriods = observedPeriods,
                hasHistory = hasHistory,
                averagePeriodSpend = historicalPeriodAverage,
                profile = profile,
                forecastLowForPeriod = forecastLow,
                forecastHighForPeriod = forecastHigh,
                trendPercent = trendPercent,
                volatilityPercent = volatilityPercent,
                evidenceQuality = evidenceQuality,
                positiveHistoryPeriods = positiveRates.size,
                currentPeriodAnomaly = currentPeriodAnomaly,
                explanation = explanation
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


    private fun crostonPeriodForecast(periodAmounts: List<Double>, alpha: Double = 0.30): Double {
        val events = periodAmounts.mapIndexedNotNull { index, amount ->
            if (amount > 0.0) index to amount else null
        }
        if (events.size < 2) {
            val positives = events.map { it.second }
            return median(positives) * events.size.toDouble() / periodAmounts.size.coerceAtLeast(1).toDouble()
        }
        var smoothedSize = events.first().second
        var smoothedInterval = (events[1].first - events[0].first).coerceAtLeast(1).toDouble()
        for (index in 1 until events.size) {
            val event = events[index]
            smoothedSize += alpha * (event.second - smoothedSize)
            if (index > 1) {
                val interval = (event.first - events[index - 1].first).coerceAtLeast(1).toDouble()
                smoothedInterval += alpha * (interval - smoothedInterval)
            }
        }
        return smoothedSize / smoothedInterval.coerceAtLeast(1.0)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }

    private fun robustVolatility(rates: List<Double>): Double {
        if (rates.size < 2) return 1.0
        val center = median(rates)
        if (center <= 0.0) return 1.0
        val mad = median(rates.map { kotlin.math.abs(it - center) })
        return (1.4826 * mad / center).coerceIn(0.0, 2.0)
    }

    /**
     * Compare the last three observations with the preceding three and damp
     * the difference before projecting it forward. This deliberately avoids
     * fitting an aggressive line through a handful of volatile transactions.
     */
    private fun dampedTrendFactor(rates: List<Double>): Double {
        if (rates.size < 6) return 1.0
        val prior = rates.dropLast(3).takeLast(3)
        val recent = rates.takeLast(3)
        if (prior.isEmpty() || recent.isEmpty()) return 1.0
        val priorAverage = prior.average()
        if (priorAverage <= 0.0) return 1.0
        val change = (recent.average() - priorAverage) / priorAverage
        return (1.0 + change * 0.35).coerceIn(0.85, 1.15)
    }

    private fun capHighOutliers(rates: List<Double>): List<Double> {
        // Zero-spend periods are meaningful in the average, but must not push
        // the percentile ceiling to zero for intermittent categories.
        val positive = rates.filter { it > 0.0 }.sorted()
        if (positive.size < 5) return rates
        val p90Index = ((positive.size - 1) * 0.90).toInt()
        val ceiling = positive[p90Index]
        return rates.map { if (it > 0.0) minOf(it, ceiling) else 0.0 }
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
