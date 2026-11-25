package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetForecastCalculatorTest {
    private val startDay = 9
    private val endDay = 23

    @Test fun usesAllCompletedReportingPeriodsAcrossMonths() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты", "Транспорт"),
            expenses = listOf(
                ForecastExpense("Продукты", 1000.0, LocalDate.parse("2026-08-10")),
                ForecastExpense("Продукты", 2000.0, LocalDate.parse("2026-09-10")),
                ForecastExpense("Транспорт", 500.0, LocalDate.parse("2026-09-12"))
            ),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 3
        )
        assertEquals(7, result.historyPeriods)
        assertEquals(7, result.reportingPeriods.size)
        assertTrue(result.totalMonthlyForecast > 0.0)
    }

    @Test fun excludesCurrentPeriodActualsFromHistoricalBaseline() {
        val base = listOf(
            ForecastExpense("Продукты", 3000.0, LocalDate.parse("2026-10-10")),
            ForecastExpense("Продукты", 2400.0, LocalDate.parse("2026-10-25"))
        )
        val withoutNewExpense = BudgetForecastCalculator.calculate(
            listOf("Продукты"), base, LocalDate.parse("2026-11-26"),
            startDay, endDay, 6, nextPeriodDays = 12
        )
        val withNewExpense = BudgetForecastCalculator.calculate(
            listOf("Продукты"),
            base + ForecastExpense("Продукты", 7000.0, LocalDate.parse("2026-11-26")),
            LocalDate.parse("2026-11-26"), startDay, endDay, 6, nextPeriodDays = 12
        )
        val increase = withNewExpense.totalPeriodForecast - withoutNewExpense.totalPeriodForecast
        assertTrue("A new actual expense must adjust the forecast, not be added one-for-one", increase > 0.0)
        assertTrue("The projection must not grow by the full transaction amount", increase < 7000.0)
    }

    @Test fun currentPeriodPaceIsBlendedWithHistoricalDailyRate() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(
                ForecastExpense("Продукты", 3000.0, LocalDate.parse("2026-10-10")),
                ForecastExpense("Продукты", 900.0, LocalDate.parse("2026-11-25"))
            ),
            asOf = LocalDate.parse("2026-11-26"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6,
            nextPeriodDays = 12
        )
        assertTrue(result.categories.single().forecastForPeriod > 0.0)
        assertTrue(result.categories.single().forecastForPeriod < 900.0 / 3.0 * 12.0)
    }

    @Test fun currentPaceIsUsedWhenThereIsNoCompletedHistory() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Транспорт"),
            expenses = listOf(
                ForecastExpense("Транспорт", 1500.0, LocalDate.parse("2026-11-25")),
                ForecastExpense("Транспорт", 1500.0, LocalDate.parse("2026-11-26"))
            ),
            asOf = LocalDate.parse("2026-11-26"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6,
            nextPeriodDays = 12
        )
        assertEquals(1, result.historyPeriods)
        assertEquals(12000.0, result.categories.single().forecastForPeriod, 0.001)
    }

    @Test fun oneLargeExpenseInNewCategoryIsNotExtrapolatedWithoutEvidence() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Ремонт"),
            expenses = listOf(ForecastExpense("Ремонт", 7000.0, LocalDate.parse("2026-11-25"))),
            asOf = LocalDate.parse("2026-11-26"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6,
            nextPeriodDays = 12
        )
        assertEquals(0.0, result.totalPeriodForecast, 0.001)
    }

    @Test fun completedPeriodEndingOnAsOfDateIsIncluded() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(ForecastExpense("Продукты", 1000.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-08"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6
        )
        assertEquals(2, result.historyPeriods)
    }

    @Test fun categoriesWithoutAnySpendingRemainAtZero() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты", "Накопления"),
            expenses = listOf(ForecastExpense("Продукты", 1200.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6
        )
        val savings = result.categories.single { it.category == "Накопления" }
        assertEquals(0.0, savings.forecastForPeriod, 0.001)
    }

    @Test fun highOutlierInOldPeriodIsCappedWhenEnoughHistoryExists() {
        val rows = listOf(
            "2026-06-10" to 1000.0,
            "2026-07-10" to 1100.0,
            "2026-08-10" to 900.0,
            "2026-09-10" to 1200.0,
            "2026-10-10" to 10000.0
        ).map { ForecastExpense("Здоровье", it.second, LocalDate.parse(it.first)) }
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Здоровье"),
            expenses = rows,
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6,
            nextPeriodDays = 15
        )
        assertTrue(result.totalPeriodForecast < 5000.0)
    }


    @Test fun intermittentCategoryKeepsZeroSpendPeriodsInItsBaseline() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Подарки"),
            expenses = listOf(ForecastExpense("Подарки", 3000.0, LocalDate.parse("2026-08-10"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 3,
            nextPeriodDays = 15
        )
        assertEquals(7, result.reportingPeriods.size)
        assertTrue("Zero-spend periods must reduce an intermittent category's baseline",
            result.totalPeriodForecast > 0.0 && result.totalPeriodForecast < 3000.0)
    }

    @Test fun historyIsNotTruncatedAtSixOrTwelveMonths() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(
                ForecastExpense("Продукты", 1500.0, LocalDate.parse("2025-01-10")),
                ForecastExpense("Продукты", 1800.0, LocalDate.parse("2026-10-10"))
            ),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6,
            nextPeriodDays = 15
        )
        assertTrue("The forecast must retain more than a year of completed periods",
            result.reportingPeriods.size > 20)
        assertEquals(2025, result.historyFrom!!.year)
    }

    @Test fun oneLargeCurrentPeriodPurchaseCannotRaisePaceAboveTwiceHistoricalRate() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(
                ForecastExpense("Продукты", 1500.0, LocalDate.parse("2026-10-10")),
                ForecastExpense("Продукты", 10000.0, LocalDate.parse("2026-11-09"))
            ),
            asOf = LocalDate.parse("2026-11-09"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6,
            nextPeriodDays = 15
        )
        assertTrue("A one-day spike must be capped and blended, not projected as a new habit",
            result.totalPeriodForecast < 1200.0)
    }

    @Test fun expensesAfterAsOfAreExcludedFromForecastEvidence() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(
                ForecastExpense("Продукты", 1500.0, LocalDate.parse("2026-10-10")),
                ForecastExpense("Продукты", 50000.0, LocalDate.parse("2026-11-27"))
            ),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = startDay,
            firstEndDay = endDay,
            monthsToAnalyze = 6,
            nextPeriodDays = 15
        )
        assertTrue("Future-dated transactions must not influence the forecast",
            result.totalPeriodForecast < 2000.0)
    }

}
