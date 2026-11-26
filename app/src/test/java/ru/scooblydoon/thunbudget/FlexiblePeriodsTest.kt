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
    fun twoReportingPeriodsAreSummedOnceForTheirCalendarMonth() {
        val total = ReportingPeriodIncomeCalculator.sumForMonth(
            listOf(
                PeriodIncome("2026-10-24", "2026-11-08", 36881.0),
                PeriodIncome("2026-11-09", "2026-11-23", 60854.0),
                PeriodIncome("2026-11-24", "2026-12-08", 99999.0)
            ),
            java.time.YearMonth.of(2026, 11)
        )

        assertEquals(97735.0, total, 0.001)
    }

    @Test
    fun flexibleDateInputParsesTheEightDigitDateStrictly() {
        assertEquals(LocalDate.parse("2026-12-19"), parseFlexibleDate("19122026"))
        assertEquals(LocalDate.parse("2024-02-29"), parseFlexibleDate("29022024"))
        assertNull(parseFlexibleDate("31022026"))
        assertNull(parseFlexibleDate("1912202"))
    }

    @Test
    fun fifteenPlusSixteenDaysIsAllowedWhenOneTouchedMonthHasThirtyOneDays() {
        val first = period("one", "Первая половина", "2026-11-16", "2026-11-30")
        val second = period("two", "Вторая половина", "2026-12-01", "2026-12-16")

        val ordered = FlexiblePeriodRules.add(listOf(first), second)

        assertEquals(31L, FlexiblePeriodRules.totalDays(ordered))
        assertEquals(31L, FlexiblePeriodRules.maximumAllowedDays(first.start, second.end))
        assertEquals("two", FlexiblePeriodRules.periodFor(ordered, LocalDate.parse("2026-12-16"))?.id)
    }

    @Test
    fun thirtyOneSingleDayPeriodsAreAllowedInAThirtyOneDayMonth() {
        val start = LocalDate.parse("2026-01-01")
        val periods = (0 until 31).map { index ->
            val date = start.plusDays(index.toLong())
            FlexiblePeriodDefinition("p$index", "День ${index + 1}", date, date)
        }

        val ordered = FlexiblePeriodRules.validateSequence(periods)

        assertEquals(31, ordered.size)
        assertEquals(31L, FlexiblePeriodRules.totalDays(ordered))
        assertEquals(0L, FlexiblePeriodRules.remainingDays(ordered))
    }

    @Test
    fun fifteenTwoDayPeriodsAreAllowedWhenTheyFitThirtyDays() {
        val start = LocalDate.parse("2026-04-01")
        val periods = (0 until 15).map { index ->
            val periodStart = start.plusDays(index * 2L)
            FlexiblePeriodDefinition(
                "p$index",
                "Период ${index + 1}",
                periodStart,
                periodStart.plusDays(1)
            )
        }

        val ordered = FlexiblePeriodRules.validateSequence(periods)

        assertEquals(15, ordered.size)
        assertEquals(30L, FlexiblePeriodRules.totalDays(ordered))
        assertEquals(0L, FlexiblePeriodRules.remainingDays(ordered))
    }

    @Test(expected = IllegalArgumentException::class)
    fun thirtyTwoDaysAreRejectedEvenWhenTheyTouchTwoMonths() {
        FlexiblePeriodRules.validateSequence(
            listOf(period("one", "Слишком длинный", "2026-02-01", "2026-03-04"))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun sequenceCannotTouchThreeCalendarMonths() {
        FlexiblePeriodRules.validateSequence(
            listOf(period("one", "Три месяца", "2026-01-31", "2026-03-02"))
        )
    }

    @Test
    fun februaryOnlyUsesItsActualCalendarLength() {
        val nonLeap = period("non-leap", "Февраль", "2026-02-01", "2026-02-28")
        val leap = period("leap", "Февраль", "2024-02-01", "2024-02-29")

        assertEquals(28L, FlexiblePeriodRules.totalDays(listOf(nonLeap)))
        assertEquals(29L, FlexiblePeriodRules.totalDays(listOf(leap)))
    }

    @Test
    fun crossingFromFebruaryIntoMarchUsesTheLongerMonthsThirtyOneDayLimit() {
        val februaryAndMarch = period("february-march", "Февраль–март", "2026-02-01", "2026-03-03")
        assertEquals(31L, FlexiblePeriodRules.totalDays(listOf(februaryAndMarch)))
        assertEquals(31L, FlexiblePeriodRules.maximumAllowedDays(februaryAndMarch.start, februaryAndMarch.end))
    }

    @Test
    fun addingPeriodsIsSequentialAndAutomaticallyContinuesAfterTheLastDate() {
        val first = period("one", "Первый", "2026-05-01", "2026-05-10")
        val second = period("two", "Второй", "2026-05-11", "2026-05-20")
        val third = period("three", "Третий", "2026-05-21", "2026-05-30")

        val ordered = FlexiblePeriodRules.add(FlexiblePeriodRules.add(listOf(first), second), third)

        assertEquals(listOf("one", "two", "three"), ordered.map { it.id })
        assertEquals("one", FlexiblePeriodRules.periodFor(ordered, LocalDate.parse("2026-05-01"))?.id)
        assertEquals("two", FlexiblePeriodRules.periodFor(ordered, LocalDate.parse("2026-05-11"))?.id)
        assertEquals("three", FlexiblePeriodRules.periodFor(ordered, LocalDate.parse("2026-05-30"))?.id)
        assertNull(FlexiblePeriodRules.periodFor(ordered, LocalDate.parse("2026-04-30")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun addingAnEarlierPeriodIsRejected() {
        FlexiblePeriodRules.add(
            listOf(period("one", "Первый", "2026-01-10", "2026-01-20")),
            period("before", "До него", "2026-01-01", "2026-01-09")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun addingANonAdjacentPeriodIsRejected() {
        FlexiblePeriodRules.add(
            listOf(period("one", "Первый", "2026-01-01", "2026-01-14")),
            period("two", "Второй", "2026-01-20", "2026-02-02")
        )
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
