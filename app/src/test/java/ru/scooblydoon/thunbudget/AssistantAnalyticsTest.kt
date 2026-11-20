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
        assertEquals(4L, monthly!!.bucketCount)
        assertEquals(862.5, monthly.average, 0.001)
        val category = AssistantAnalytics.average(expenses, SpendingBucket.MONTH, "Продукты")
        assertEquals(833.333333, category!!.average, 0.001)
    }

    @Test fun buildsCategoryTotalsForChart() {
        val totals = AssistantAnalytics.categoryTotals(expenses)
        assertEquals("Продукты", totals.first().first)
        assertEquals(2500.0, totals.first().second, 0.001)
    }
    @Test fun findsCalendarDayAndWeekdayWithHighestSpend() {
        val topDay = AssistantAnalytics.topSpendingDay(expenses)
        assertEquals("2026-02-02", topDay?.first?.toString())
        assertEquals(2500.0, topDay?.second ?: 0.0, 0.001)
        val topWeekday = AssistantAnalytics.topSpendingWeekday(expenses)
        assertEquals(java.time.DayOfWeek.MONDAY, topWeekday?.first)
        assertEquals(2500.0, topWeekday?.second ?: 0.0, 0.001)
    }

    @Test fun ranksCategoriesByTotalAmount() {
        val top = AssistantAnalytics.topCategoryByAmount(expenses)
        assertEquals("Продукты", top?.first)
        assertEquals(2500.0, top?.second ?: 0.0, 0.001)
    }

    @Test fun recommendsCutsWithoutSuggestingCutsToHousing() {
        val rows = listOf(
            Expense(10, "Кафе", "Кафе", 900.0, "2026-01-02"),
            Expense(11, "Продукты", "Продукты", 2000.0, "2026-01-03"),
            Expense(12, "Аренда", "Жильё и ЖКХ", 5000.0, "2026-01-04")
        )
        val recommendations = AssistantAnalytics.savingRecommendations(
            rows,
            mapOf("Кафе" to 500.0, "Продукты" to 2500.0, "Жильё и ЖКХ" to 5000.0),
            remainingBudget = -500.0,
            daysUntilPayday = 5
        )
        assertEquals(listOf("Кафе", "Продукты"), recommendations.map { it.category })
        assertEquals(500.0, recommendations.sumOf { it.suggestedReduction }, 0.001)
    }

}
