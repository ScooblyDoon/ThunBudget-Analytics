package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantMemoryRulesTest {
    @Test
    fun remembersSelectedPeriodAndCategoryWithoutRawFinancialData() {
        val result = AssistantMemoryRules.remember(
            current = AssistantMemoryState(),
            intent = "query_flexible_period_report",
            periodId = "holiday-1",
            periodName = "Отпуск",
            periodStart = "2026-07-01",
            periodEnd = "2026-07-14",
            category = "Транспорт",
            timestamp = "2026-11-27T10:00:00Z"
        )

        assertEquals("holiday-1", result.lastPeriodId)
        assertEquals("Отпуск", result.lastPeriodName)
        assertEquals("2026-07-01", result.lastPeriodStart)
        assertEquals("2026-07-14", result.lastPeriodEnd)
        assertEquals("Транспорт", result.lastCategory)
        assertEquals(1, result.turnCount)
    }

    @Test
    fun keepsPreviousPeriodWhenNextQueryDoesNotSpecifyOne() {
        val first = AssistantMemoryRules.remember(
            AssistantMemoryState(),
            intent = "query_flexible_period_report",
            periodId = "p1",
            periodName = "Отпуск",
            periodStart = "2026-07-01",
            periodEnd = "2026-07-14",
            timestamp = "2026-11-27T10:00:00Z"
        )
        val second = AssistantMemoryRules.remember(
            first,
            intent = "query_flexible_period_income",
            category = null,
            timestamp = "2026-11-27T10:01:00Z"
        )

        assertEquals("p1", second.lastPeriodId)
        assertEquals("Отпуск", second.lastPeriodName)
        assertEquals(2, second.turnCount)
    }

    @Test
    fun ignoresInvalidDateRangeAndDoesNotResolveDeletedPeriod() {
        val state = AssistantMemoryRules.remember(
            AssistantMemoryState(),
            intent = "query_flexible_period_report",
            periodId = "removed",
            periodName = "Старый период",
            periodStart = "2026-02-30",
            periodEnd = "2026-03-01",
            timestamp = "2026-11-27T10:00:00Z"
        )

        assertNull(state.lastPeriodStart)
        assertNull(state.lastPeriodEnd)
        assertNull(AssistantMemoryRules.resolvePeriodId(state, setOf("current")))
    }

    @Test
    fun clearReturnsEmptyState() {
        assertEquals(AssistantMemoryState(), AssistantMemoryRules.clear())
    }
}
