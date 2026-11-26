package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FlexiblePeriodsTest {
    private fun period(id: String, name: String, start: String, end: String) =
        FlexiblePeriodDefinition(id, name, LocalDate.parse(start), LocalDate.parse(end))

    @Test
    fun periodsAllowAnyLengthWhenTheyAreContiguous() {
        val first = period("one", "Короткий", "2026-02-01", "2026-02-04")
        val second = period("two", "Средний", "2026-02-05", "2026-02-18")
        val third = period("three", "Длинный", "2026-02-19", "2026-03-31")

        val ordered = FlexiblePeriodRules.validateSequence(listOf(third, first, second))

        assertEquals(listOf("one", "two", "three"), ordered.map { it.id })
        assertEquals(4L, ordered[0].dayCount)
        assertEquals(14L, ordered[1].dayCount)
        assertEquals(41L, ordered[2].dayCount)
    }

    @Test
    fun addingAPeriodAtEitherEndPreservesOneToOneDateAssignment() {
        val middle = period("middle", "Основной", "2026-05-10", "2026-05-20")
        val before = period("before", "До", "2026-05-01", "2026-05-09")
        val after = period("after", "После", "2026-05-21", "2026-06-03")

        val result = FlexiblePeriodRules.add(
            FlexiblePeriodRules.add(listOf(middle), after),
            before
        )

        assertEquals(listOf("before", "middle", "after"), result.map { it.id })
        assertEquals("before", FlexiblePeriodRules.periodFor(result, LocalDate.parse("2026-05-09"))?.id)
        assertEquals("middle", FlexiblePeriodRules.periodFor(result, LocalDate.parse("2026-05-10"))?.id)
        assertEquals("after", FlexiblePeriodRules.periodFor(result, LocalDate.parse("2026-05-21"))?.id)
        assertNull(FlexiblePeriodRules.periodFor(result, LocalDate.parse("2026-04-30")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun periodsCannotOverlap() {
        FlexiblePeriodRules.validateSequence(
            listOf(
                period("one", "Первый", "2026-01-01", "2026-01-14"),
                period("two", "Второй", "2026-01-14", "2026-01-31")
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun periodsCannotLeaveAnUnassignedDateGap() {
        FlexiblePeriodRules.validateSequence(
            listOf(
                period("one", "Первый", "2026-01-01", "2026-01-14"),
                period("two", "Второй", "2026-01-16", "2026-01-31")
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun addingANonAdjacentPeriodIsRejected() {
        FlexiblePeriodRules.add(
            listOf(period("one", "Первый", "2026-01-01", "2026-01-14")),
            period("two", "Второй", "2026-01-20", "2026-02-02")
        )
    }

    @Test
    fun monthlyFallbackIsProratedByActualCalendarDaysAcrossLeapFebruaryAndMarch() {
        val result = FlexiblePeriodAnalyticsCalculator.proratedMonthlyIncome(
            monthlyIncome = 100000.0,
            start = LocalDate.parse("2024-02-20"),
            end = LocalDate.parse("2024-03-05")
        )
        val expected = 10.0 * 100000.0 / 29.0 + 5.0 * 100000.0 / 31.0
        assertEquals(expected, result, 0.001)
    }

    @Test
    fun reportUsesIncomeEntriesByDateAndCountsCarryoverAsAvailableIncome() {
        val report = FlexiblePeriodAnalyticsCalculator.calculate(
            period = period("p", "Период", "2026-02-20", "2026-03-05"),
            categories = listOf(FlexiblePeriodCategoryPlan("Продукты", 50.0)),
            expenses = emptyList(),
            incomes = listOf(
                FlexiblePeriodIncome(10000.0, LocalDate.parse("2026-02-20")),
                FlexiblePeriodIncome(2000.0, LocalDate.parse("2026-02-24"), isCarryover = true)
            ),
            monthlyIncomeFallback = 100000.0,
            asOf = LocalDate.parse("2026-02-25")
        )

        assertEquals(10000.0, report.earnedIncome, 0.001)
        assertEquals(2000.0, report.carryoverIncome, 0.001)
        assertEquals(12000.0, report.budgetIncome, 0.001)
        assertEquals(6000.0, report.plannedTotal, 0.001)
        assertEquals(6000.0, report.categories.single().planned, 0.001)
    }

    @Test
    fun reportSeparatesActualFutureEntriesAndForecastAndExcludesTransfers() {
        val report = FlexiblePeriodAnalyticsCalculator.calculate(
            period = period("p", "Февраль–март", "2026-02-20", "2026-03-05"),
            categories = listOf(
                FlexiblePeriodCategoryPlan("Продукты", 50.0),
                FlexiblePeriodCategoryPlan("Перенос средств", 50.0)
            ),
            expenses = listOf(
                FlexiblePeriodExpense(1L, "Покупка", "Продукты", 2000.0, LocalDate.parse("2026-02-21")),
                FlexiblePeriodExpense(2L, "Запланированная покупка", "Продукты", 5000.0, LocalDate.parse("2026-03-02")),
                FlexiblePeriodExpense(3L, "Перенос", "Перенос средств", 1000.0, LocalDate.parse("2026-02-22"))
            ),
            incomes = listOf(
                FlexiblePeriodIncome(10000.0, LocalDate.parse("2026-02-20")),
                FlexiblePeriodIncome(2000.0, LocalDate.parse("2026-02-24"), isCarryover = true)
            ),
            monthlyIncomeFallback = 100000.0,
            asOf = LocalDate.parse("2026-02-25"),
            forecasts = mapOf(
                "Продукты" to FlexiblePeriodForecast(
                    variable = 2000.0,
                    low = 1500.0,
                    high = 2500.0,
                    recurring = 1000.0
                )
            )
        )

        val products = report.categories.single()
        assertEquals(2000.0, products.actualToDate, 0.001)
        assertEquals(5000.0, products.futureEntered, 0.001)
        assertEquals(2000.0, products.variableForecast, 0.001)
        assertEquals(1000.0, products.recurringForecast, 0.001)
        assertEquals(10000.0, products.projectedTotal, 0.001)
        assertEquals(9500.0, products.projectedLow, 0.001)
        assertEquals(10500.0, products.projectedHigh, 0.001)
        assertEquals(2000.0, report.actualTotal, 0.001)
        assertEquals(5000.0, report.futureEnteredTotal, 0.001)
        assertEquals(10000.0, report.projectedTotal, 0.001)
        assertEquals(33.333, products.actualExecutionPercent!!, 0.01)
        assertEquals(166.667, products.projectedExecutionPercent!!, 0.01)
        assertEquals(8L, report.remainingDays)
        assertEquals(500.0, report.requiredPerRemainingDay!!, 0.001)
    }

    @Test
    fun completedPeriodDoesNotApplyFutureForecast() {
        val report = FlexiblePeriodAnalyticsCalculator.calculate(
            period = period("p", "Прошлый", "2026-01-01", "2026-01-14"),
            categories = listOf(FlexiblePeriodCategoryPlan("Продукты", 100.0)),
            expenses = listOf(
                FlexiblePeriodExpense(1L, "Покупка", "Продукты", 3000.0, LocalDate.parse("2026-01-05"))
            ),
            incomes = emptyList(),
            monthlyIncomeFallback = 90000.0,
            asOf = LocalDate.parse("2026-02-01"),
            forecasts = mapOf("Продукты" to FlexiblePeriodForecast(5000.0, 4000.0, 6000.0, 1000.0))
        )

        assertTrue(report.isCompleted)
        assertEquals(14L, report.elapsedDays)
        assertEquals(0L, report.remainingDays)
        assertEquals(0.0, report.variableForecastTotal, 0.001)
        assertEquals(0.0, report.recurringForecastTotal, 0.001)
        assertEquals(3000.0, report.projectedTotal, 0.001)
        assertNull(report.requiredPerRemainingDay)
    }

    @Test
    fun futurePeriodHasNoElapsedActualDaysAndCanShowForecast() {
        val report = FlexiblePeriodAnalyticsCalculator.calculate(
            period = period("p", "Будущий", "2026-12-01", "2026-12-14"),
            categories = listOf(FlexiblePeriodCategoryPlan("Продукты", 100.0)),
            expenses = emptyList(),
            incomes = emptyList(),
            monthlyIncomeFallback = 90000.0,
            asOf = LocalDate.parse("2026-11-26"),
            forecasts = mapOf("Продукты" to FlexiblePeriodForecast(4000.0, 3000.0, 5000.0, 1000.0))
        )

        assertTrue(report.isFuture)
        assertFalse(report.isCompleted)
        assertEquals(0L, report.elapsedDays)
        assertEquals(14L, report.remainingDays)
        assertEquals(5000.0, report.projectedTotal, 0.001)
        assertNotNull(report.requiredPerRemainingDay)
    }
}
