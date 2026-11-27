package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

        assertEquals(3, rows.size)
        assertEquals("ИТОГО", rows[2][0])
        assertTrue(rows[2][3].toString().contains("SUM(D2:D2)"))
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

        assertEquals(12 + 2, rows.size)
        assertEquals("ИТОГО", rows.last()[0])
        assertTrue(rows.last()[2].toString().contains("SUM(C2:C13)"))
        assertEquals(12_500.0, rows[1][2] as Double, 0.001)
        assertEquals(7_500.0, rows[2][2] as Double, 0.001)
    }

    @Test
    fun sheetXmlContainsFormulaTotalsFrozenHeaderAndPrintLayout() {
        val rows = XlsxExporter.periods(
            expenses = listOf(Expense(1, "Покупка", "Продукты", 250.0, "2026-11-10")),
            cats = listOf(BudgetCategory("Продукты", 25.0)),
            income = 30_000.0,
            start = LocalDate.parse("2026-11-10"),
            end = LocalDate.parse("2026-11-20"),
            periodIncome = 20_000.0
        )
        val xml = XlsxExporter.sheet(rows)

        assertTrue(xml.contains("<f>SUM(D2:D2)</f>"))
        assertTrue(xml.contains("autoFilter ref=\"A1:G2\""))
        assertTrue(xml.contains("state=\"frozen\""))
        assertTrue(xml.contains("fitToWidth=\"1\""))
    }

    @Test
    fun worksheetAndStylesXmlAreWellFormed() {
        val rows = XlsxExporter.periods(
            expenses = emptyList(),
            cats = listOf(BudgetCategory("Продукты", 25.0)),
            income = 30_000.0,
            start = LocalDate.parse("2026-11-10"),
            end = LocalDate.parse("2026-11-20"),
            periodIncome = 0.0
        )
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        parser.parse(InputSource(StringReader(XlsxExporter.sheet(rows))))
        parser.parse(InputSource(StringReader(XlsxExporter.styles())))
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

    @Test
    fun incomeSheetExportsPrimaryAdditionalAndCarryoverAsSeparateTypes() {
        val rows = XlsxExporter.incomes(
            listOf(
                IncomeEntry(
                    id = "main", title = "Основной доход", amount = 36000.0,
                    date = "2026-11-23", periodStart = "2026-11-09", periodEnd = "2026-11-23"
                ),
                IncomeEntry(
                    id = "bonus", title = "Премия", amount = 5000.0,
                    date = "2026-11-15", periodStart = "2026-11-09", periodEnd = "2026-11-23",
                    isAdditional = true
                ),
                IncomeEntry(
                    id = "carry", title = "Перенос средств", amount = 2000.0,
                    date = "2026-11-24", periodStart = "2026-11-24", periodEnd = "2026-12-08",
                    isCarryover = true
                )
            )
        )

        assertEquals("Дата", rows.first()[0])
        assertEquals("Тип дохода", rows.first()[2])
        val entriesByType = rows.drop(1).dropLast(1).associateBy { it[2].toString() }
        assertEquals(setOf("Основной доход", "Дополнительный доход", "Перенос средств"), entriesByType.keys)
        assertEquals(36000.0, entriesByType["Основной доход"]!![3] as Double, 0.001)
        assertEquals(5000.0, entriesByType["Дополнительный доход"]!![3] as Double, 0.001)
        assertEquals(2000.0, entriesByType["Перенос средств"]!![3] as Double, 0.001)
        assertEquals("ИТОГО", rows.last()[0])
        assertTrue(rows.last()[3].toString().contains("SUM(D2:D4)"))
    }


    @Test
    fun monthlyExcelKeepsSavingsOutflowsAndExcludesInternalPeriodCarryover() {
        val expenses = listOf(
            Expense(1, "Покупка", "Продукты", 1000.0, "2026-11-10"),
            Expense(2, "На накопительный счёт", "Накопления", 10000.0, "2026-11-11"),
            Expense(3, "В резерв", "Резервные средства", 3000.0, "2026-11-12"),
            Expense(4, "Перенос остатка", "Перенос средств", 5000.0, "2026-11-23"),
            Expense(5, "Будущая покупка", "Продукты", 800.0, "2026-11-28")
        )
        val rows = XlsxExporter.months(
            expenses = expenses,
            cats = listOf(
                BudgetCategory("Продукты", 20.0),
                BudgetCategory("Накопления", 10.0),
                BudgetCategory("Резервные средства", 10.0),
                BudgetCategory("Перенос средств", 0.0)
            ),
            income = 100000.0,
            year = 2026,
            monthlyIncomes = mapOf("2026-11" to 100000.0),
            asOf = LocalDate.parse("2026-11-27"),
            categoryPlans = mapOf(
                "2026-11" to mapOf(
                    "Продукты" to 15613.0,
                    "Накопления" to 609.0,
                    "Резервные средства" to 1721.0
                )
            )
        )
        val november = rows.drop(1).dropLast(1)
            .filter { it[0] == "2026-11" }
            .associateBy { it[1].toString() }

        assertEquals(setOf("Продукты", "Накопления", "Резервные средства"), november.keys)
        assertEquals(15613.0, november["Продукты"]!![2] as Double, 0.001)
        assertEquals(1000.0, november["Продукты"]!![3] as Double, 0.001)
        assertEquals(10000.0, november["Накопления"]!![3] as Double, 0.001)
        assertEquals(3000.0, november["Резервные средства"]!![3] as Double, 0.001)
        assertEquals(14000.0, november.values.sumOf { it[3] as Double }, 0.001)
    }


    @Test
    fun monthlyExcelUsesFullReportingCycleAndIncludesTheEighthOfNextMonth() {
        val rows = XlsxExporter.months(
            expenses = listOf(
                Expense(1, "До начала цикла", "Продукты", 90.0, "2026-11-08"),
                Expense(2, "Начало цикла", "Продукты", 100.0, "2026-11-09"),
                Expense(3, "Второй период", "Продукты", 200.0, "2026-12-08"),
                Expense(4, "После цикла", "Продукты", 300.0, "2026-12-09")
            ),
            cats = listOf(BudgetCategory("Продукты", 100.0)),
            income = 1000.0,
            year = 2026,
            monthlyIncomes = mapOf("2026-11" to 1000.0),
            asOf = LocalDate.parse("2026-12-09")
        )
        val november = rows.drop(1).dropLast(1)
            .first { it[0] == "2026-11" && it[1] == "Продукты" }

        assertEquals(300.0, november[3] as Double, 0.001)
    }

}
