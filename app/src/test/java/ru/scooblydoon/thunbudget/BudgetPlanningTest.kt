package ru.scooblydoon.thunbudget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetPlanningTest {
    @Test
    fun dividesRemainingBudgetAcrossTodayAndEndDateInclusively() {
        val result = calculateDailyLimit(
            remainingBudget = 900.0,
            periodStart = LocalDate.parse("2026-11-15"),
            periodEnd = LocalDate.parse("2026-11-17"),
            today = LocalDate.parse("2026-11-15")
        )
        assertEquals(3L, result.daysRemaining)
        assertEquals(300.0, result.amountPerDay, 0.001)
    }

    @Test
    fun startsCountingFromTodayWhenPeriodAlreadyStarted() {
        val result = calculateDailyLimit(
            remainingBudget = 800.0,
            periodStart = LocalDate.parse("2026-11-01"),
            periodEnd = LocalDate.parse("2026-11-17"),
            today = LocalDate.parse("2026-11-15")
        )
        assertEquals(3L, result.daysRemaining)
        assertEquals(800.0 / 3.0, result.amountPerDay, 0.001)
    }

    @Test
    fun returnsZeroWhenPeriodHasEnded() {
        val result = calculateDailyLimit(
            remainingBudget = 500.0,
            periodStart = LocalDate.parse("2026-11-01"),
            periodEnd = LocalDate.parse("2026-11-14"),
            today = LocalDate.parse("2026-11-15")
        )
        assertEquals(0L, result.daysRemaining)
        assertEquals(0.0, result.amountPerDay, 0.001)
    }

    @Test
    fun doesNotShowNegativeDailyAllowanceWhenBudgetIsExceeded() {
        val result = calculateDailyLimit(
            remainingBudget = -120.0,
            periodStart = LocalDate.parse("2026-11-15"),
            periodEnd = LocalDate.parse("2026-11-17"),
            today = LocalDate.parse("2026-11-15")
        )
        assertEquals(0.0, result.amountPerDay, 0.001)
    }
}
