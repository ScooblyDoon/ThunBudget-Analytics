package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth

class AnalyticsBudgetTest {
    @Test fun monthlyIncomeCanUseFixedMonthlySetting() {
        val result = AnalyticsBudgetCalculator.monthlyIncome(
            120000.0,
            listOf(PeriodIncome("2026-10-24", "2026-11-08", 30000.0), PeriodIncome("2026-11-09", "2026-11-23", 40000.0)),
            YearMonth.of(2026, 11),
            false
        )
        assertEquals(120000.0, result, 0.001)
    }

    @Test fun monthlyIncomeAllocatesPeriodIncomeAcrossCalendarMonthBoundaries() {
        val result = AnalyticsBudgetCalculator.monthlyIncome(
            120000.0,
            listOf(
                PeriodIncome("2026-10-24", "2026-11-08", 30000.0),
                PeriodIncome("2026-11-09", "2026-11-23", 40000.0),
                PeriodIncome("2026-11-24", "2026-12-08", 50000.0)
            ),
            YearMonth.of(2026, 11),
            true
        )
        assertEquals(78333.333333, result, 0.001)
    }


    @Test fun carryoverIsIncludedInPeriodTotalButExcludedFromEarnedMonthlyIncome() {
        val period = PeriodIncome(
            start = "2026-11-09",
            end = "2026-11-23",
            amount = 70000.0,
            earnedAmount = 50000.0,
            carryoverAmount = 20000.0
        )
        assertEquals(70000.0, period.amount, 0.001)
        assertEquals(
            50000.0,
            AnalyticsBudgetCalculator.monthlyIncome(
                120000.0,
                listOf(period),
                YearMonth.of(2026, 11),
                true
            ),
            0.001
        )
    }


    @Test fun monthlyIncomeSumsTwoReportingPeriodValuesWithoutDailyExtrapolation() {
        val result = AnalyticsBudgetCalculator.monthlyIncome(
            120000.0,
            listOf(
                PeriodIncome("2026-10-24", "2026-11-08", 60854.0),
                PeriodIncome("2026-11-09", "2026-11-23", 36881.0),
                PeriodIncome("2026-11-24", "2026-12-08", 50000.0)
            ),
            YearMonth.of(2026, 11),
            true
        )
        assertEquals(90641.333333, result, 0.001)
    }

    @Test fun calendarMonthUsesOnlyCoveredDaysAndSplitsPeriodsByDay() {
        val periods = listOf(
            PeriodIncome("2026-11-09", "2026-11-24", 16000.0),
            PeriodIncome("2026-11-25", "2026-12-08", 14000.0),
            PeriodIncome("2026-12-09", "2026-12-24", 16000.0),
            PeriodIncome("2026-12-25", "2027-01-08", 15000.0)
        )
        val november = AnalyticsBudgetCalculator.calendarMonthIncome(
            periods, YearMonth.of(2026, 11), java.time.LocalDate.of(2026, 11, 30)
        )
        assertEquals(22000.0, november.enteredIncome, 0.001)
        assertEquals(22, november.coveredDays)
        assertEquals(8, november.uncoveredPastDays)
        assertEquals(0.0, november.forecastIncome, 0.001)

        val december = AnalyticsBudgetCalculator.calendarMonthIncome(
            periods, YearMonth.of(2026, 12), java.time.LocalDate.of(2027, 1, 31)
        )
        assertEquals(31000.0, december.enteredIncome, 0.001)
        assertEquals(31, december.coveredDays)
    }

    @Test fun reportingCycleIncomeAddsTwoFullPeriodsAndExcludesCarryover() {
        val result = AnalyticsBudgetCalculator.reportingCycleIncome(
            listOf(
                PeriodIncome("2026-11-09", "2026-11-24", 18000.0, earnedAmount = 16000.0, carryoverAmount = 2000.0),
                PeriodIncome("2026-11-25", "2026-12-08", 14000.0)
            ),
            YearMonth.of(2026, 11),
            9,
            24
        )
        assertEquals(16000.0, result.firstIncome, 0.001)
        assertEquals(14000.0, result.secondIncome, 0.001)
        assertEquals(30000.0, result.totalIncome, 0.001)
    }

