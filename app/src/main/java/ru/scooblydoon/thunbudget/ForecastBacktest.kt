package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal data class ForecastBacktestMetrics(
    val evaluatedPeriods: Int,
    val evaluatedCategoryPeriods: Int,
    val actualSpend: Double,
    val forecastSpend: Double,
    val meanAbsoluteError: Double?,
    val weightedAbsolutePercentageError: Double?,
    val biasPercent: Double?,
    val calibration: ForecastCalibration = ForecastCalibration()
)

/**
 * Rolling-origin validation. Every tested period is forecast only from
 * transactions dated before that period starts. The latest 24 completed
 * reporting periods are evaluated to keep the work bounded on-device.
 */
internal object BudgetForecastBacktester {
    private const val MIN_TRAINING_PERIODS = 4
    private const val MAX_EVALUATION_PERIODS = 24

    fun evaluate(
        categories: List<String>,
        expenses: List<ForecastExpense>,
        asOf: LocalDate,
        firstStartDay: Int,
        firstEndDay: Int
    ): ForecastBacktestMetrics {
        val history = BudgetForecastCalculator.calculate(
            categories = categories,
            expenses = expenses.filter { !it.date.isAfter(asOf) },
            asOf = asOf,
            firstStartDay = firstStartDay,
            firstEndDay = firstEndDay,
            monthsToAnalyze = 0
        )
        val periods = history.reportingPeriods
        if (periods.size <= MIN_TRAINING_PERIODS) {
            return ForecastBacktestMetrics(0, 0, 0.0, 0.0, null, null, null, ForecastCalibration())
        }
        val firstTargetIndex = maxOf(MIN_TRAINING_PERIODS, periods.size - MAX_EVALUATION_PERIODS)
        var evaluatedPeriods = 0
        var evaluatedCategoryPeriods = 0
        var totalActual = 0.0
        var totalForecast = 0.0
        var absoluteError = 0.0
        val calibrationErrors = mutableMapOf<String, MutableList<Double>>()

        for (index in firstTargetIndex until periods.size) {
            val target = periods[index]
            val cutoff = target.start.minusDays(1)
            val trainingRows = expenses.filter { !it.date.isAfter(cutoff) }
            val targetDays = ChronoUnit.DAYS.between(target.start, target.end).toInt() + 1
            val prediction = BudgetForecastCalculator.calculate(
                categories = categories,
                expenses = trainingRows,
                asOf = cutoff,
                firstStartDay = firstStartDay,
                firstEndDay = firstEndDay,
                monthsToAnalyze = 0,
                nextPeriodDays = targetDays,
                forecastStartDate = target.start,
                forecastEndDate = target.end
            )
            val actualRows = expenses.filter {
                !it.date.isBefore(target.start) && !it.date.isAfter(target.end)
            }
            categories.distinct().forEach { category ->
                val actual = actualRows.asSequence()
                    .filter { it.category.equals(category, ignoreCase = true) }
                    .filterNot { it.category.equals("Перенос средств", ignoreCase = true) }
                    .sumOf { it.amount.coerceAtLeast(0.0) }
                val categoryPrediction = prediction.categories.firstOrNull {
                    it.category.equals(category, ignoreCase = true)
                }
                val forecast = categoryPrediction?.forecastForPeriod ?: 0.0
                if (categoryPrediction != null && forecast > 0.0 &&
                    categoryPrediction.calibrationScaleForPeriod > 0.0
                ) {
                    val errorScore = abs(forecast - actual) / categoryPrediction.calibrationScaleForPeriod
                    if (errorScore.isFinite()) {
                        calibrationErrors.getOrPut(category.trim().lowercase()) { mutableListOf() }
                            .add(errorScore)
                    }
                }
                totalActual += actual
                totalForecast += forecast
                absoluteError += abs(forecast - actual)
                evaluatedCategoryPeriods++
            }
            // Empty periods are valid observations and must remain in validation.
            evaluatedPeriods++
        }

        val wape = if (totalActual > 0.0) absoluteError / totalActual * 100.0 else null
        val mae = if (evaluatedCategoryPeriods > 0) absoluteError / evaluatedCategoryPeriods else null
        val bias = if (totalActual > 0.0) (totalForecast - totalActual) / totalActual * 100.0 else null
        return ForecastBacktestMetrics(
            evaluatedPeriods = evaluatedPeriods,
            evaluatedCategoryPeriods = evaluatedCategoryPeriods,
            actualSpend = totalActual,
            forecastSpend = totalForecast,
            meanAbsoluteError = mae,
            weightedAbsolutePercentageError = wape,
            biasPercent = bias,
            calibration = ForecastIntervalCalibrator.build(calibrationErrors)
        )
    }
}
