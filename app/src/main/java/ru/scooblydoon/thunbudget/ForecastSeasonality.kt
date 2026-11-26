package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal data class SeasonalityEstimate(
    val factor: Double = 1.0,
    val completeCycles: Int = 0,
    val applied: Boolean = false
)

internal data class SeasonallyAdjustedForecast(
    val amount: Double,
    val averageFactor: Double,
    val completeCycles: Int,
    val applied: Boolean
)

/**
 * Multiplicative calendar seasonality estimated from complete 12-month blocks.
 * Each block is normalized by its own average daily spend, reducing trend
 * leakage into the month-of-year factor. A factor is withheld until two full
 * comparable cycles are available for the category.
 */
internal object CalendarSeasonalityModel {
    private const val MIN_COMPLETE_CYCLES = 2
    private const val MIN_FACTOR = 0.50
    private const val MAX_FACTOR = 1.80

    fun estimate(
        category: String,
        expenses: List<ForecastExpense>,
        asOf: LocalDate,
        targetMonth: YearMonth
    ): SeasonalityEstimate {
        val categoryRows = expenses.filter {
            it.category.equals(category, ignoreCase = true) &&
                it.amount.isFinite() && it.amount > 0.0 && !it.date.isAfter(asOf)
        }
        val earliestDate = categoryRows.minOfOrNull { it.date } ?: return SeasonalityEstimate()
        val firstCompleteMonth = YearMonth.from(earliestDate).plusMonths(1)
        val lastCompleteMonth = YearMonth.from(asOf).minusMonths(1)
        if (lastCompleteMonth.isBefore(firstCompleteMonth)) return SeasonalityEstimate()

        val completeMonthCount = ChronoUnit.MONTHS.between(firstCompleteMonth, lastCompleteMonth) + 1L
        val cycleCount = (completeMonthCount / 12L).toInt()
        if (cycleCount < MIN_COMPLETE_CYCLES) return SeasonalityEstimate()

        val monthlyTotals = mutableMapOf<YearMonth, Double>()
        categoryRows.forEach { row ->
            val month = YearMonth.from(row.date)
            if (!month.isBefore(firstCompleteMonth) && !month.isAfter(lastCompleteMonth)) {
                monthlyTotals[month] = (monthlyTotals[month] ?: 0.0) + row.amount
            }
        }

        val targetOffset = ChronoUnit.MONTHS.between(firstCompleteMonth, targetMonth).toInt()
        val monthIndexWithinCycle = Math.floorMod(targetOffset, 12)
        val comparableRatios = mutableListOf<Double>()
        repeat(cycleCount) { cycle ->
            val cycleStart = firstCompleteMonth.plusMonths(cycle.toLong() * 12L)
            val dailyRates = (0 until 12).map { offset ->
                val month = cycleStart.plusMonths(offset.toLong())
                (monthlyTotals[month] ?: 0.0) / month.lengthOfMonth().toDouble()
            }
            val annualDailyBaseline = dailyRates.average()
            if (annualDailyBaseline > 0.0) {
                comparableRatios += dailyRates[monthIndexWithinCycle] / annualDailyBaseline
            }
        }
        if (comparableRatios.size < MIN_COMPLETE_CYCLES) {
            return SeasonalityEstimate(completeCycles = comparableRatios.size)
        }

        val sorted = comparableRatios.sorted()
        val middle = sorted.size / 2
        val historicalRatio = if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
        val shrinkage = comparableRatios.size.toDouble() / (comparableRatios.size + 2.0)
        val factor = (1.0 + (historicalRatio - 1.0) * shrinkage)
            .coerceIn(MIN_FACTOR, MAX_FACTOR)
        return SeasonalityEstimate(
            factor = factor,
            completeCycles = comparableRatios.size,
            applied = abs(factor - 1.0) >= 0.03
        )
    }

    fun project(
        dailyRate: Double,
        category: String,
        expenses: List<ForecastExpense>,
        asOf: LocalDate,
        startDate: LocalDate,
        endDate: LocalDate
    ): SeasonallyAdjustedForecast {
        if (dailyRate <= 0.0 || endDate.isBefore(startDate)) {
            return SeasonallyAdjustedForecast(0.0, 1.0, 0, false)
        }
        var cursor = startDate
        var total = 0.0
        var weightedFactor = 0.0
        var totalDays = 0L
        var minimumCycles = Int.MAX_VALUE
        var applied = false
        while (!cursor.isAfter(endDate)) {
            val month = YearMonth.from(cursor)
            val segmentEnd = minOf(endDate, month.atEndOfMonth())
            val days = ChronoUnit.DAYS.between(cursor, segmentEnd) + 1L
            val estimate = estimate(category, expenses, asOf, month)
            total += dailyRate * days.toDouble() * estimate.factor
            weightedFactor += days.toDouble() * estimate.factor
            totalDays += days
            minimumCycles = minOf(minimumCycles, estimate.completeCycles)
            applied = applied || estimate.applied
            cursor = segmentEnd.plusDays(1)
        }
        return SeasonallyAdjustedForecast(
            amount = total,
            averageFactor = if (totalDays > 0L) weightedFactor / totalDays.toDouble() else 1.0,
            completeCycles = if (minimumCycles == Int.MAX_VALUE) 0 else minimumCycles,
            applied = applied
        )
    }
}
