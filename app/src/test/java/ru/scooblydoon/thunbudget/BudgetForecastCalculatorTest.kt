package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetForecastCalculatorTest {
    @Test fun averagesCompletedReportingPeriodsAndIncludesZeroSpendPeriods() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты", "Транспорт"),
            expenses = listOf(
                ForecastExpense("Продукты", 1000.0, LocalDate.parse("2026-08-10")),
                ForecastExpense("Продукты", 2000.0, LocalDate.parse("2026-09-10")),
                ForecastExpense("Транспорт", 500.0, LocalDate.parse("2026-09-12"))
            ),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 3
        )
        assertEquals(7, result.historyPeriods)
        assertEquals(7, result.historyMonths)
        assertEquals(3000.0 / 7.0, result.categories.first { it.category == "Продукты" }.averagePeriodSpend, 0.001)
        assertEquals(500.0 / 7.0, result.categories.first { it.category == "Транспорт" }.averagePeriodSpend, 0.001)
    }

    @Test fun includesOnlyReportingPeriodsCompletedByAsOfDate() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(
                ForecastExpense("Продукты", 1000.0, LocalDate.parse("2026-09-10")),
                ForecastExpense("Продукты", 9000.0, LocalDate.parse("2026-11-10")),
                ForecastExpense("Продукты", 50000.0, LocalDate.parse("2026-11-25"))
            ),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 6
        )
        assertEquals(5, result.historyPeriods)
        assertEquals(2000.0, result.categories.single().averagePeriodSpend, 0.001)
    }

    @Test fun shortHistoryCountsEachCompletedPeriodNotOnlyCalendarMonths() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(ForecastExpense("Продукты", 600.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 6
        )
        assertEquals(3, result.historyPeriods)
        assertEquals(200.0, result.categories.single().averagePeriodSpend, 0.001)
        assertTrue(result.categories.single().hasHistory)
    }

    @Test fun proratesNextReportingPeriodByAverageDailySpend() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(ForecastExpense("Продукты", 3000.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 1,
            nextPeriodDays = 15
        )
        assertEquals(3000.0 / 46.0 * 15.0, result.categories.single().forecastForPeriod, 0.001)
    }

    @Test fun includesPeriodThatEndsOnAsOfDate() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(ForecastExpense("Продукты", 1000.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-08"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 1
        )
        assertEquals(2, result.historyPeriods)
        assertEquals(500.0, result.categories.single().averagePeriodSpend, 0.001)
    }

    @Test fun keepsCategoriesWithoutHistoryInForecastWithZeroSpend() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты", "Накопления"),
            expenses = listOf(ForecastExpense("Продукты", 1200.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 1
        )
        val savings = result.categories.single { it.category == "Накопления" }
        assertFalse(savings.hasHistory)
        assertEquals(0.0, savings.averagePeriodSpend, 0.001)
    }

    @Test fun reportsOneCompletedPeriodWhenOnlyOnePeriodHasBeenRecorded() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Транспорт"),
            expenses = listOf(ForecastExpense("Транспорт", 2500.0, LocalDate.parse("2026-11-12"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 6
        )
        assertEquals(1, result.historyPeriods)
        assertEquals(2500.0, result.categories.single().averagePeriodSpend, 0.001)
    }
}