    @Test fun calendarForecastFillsOnlyUncoveredFutureDaysUsingMedianOfCompletedPeriods() {
        val periods = listOf(
            PeriodIncome("2026-10-01", "2026-10-15", 15000.0),
            PeriodIncome("2026-10-16", "2026-10-31", 16000.0),
            PeriodIncome("2026-11-09", "2026-11-24", 16000.0)
        )
        val result = AnalyticsBudgetCalculator.calendarMonthIncome(
            periods, YearMonth.of(2026, 11), java.time.LocalDate.of(2026, 11, 26)
        )
        assertEquals(16000.0, result.enteredIncome, 0.001)
        assertEquals(4, result.forecastDays)
        assertEquals(4000.0, result.forecastIncome, 0.001)
        assertEquals(10, result.uncoveredPastDays)
        assertEquals(4, result.uncoveredFutureDays)
        assertEquals(20000.0, result.totalIncome, 0.001)
    }

    @Test fun calendarMonthNeverForecastsMissingDaysInThePast() {
        val periods = listOf(
            PeriodIncome("2026-10-01", "2026-10-15", 15000.0),
            PeriodIncome("2026-10-16", "2026-10-31", 16000.0),
            PeriodIncome("2026-11-09", "2026-11-24", 16000.0)
        )
        val result = AnalyticsBudgetCalculator.calendarMonthIncome(
            periods, YearMonth.of(2026, 11), java.time.LocalDate.of(2026, 11, 30)
        )
        assertEquals(16000.0, result.enteredIncome, 0.001)
        assertEquals(0.0, result.forecastIncome, 0.001)
        assertEquals(14, result.uncoveredPastDays)
    }

    @Test fun automaticCategoryAllocationAveragesFirstAndCurrentReportingPeriods() {
        val result = ReportingCycleCategoryAllocationCalculator.calculate(
            currentShares = listOf(
                "Продукты" to 20.0,
                "Транспорт" to 30.0,
                "Непредвиденные расходы" to 10.0,
                "Резервные средства" to 40.0
            ),
            periodIncomes = listOf(
                PeriodIncome("2026-11-09", "2026-11-23", 100000.0),
                PeriodIncome("2026-11-24", "2026-12-08", 100000.0)
            ),
            expenses = listOf(
                Expense(1L, "Продукты 1", "Продукты", 20000.0, "2026-11-15"),
                Expense(2L, "Продукты 2", "Продукты", 26000.0, "2026-12-02"),
                Expense(3L, "Транспорт 1", "Транспорт", 30000.0, "2026-11-18"),
                Expense(4L, "Транспорт 2", "Транспорт", 20000.0, "2026-12-03")
            ),
            month = YearMonth.of(2026, 11),
            firstStartDay = 9,
            firstEndDay = 23,
            asOf = java.time.LocalDate.of(2026, 12, 8)
        )

        assertEquals(2, result.periodsUsed)
        assertEquals(23.0, result.percentages["Продукты"]!!, 0.001)
        assertEquals(25.0, result.percentages["Транспорт"]!!, 0.001)
        assertEquals(20.0, result.periodPercentages["Продукты"]!![0], 0.001)
        assertEquals(26.0, result.periodPercentages["Продукты"]!![1], 0.001)
        assertEquals(10.0, result.percentages["Непредвиденные расходы"]!!, 0.001)
        assertEquals(100.0, result.percentages.values.sum(), 0.001)
    }

    @Test fun automaticCategoryAllocationIgnoresFutureDatedExpensesInCurrentPeriod() {
        val result = ReportingCycleCategoryAllocationCalculator.calculate(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            periodIncomes = listOf(
                PeriodIncome("2026-11-09", "2026-11-23", 100000.0),
                PeriodIncome("2026-11-24", "2026-12-08", 100000.0)
            ),
            expenses = listOf(
                Expense(1L, "Продукты 1", "Продукты", 20000.0, "2026-11-15"),
                Expense(2L, "Продукты 2", "Продукты", 18000.0, "2026-12-02"),
                Expense(3L, "Будущие продукты", "Продукты", 8000.0, "2026-12-08")
            ),
            month = YearMonth.of(2026, 11),
            firstStartDay = 9,
            firstEndDay = 23,
            asOf = java.time.LocalDate.of(2026, 12, 4)
        )

        assertEquals(2, result.periodsUsed)
        assertEquals(19.0, result.percentages["Продукты"]!!, 0.001)
        assertEquals(18.0, result.periodPercentages["Продукты"]!![1], 0.001)
    }

