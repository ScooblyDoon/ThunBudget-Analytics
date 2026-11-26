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

    @Test fun overviewAndAnalyticsUseTheSameSavedPlanForAnExactReportingPeriod() {
        val start = java.time.LocalDate.parse("2026-11-24")
        val end = java.time.LocalDate.parse("2026-12-08")
        val currentShares = listOf(
            "Продукты" to 20.0,
            "Транспорт" to 13.0,
            "Резервные средства" to 67.0
        )
        val savedPlans = mapOf(
            ("2026-11-24" to "2026-12-08") to mapOf("тРАНСпОРТ" to 7.0)
        )
    
        val exactPeriodPlan = ReportingPeriodCategoryPlanCalculator.periodPlan(
            currentShares = currentShares,
            savedPlans = savedPlans,
            start = start,
            end = end
        )
    
        // The saved snapshot wins for this period; categories without a
        // snapshot use the supplied baseline shares.
        assertEquals(7.0, exactPeriodPlan["Транспорт"]!!, 0.001)
        assertEquals(20.0, exactPeriodPlan["Продукты"]!!, 0.001)
    
        val overviewBudget = AnalyticsBudgetCalculator.periodCategoryBudget(
            periodIncome = 36885.0,
            monthlyIncomeFallback = 40000.0,
            categoryPercent = exactPeriodPlan["Транспорт"]!!,
            start = start,
            end = end
        )
        val analyticsBudget = AnalyticsBudgetCalculator.periodCategoryBudget(
            periodIncome = 36885.0,
            monthlyIncomeFallback = 40000.0,
            categoryPercent = exactPeriodPlan["Транспорт"]!!,
            start = start,
            end = end
        )
    
        assertEquals(2581.95, overviewBudget, 0.001)
        assertEquals(overviewBudget, analyticsBudget, 0.001)
        assertEquals(-32.9, AnalyticsBudgetCalculator.metrics(overviewBudget, 3432.0).remainingPercent!!, 0.1)
    }
    
        val result = ReportingPeriodCategoryPlanCalculator.cyclePlan(
            currentShares = listOf(
                "Продукты" to 20.0,
                "Транспорт" to 20.0,
                "Резервные средства" to 60.0
            ),
            savedPlans = mapOf(
                ("2026-11-09" to "2026-11-23") to mapOf(
                    "Продукты" to 20.0, "Транспорт" to 10.0, "Резервные средства" to 70.0
                ),
                ("2026-11-24" to "2026-12-08") to mapOf(
                    "Продукты" to 20.0, "Транспорт" to 30.0, "Резервные средства" to 50.0
                )
            ),
            firstStart = java.time.LocalDate.parse("2026-11-09"),
            firstEnd = java.time.LocalDate.parse("2026-11-23"),
            secondStart = java.time.LocalDate.parse("2026-11-24"),
            secondEnd = java.time.LocalDate.parse("2026-12-08"),
            firstIncome = 60854.0,
            secondIncome = 36881.0
        )

        assertEquals(20.0, result.averagePercentages["Продукты"]!!, 0.001)
        val totalCycleIncome = 60854.0 + 36881.0
        assertEquals(
            (60854.0 * 0.10 + 36881.0 * 0.30) / totalCycleIncome * 100.0,
            result.averagePercentages["Транспорт"]!!,
            0.001
        )
        assertEquals(
            (60854.0 * 0.70 + 36881.0 * 0.50) / totalCycleIncome * 100.0,
            result.averagePercentages["Резервные средства"]!!,
            0.001
        )
        assertEquals(10.0, result.firstPeriodPercentages["Транспорт"]!!, 0.001)
        assertEquals(30.0, result.secondPeriodPercentages["Транспорт"]!!, 0.001)

        val twoPeriodIncome = 60854.0 + 36881.0
        assertEquals(19547.0, twoPeriodIncome * result.averagePercentages["Продукты"]!! / 100.0, 0.001)
    }

    @Test fun cyclePlanWeightsDifferentPeriodPlansByTheirIncomeAmounts() {
        val result = ReportingPeriodCategoryPlanCalculator.cyclePlan(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            savedPlans = mapOf(
                ("2026-11-09" to "2026-11-23") to mapOf("Продукты" to 15.0, "Резервные средства" to 85.0),
                ("2026-11-24" to "2026-12-08") to mapOf("Продукты" to 25.0, "Резервные средства" to 75.0)
            ),
            firstStart = java.time.LocalDate.parse("2026-11-09"),
            firstEnd = java.time.LocalDate.parse("2026-11-23"),
            secondStart = java.time.LocalDate.parse("2026-11-24"),
            secondEnd = java.time.LocalDate.parse("2026-12-08"),
            firstIncome = 60000.0,
            secondIncome = 40000.0
        )

        val expectedPlannedAmount = 60000.0 * 0.15 + 40000.0 * 0.25
        val actualPlan = 100000.0 * result.averagePercentages["Продукты"]!! / 100.0
        assertEquals(19.0, result.averagePercentages["Продукты"]!!, 0.001)
        assertEquals(expectedPlannedAmount, actualPlan, 0.001)
    }

    @Test fun cyclePlanFallsBackToCurrentPlanWhenAnOlderSnapshotIsUnavailable() {
        val result = ReportingPeriodCategoryPlanCalculator.cyclePlan(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            savedPlans = mapOf(
                ("2026-11-24" to "2026-12-08") to mapOf("Продукты" to 20.0, "Резервные средства" to 80.0)
            ),
            firstStart = java.time.LocalDate.parse("2026-11-09"),
            firstEnd = java.time.LocalDate.parse("2026-11-23"),
            secondStart = java.time.LocalDate.parse("2026-11-24"),
            secondEnd = java.time.LocalDate.parse("2026-12-08"),
            firstIncome = 60854.0,
            secondIncome = 36881.0
        )

        assertEquals(20.0, result.averagePercentages["Продукты"]!!, 0.001)
        assertEquals(80.0, result.averagePercentages["Резервные средства"]!!, 0.001)
    }

    @Test fun calendarMonthIncomeUsesExactInclusiveReportingDayOverlap() {
        val periods = listOf(
            PeriodIncome("2026-11-01", "2026-11-30", 100000.0), // obsolete overlapping monthly aggregate
            PeriodIncome("2026-11-09", "2026-11-23", 60854.0),
            PeriodIncome("2026-11-24", "2026-12-08", 36881.0)
        )
        val result = AnalyticsBudgetCalculator.calendarMonthIncome(
            periods, YearMonth.of(2026, 11), java.time.LocalDate.of(2026, 11, 26)
        )

        assertEquals(78065.133333, result.totalIncome, 0.001)
        assertEquals(22, result.coveredDays)
        assertEquals(8, result.uncoveredPastDays)
        assertEquals(0, result.uncoveredFutureDays)
    }

    @Test fun monthlyCategoryPlanSplitsIncomeAndUsesEachOverlappingPeriodPlan() {
        val plans = mapOf(
            ("2026-11-09" to "2026-11-23") to mapOf("Продукты" to 20.0),
            ("2026-11-24" to "2026-12-08") to mapOf("Продукты" to 24.0)
        )
        val result = ReportingPeriodCategoryPlanCalculator.monthlyPlan(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            periodIncomes = listOf(
                PeriodIncome("2026-11-01", "2026-11-30", 100000.0), // obsolete monthly aggregate
                PeriodIncome("2026-11-09", "2026-11-23", 60854.0),
                PeriodIncome("2026-11-24", "2026-12-08", 36881.0)
            ),
            savedPlans = plans,
            month = YearMonth.of(2026, 11)
        )

        val firstPeriodPlan = 60854.0 * 0.20
        val secondPeriodNovemberIncome = 36881.0 * 7.0 / 15.0
        val secondPeriodPlan = secondPeriodNovemberIncome * 0.24
        assertEquals(firstPeriodPlan + secondPeriodPlan, result["Продукты"]!!, 0.001)
    }

    @Test fun monthlyCategoryPlanUsesAverageSavedSharesForForecastOnly() {
        val result = ReportingPeriodCategoryPlanCalculator.monthlyPlan(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            periodIncomes = listOf(
                PeriodIncome("2026-11-09", "2026-11-23", 15000.0),
                PeriodIncome("2026-11-24", "2026-12-08", 15000.0)
            ),
            savedPlans = mapOf(
                ("2026-11-09" to "2026-11-23") to mapOf("Продукты" to 20.0),
                ("2026-11-24" to "2026-12-08") to mapOf("Продукты" to 30.0)
            ),
            month = YearMonth.of(2026, 11),
            forecastIncome = 4000.0
        )

        assertEquals(6100.0, result["Продукты"]!!, 0.001)
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
