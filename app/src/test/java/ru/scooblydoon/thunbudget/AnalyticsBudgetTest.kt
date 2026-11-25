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

    @Test fun monthlyIncomeCanSumBothReportingPeriodsByEndMonth() {
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
        assertEquals(70000.0 / 31.0 * 30.0, result, 0.001)
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
            50000.0 / 15.0 * 30.0,
            AnalyticsBudgetCalculator.monthlyIncome(
                120000.0,
                listOf(period),
                YearMonth.of(2026, 11),
                true
            ),
            0.001
        )
    }

    @Test fun carryoverBeforeAddsUnspentBalanceFromCompletedPeriods() {
        val result = AnalyticsBudgetCalculator.carryoverBefore(
            "2026-11-24",
            listOf(PeriodIncome("2026-11-09", "2026-11-23", 100000.0)),
            listOf(Expense(1L, "Покупки", "Продукты", 70000.0, "2026-11-20"))
        )
        assertEquals(30000.0, result, 0.001)
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