    @Test fun automaticCategoryAllocationUsesOnlyPeriodsWithRecordedEarnedIncome() {
        val result = ReportingCycleCategoryAllocationCalculator.calculate(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            periodIncomes = listOf(
                PeriodIncome("2026-11-09", "2026-11-23", 100000.0),
                PeriodIncome("2026-11-24", "2026-12-08", 0.0)
            ),
            expenses = listOf(
                Expense(1L, "Продукты", "Продукты", 20000.0, "2026-11-15"),
                Expense(2L, "Продукты", "Продукты", 26000.0, "2026-12-02")
            ),
            month = YearMonth.of(2026, 11),
            firstStartDay = 9,
            firstEndDay = 23,
            asOf = java.time.LocalDate.of(2026, 12, 8)
        )

        assertEquals(1, result.periodsUsed)
        assertEquals(20.0, result.percentages["Продукты"]!!, 0.001)
    }

    @Test fun automaticCategoryAllocationKeepsManualSharesWithoutIncomeHistory() {
        val result = ReportingCycleCategoryAllocationCalculator.calculate(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            periodIncomes = emptyList(),
            expenses = emptyList(),
            month = YearMonth.of(2026, 11),
            firstStartDay = 9,
            firstEndDay = 23,
            asOf = java.time.LocalDate.of(2026, 11, 26)
        )

        assertEquals(0, result.periodsUsed)
        assertEquals(20.0, result.percentages["Продукты"]!!, 0.001)
        assertEquals(80.0, result.percentages["Резервные средства"]!!, 0.001)
    }

    @Test fun carryoverBeforeAddsUnspentBalanceFromCompletedPeriods() {
        val result = AnalyticsBudgetCalculator.carryoverBefore(
            "2026-11-24",
            listOf(PeriodIncome("2026-11-09", "2026-11-23", 100000.0)),
            listOf(Expense(1L, "Покупки", "Продукты", 70000.0, "2026-11-20"))
        )
        assertEquals(30000.0, result, 0.001)
    }

    @Test fun carryoverSubtractsExpensesFromPeriodsWithoutIncomeEntries() {
        val result = AnalyticsBudgetCalculator.carryoverBefore(
            "2026-12-09",
            listOf(PeriodIncome("2026-11-09", "2026-11-23", 100000.0)),
            listOf(
                Expense(1L, "Покупки", "Продукты", 20000.0, "2026-11-20"),
                Expense(2L, "Покупки", "Продукты", 30000.0, "2026-12-02")
            )
        )
        assertEquals(50000.0, result, 0.001)
    }

    @Test fun explicitTransferExpenseConsumesPriorPeriodCarryover() {
        val result = AnalyticsBudgetCalculator.carryoverBefore(
            "2026-11-24",
            listOf(PeriodIncome("2026-11-09", "2026-11-23", 100000.0)),
            listOf(
                Expense(1L, "Покупки", "Продукты", 70000.0, "2026-11-20"),
                Expense(2L, "Перенос остатка", "Перенос средств", 30000.0, "2026-11-23")
            )
        )
        assertEquals(0.0, result, 0.001)
    }

    @Test fun metricsShowFreePercentAndRequiredIncrease() {
        val free = AnalyticsBudgetCalculator.metrics(10000.0, 7500.0)
        assertEquals(25.0, free.remainingPercent!!, 0.001)
        assertEquals(0.0, free.requiredIncreasePercent!!, 0.001)
        val exceeded = AnalyticsBudgetCalculator.metrics(10000.0, 12500.0)
        assertEquals(-25.0, exceeded.remainingPercent!!, 0.001)
        assertEquals(25.0, exceeded.requiredIncreasePercent!!, 0.001)
        assertNull(AnalyticsBudgetCalculator.metrics(0.0, 500.0).remainingPercent)
    }

    @Test fun dynamicProposalTracksActualSpendAndKeepsNoSpendShares() {
        val proposal = AnalyticsBudgetCalculator.dynamicProposal(
            listOf("Продукты" to 50.0, "Транспорт" to 30.0, "Развлечения" to 20.0),
            mapOf("Продукты" to 6000.0, "Транспорт" to 4000.0, "Развлечения" to 0.0)
        )
        assertEquals(60.0, proposal.percentages["Продукты"]!!, 0.001)
        assertEquals(40.0, proposal.percentages["Транспорт"]!!, 0.001)
        assertEquals(0.0, proposal.percentages["Развлечения"]!!, 0.001)
        assertEquals(100.0, proposal.percentages.values.sum(), 0.001)
    }
}
