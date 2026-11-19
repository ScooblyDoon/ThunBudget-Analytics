package ru.scooblydoon.thunbudget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XlsxExporterTest {
    @Test
    fun reportUsesExactlyTheConfiguredStartAndEndDates() {
        val expenses = listOf(
            Expense(1, "До периода", "Продукты", 900.0, "2026-11-09"),
            Expense(2, "Начало периода", "Продукты", 100.0, "2026-11-10"),
            Expense(3, "Внутри периода", "Продукты", 300.0, "2026-11-15"),
            Expense(4, "Конец периода", "Продукты", 200.0, "2026-11-20"),
            Expense(5, "После периода", "Продукты", 700.0, "2026-11-21")
        )
        val rows = XlsxExporter.periods(
            expenses = expenses,
            cats = listOf(BudgetCategory("Продукты", 25.0)),
            income = 50_000.0,
            start = LocalDate.parse("2026-11-10"),
            end = LocalDate.parse("2026-11-20"),
            periodIncome = 20_000.0
        )

        assertEquals(2, rows.size)
        assertEquals("2026-11-10", rows[1][0])
        assertEquals("2026-11-20", rows[1][1])
        assertEquals(5_000.0, rows[1][3] as Double, 0.001)
        assertEquals(600.0, rows[1][4] as Double, 0.001)
        assertEquals(4_400.0, rows[1][5] as Double, 0.001)
        assertEquals(0.0, rows[1][6] as Double, 0.001)
    }

    @Test
    fun reportProratesMonthlyIncomeAcrossTheConfiguredDatesWhenPeriodIncomeIsAbsent() {
        val rows = XlsxExporter.periods(
            expenses = emptyList(),
            cats = listOf(BudgetCategory("Продукты", 50.0)),
            income = 30_000.0,
            start = LocalDate.parse("2026-02-27"),
            end = LocalDate.parse("2026-03-02"),
            periodIncome = 0.0
        )

        val expected = 15_000.0 / 28.0 * 2 + 15_000.0 / 31.0 * 2
        assertEquals(expected, rows[1][3] as Double, 0.001)
    }

    @Test
    fun monthlyReportUsesRecordedIncomeAndFallsBackToConfiguredMonthlyIncome() {
        val rows = XlsxExporter.months(
            expenses = emptyList(),
            cats = listOf(BudgetCategory("Продукты", 25.0)),
            income = 30_000.0,
            year = 2026,
            monthlyIncomes = mapOf("2026-01" to 50_000.0)
        )

        assertEquals(12 + 1, rows.size)
        assertEquals(12_500.0, rows[1][2] as Double, 0.001)
        assertEquals(7_500.0, rows[2][2] as Double, 0.001)
    }

    @Test
    fun reportRejectsEndDateBeforeStartDate() {
        assertThrows(IllegalArgumentException::class.java) {
            XlsxExporter.periods(
                expenses = emptyList(),
                cats = emptyList(),
                income = 30_000.0,
                start = LocalDate.parse("2026-11-20"),
                end = LocalDate.parse("2026-11-10"),
                periodIncome = 0.0
            )
        }
    }
}
