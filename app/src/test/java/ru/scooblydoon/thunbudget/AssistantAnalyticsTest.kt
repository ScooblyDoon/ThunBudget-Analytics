package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AssistantAnalyticsTest {
    private val expenses = listOf(
        Expense(1, "Кофе", "Кафе", 200.0, "2026-01-02"),
        Expense(2, "Кофе", "Кафе", 250.0, "2026-01-03"),
        Expense(3, "Продукты", "Продукты", 1000.0, "2026-02-02"),
        Expense(4, "Продукты", "Продукты", 1500.0, "2026-04-02"),
        Expense(5, "Такси", "Транспорт", 500.0, "2026-04-05")
    )

    @Test fun findsMostFrequentCategoryAndTitle() {
        assertEquals("Кафе", AssistantAnalytics.topCategoryByFrequency(expenses)?.first)
        assertEquals(2, AssistantAnalytics.topTitleByFrequency(expenses)?.second)
    }

    @Test fun findsLargestAndSmallestExpense() {
        assertEquals(1500.0, AssistantAnalytics.largest(expenses)?.amount ?: 0.0, 0.001)
        assertEquals(200.0, AssistantAnalytics.smallest(expenses)?.amount ?: 0.0, 0.001)
    }

    @Test fun calculatesMonthlyAndCategoryAverageAcrossCalendarMonths() {
        val monthly = AssistantAnalytics.average(expenses, SpendingBucket.MONTH)
        assertNotNull(monthly)
        assertEquals(3L, monthly!!.bucketCount)
        assertEquals(1150.0, monthly.average, 0.001)
        val category = AssistantAnalytics.average(expenses, SpendingBucket.MONTH, "Продукты")
        assertEquals(1250.0, category!!.average, 0.001)
    }

    @Test fun buildsCategoryTotalsForChart() {
        val totals = AssistantAnalytics.categoryTotals(expenses)
        assertEquals("Продукты", totals.first().first)
        assertEquals(2500.0, totals.first().second, 0.001)
    }
}
