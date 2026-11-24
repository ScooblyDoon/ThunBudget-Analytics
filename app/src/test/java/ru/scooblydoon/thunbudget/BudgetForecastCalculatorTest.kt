package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetForecastCalculatorTest {
    @Test fun averagesFullReportingMonthsAndIncludesZeroSpendCategoryMonths() {
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
        assertEquals(3, result.historyMonths)
        assertEquals(1000.0, result.categories.first { it.category == "Продукты" }.averageMonthlySpend, 0.001)
        assertEquals(500.0 / 3.0, result.categories.first { it.category == "Транспорт" }.averageMonthlySpend, 0.001)
    }

    @Test fun excludesCurrentIncompleteReportingMonth() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(
                ForecastExpense("Продукты", 1000.0, LocalDate.parse("2026-09-10")),
                ForecastExpense("Продукты", 9000.0, LocalDate.parse("2026-11-10"))
            ),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 6
        )
        assertEquals(500.0, result.categories.single().averageMonthlySpend, 0.001)
    }

    @Test fun shortHistoryUsesOnlyAvailableMonths() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(ForecastExpense("Продукты", 600.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 6
        )
        assertEquals(1, result.historyMonths)
        assertEquals(600.0, result.categories.single().averageMonthlySpend, 0.001)
        assertTrue(result.categories.single().hasHistory)
    }

    @Test fun proratesNextReportingPeriodByDaysOfNextMonth() {
        val result = BudgetForecastCalculator.calculate(
            categories = listOf("Продукты"),
            expenses = listOf(ForecastExpense("Продукты", 3000.0, LocalDate.parse("2026-10-10"))),
            asOf = LocalDate.parse("2026-11-24"),
            firstStartDay = 9,
            firstEndDay = 23,
            monthsToAnalyze = 1,
            nextPeriodDays = 15
        )
        assertEquals(3000.0 / 30.0 * 15.0, result.categories.single().forecastForPeriod, 0.001)
    }
}
