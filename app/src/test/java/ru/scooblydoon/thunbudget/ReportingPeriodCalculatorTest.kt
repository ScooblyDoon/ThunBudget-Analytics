package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ReportingPeriodCalculatorTest {
    @Test fun standardNineToTwentyFourContinuesIntoNextMonth() {
        val result = ReportingPeriodCalculator.propose(YearMonth.of(2026, 11), 9, 24)
        assertEquals(LocalDate.parse("2026-11-09"), result.first.start)
        assertEquals(LocalDate.parse("2026-11-24"), result.first.end)
        assertEquals(LocalDate.parse("2026-11-25"), result.second.start)
        assertEquals(LocalDate.parse("2026-12-08"), result.second.end)
    }

    @Test fun firstToTwelfthUsesThirtyOneDayMonthLength() {
        val result = ReportingPeriodCalculator.propose(YearMonth.of(2026, 1), 1, 12)
        assertEquals(LocalDate.parse("2026-01-13"), result.second.start)
        assertEquals(LocalDate.parse("2026-01-31"), result.second.end)
    }

    @Test fun firstToTwelfthUsesThirtyDayMonthLength() {
        val result = ReportingPeriodCalculator.propose(YearMonth.of(2026, 4), 1, 12)
        assertEquals(LocalDate.parse("2026-04-30"), result.second.end)
    }

    @Test fun februaryUsesTwentyEightOrTwentyNineDays() {
        val common = ReportingPeriodCalculator.propose(YearMonth.of(2025, 2), 1, 12)
        val leap = ReportingPeriodCalculator.propose(YearMonth.of(2024, 2), 1, 12)
        assertEquals(LocalDate.parse("2025-02-28"), common.second.end)
        assertEquals(LocalDate.parse("2024-02-29"), leap.second.end)
    }

    @Test fun secondPeriodEndsBeforeNextFirstPeriod() {
        val result = ReportingPeriodCalculator.propose(YearMonth.of(2026, 12), 9, 24)
        assertEquals(LocalDate.parse("2027-01-08"), result.second.end)
        assertEquals(result.first.end.plusDays(1), result.second.start)
        assertEquals(result.second.end.plusDays(1), LocalDate.parse("2027-01-09"))
    }

    @Test
    fun periodForDateUsesConfiguredBoundariesIncludingPreviousMonthTail() {
        val first = ReportingPeriodCalculator.periodFor(
            LocalDate.parse("2026-11-15"), 9, 23
        )
        val second = ReportingPeriodCalculator.periodFor(
            LocalDate.parse("2026-11-27"), 9, 23
        )
        val previousTail = ReportingPeriodCalculator.periodFor(
            LocalDate.parse("2026-11-03"), 9, 23
        )

        assertEquals(LocalDate.parse("2026-11-09"), first.start)
        assertEquals(LocalDate.parse("2026-11-23"), first.end)
        assertEquals(LocalDate.parse("2026-11-24"), second.start)
        assertEquals(LocalDate.parse("2026-12-08"), second.end)
        assertEquals(LocalDate.parse("2026-10-24"), previousTail.start)
        assertEquals(LocalDate.parse("2026-11-08"), previousTail.end)
    }

    @Test
    fun periodForDateHonorsNonDefaultReportingDays() {
        val early = ReportingPeriodCalculator.periodFor(
            LocalDate.parse("2026-05-02"), 5, 18
        )
        val late = ReportingPeriodCalculator.periodFor(
            LocalDate.parse("2026-05-20"), 5, 18
        )

        assertEquals(LocalDate.parse("2026-04-19"), early.start)
        assertEquals(LocalDate.parse("2026-05-04"), early.end)
        assertEquals(LocalDate.parse("2026-05-19"), late.start)
        assertEquals(LocalDate.parse("2026-06-04"), late.end)
    }

}
