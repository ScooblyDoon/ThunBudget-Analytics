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

    @Test fun monthlyIncomeUsesBothCompleteReportingPeriodsInsteadOfCalendarOverlap() {
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
        assertEquals(90000.0, result, 0.001)
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
        assertEquals(86881.0, result, 0.001)
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

    @Test fun legacyOverallocatedPeriodSnapshotCannotIncreasePlannedIncome() {
        val start = java.time.LocalDate.parse("2026-11-24")
        val end = java.time.LocalDate.parse("2026-12-08")
        val plan = ReportingPeriodCategoryPlanCalculator.periodPlan(
            currentShares = listOf("Продукты" to 90.0, "Транспорт" to 20.0),
            savedPlans = mapOf(
                ("2026-11-24" to "2026-12-08") to mapOf(
                    "Продукты" to 90.0,
                    "Транспорт" to 20.0
                )
            ),
            start = start,
            end = end
        )

        assertEquals(100.0, plan.values.sum(), 0.001)
        val totalBudget = AnalyticsBudgetCalculator.periodCategoryBudget(
            periodIncome = 36881.0,
            monthlyIncomeFallback = 40000.0,
            categoryPercent = plan.values.sum(),
            start = start,
            end = end
        )
        assertEquals(36881.0, totalBudget, 0.001)
        assertEquals(900.0 / 1100.0 * 100.0, plan["Продукты"]!!, 0.001)
        assertEquals(200.0 / 1100.0 * 100.0, plan["Транспорт"]!!, 0.001)
    }

    @Test fun cyclePlanAveragesSavedPlannedSharesAndDoesNotUseActualSpendRatios() {
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

    @Test fun calendarMonthExpenseEndMatchesAnalysisWindow() {
        val today = java.time.LocalDate.of(2026, 11, 27)
        assertEquals(
            java.time.LocalDate.of(2026, 11, 27),
            AnalyticsBudgetCalculator.calendarMonthExpenseEnd(YearMonth.of(2026, 11), today)
        )
        assertEquals(
            java.time.LocalDate.of(2026, 10, 31),
            AnalyticsBudgetCalculator.calendarMonthExpenseEnd(YearMonth.of(2026, 10), today)
        )
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

    @Test fun additionalIncomeCountsInActualMonthlyReceiptsButNotRegularIncomeForecast() {
        val periods = listOf(
            PeriodIncome(
                start = "2026-10-24",
                end = "2026-11-08",
                amount = 40000.0,
                earnedAmount = 40000.0,
                additionalAmount = 10000.0
            ),
            PeriodIncome(
                start = "2026-11-09",
                end = "2026-11-23",
                amount = 40000.0,
                earnedAmount = 40000.0,
                additionalAmount = 10000.0
            )
        )

        val estimate = AnalyticsBudgetCalculator.calendarMonthIncome(
            periodIncomes = periods,
            month = YearMonth.of(2026, 11),
            asOf = java.time.LocalDate.parse("2026-11-23")
        )

        // November includes Nov 1–8 (8/16 of the first period) and all 15 days
        // of the second period: 20,000 + 40,000 = 60,000 actual month income.
        assertEquals(60000.0, estimate.enteredIncome, 0.001)
        // Forecast baseline uses 30,000 regular earnings in each completed period:
        // median(30,000/16, 30,000/15) = 1,937.50 per uncovered future day.
        assertEquals(1937.5, estimate.medianDailyIncome!!, 0.001)
        assertEquals(7, estimate.uncoveredFutureDays)
        assertEquals(13562.5, estimate.forecastIncome, 0.001)
    }

    @Test fun reportingCycleIncomeIncludesAdditionalReceiptsButExcludesCarryover() {
        val result = AnalyticsBudgetCalculator.reportingCycleIncome(
            listOf(
                PeriodIncome(
                    start = "2026-11-09",
                    end = "2026-11-23",
                    amount = 41000.0,
                    earnedAmount = 36000.0,
                    carryoverAmount = 5000.0,
                    additionalAmount = 6000.0
                ),
                PeriodIncome(
                    start = "2026-11-24",
                    end = "2026-12-08",
                    amount = 17000.0,
                    earnedAmount = 14000.0,
                    carryoverAmount = 3000.0,
                    additionalAmount = 3000.0
                )
            ),
            YearMonth.of(2026, 11),
            9,
            23
        )

        assertEquals(36000.0, result.firstIncome, 0.001)
        assertEquals(14000.0, result.secondIncome, 0.001)
        assertEquals(50000.0, result.totalIncome, 0.001)
    }


    @Test
    fun datedAdditionalIncomeIsCountedInTheCalendarMonthItArrives() {
        val periods = listOf(
            PeriodIncome(
                start = "2026-10-24",
                end = "2026-11-08",
                amount = 40000.0,
                earnedAmount = 40000.0,
                additionalAmount = 10000.0
            ),
            PeriodIncome(
                start = "2026-11-09",
                end = "2026-11-23",
                amount = 40000.0,
                earnedAmount = 40000.0,
                additionalAmount = 0.0
            )
        )
        val entries = listOf(
            IncomeEntry(
                id = "returned-savings",
                title = "Возврат с накопительного счёта",
                amount = 10000.0,
                date = "2026-11-08",
                periodStart = "2026-10-24",
                periodEnd = "2026-11-08",
                isAdditional = true
            )
        )

        val estimate = AnalyticsBudgetCalculator.calendarMonthIncome(
            periodIncomes = periods,
            month = YearMonth.of(2026, 11),
            asOf = java.time.LocalDate.parse("2026-11-23"),
            incomeEntries = entries
        )

        // Base income for Oct 24–Nov 8 is prorated: 30,000 / 16 * 8 = 15,000.
        // The 10,000 ₽ return was actually recorded on Nov 8, so all of it belongs to November.
        // Nov 9–23 contributes another 40,000 ₽ of regular + additional income.
        assertEquals(65000.0, estimate.enteredIncome, 0.001)
        assertEquals((30000.0 / 16.0 + 40000.0 / 15.0) / 2.0, estimate.medianDailyIncome!!, 0.001)
    }

    @Test
    fun additionalOnlyPeriodDoesNotPretendRegularIncomeCoversItsFutureDays() {
        val periods = listOf(
            PeriodIncome("2026-09-24", "2026-10-08", 15000.0),
            PeriodIncome("2026-10-09", "2026-10-23", 15000.0),
            PeriodIncome("2026-10-24", "2026-11-08", 16000.0),
            PeriodIncome(
                start = "2026-11-09",
                end = "2026-11-23",
                amount = 5000.0,
                earnedAmount = 5000.0,
                additionalAmount = 5000.0
            )
        )
        val entries = listOf(
            IncomeEntry(
                id = "one-off",
                title = "Разовый возврат",
                amount = 5000.0,
                date = "2026-11-14",
                periodStart = "2026-11-09",
                periodEnd = "2026-11-23",
                isAdditional = true
            )
        )

        val estimate = AnalyticsBudgetCalculator.calendarMonthIncome(
            periodIncomes = periods,
            month = YearMonth.of(2026, 11),
            asOf = java.time.LocalDate.parse("2026-11-15"),
            incomeEntries = entries
        )

        // Nov 1–8 receives 8/16 of the regular 16,000 ₽ from Oct 24–Nov 8,
        // plus the dated 5,000 ₽ additional receipt from Nov 14.
        assertEquals(13000.0, estimate.enteredIncome, 0.001)
        assertEquals(15, estimate.uncoveredFutureDays)
        assertEquals(15, estimate.forecastDays)
    }

    @Test
    fun monthlyCategoryPlanAttributesDatedAdditionalIncomeToItsReceiptDate() {
        val result = ReportingPeriodCategoryPlanCalculator.monthlyPlan(
            currentShares = listOf("Продукты" to 50.0, "Резервные средства" to 50.0),
            periodIncomes = listOf(
                PeriodIncome(
                    start = "2026-10-24",
                    end = "2026-11-08",
                    amount = 40000.0,
                    earnedAmount = 40000.0,
                    additionalAmount = 10000.0
                )
            ),
            savedPlans = mapOf(
                ("2026-10-24" to "2026-11-08") to mapOf("Продукты" to 50.0)
            ),
            month = YearMonth.of(2026, 11),
            incomeEntries = listOf(
                IncomeEntry(
                    id = "additional-november",
                    title = "Возврат с накоплений",
                    amount = 10000.0,
                    date = "2026-11-08",
                    periodStart = "2026-10-24",
                    periodEnd = "2026-11-08",
                    isAdditional = true
                )
            )
        )

        // 15,000 ₽ regular income accrues over Nov 1–8, plus the full 10,000 ₽ receipt on Nov 8.
        assertEquals(12500.0, result["Продукты"]!!, 0.001)
    }

    @Test
    fun cashFlowMonthExcludesOnlyInternalCarryoverAndKeepsSavingsAsOutflow() {
        val expenses = listOf(
            Expense(1L, "Покупка", "Продукты", 1000.0, "2026-11-10"),
            Expense(2L, "На накопительный счёт", "Накопления", 10000.0, "2026-11-11"),
            Expense(3L, "В резерв", "Резервные средства", 3000.0, "2026-11-12"),
            Expense(4L, "Перенос остатка во второй период", "Перенос средств", 5000.0, "2026-11-23")
        )

        val monthTotal = AnalyticsBudgetCalculator.calendarMonthCashExpenseTotal(
            expenses, YearMonth.of(2026, 11), java.time.LocalDate.parse("2026-11-27")
        )

        assertEquals(14000.0, monthTotal, 0.001)
        assertEquals(19000.0, expenses.sumOf { it.amount }, 0.001)
    }


    @Test
    fun reportingCycleCashExpensesUseBothPeriodsAndStopAtToday() {
        val expenses = listOf(
            Expense(1L, "До цикла", "Продукты", 100.0, "2026-11-08"),
            Expense(2L, "Начало цикла", "Продукты", 100.0, "2026-11-09"),
            Expense(3L, "Конец первого периода", "Продукты", 200.0, "2026-11-23"),
            Expense(4L, "Технический перенос", "Перенос средств", 500.0, "2026-11-23"),
            Expense(5L, "Начало второго периода", "Продукты", 300.0, "2026-11-24"),
            Expense(6L, "Конец цикла", "Продукты", 400.0, "2026-12-08"),
            Expense(7L, "После цикла", "Продукты", 500.0, "2026-12-09")
        )

        assertEquals(
            600.0,
            AnalyticsBudgetCalculator.reportingCycleCashExpenseTotal(
                expenses, YearMonth.of(2026, 11), java.time.LocalDate.parse("2026-11-27"), 9, 23
            ),
            0.001
        )
        assertEquals(
            1000.0,
            AnalyticsBudgetCalculator.reportingCycleCashExpenseTotal(
                expenses, YearMonth.of(2026, 11), java.time.LocalDate.parse("2026-12-10"), 9, 23
            ),
            0.001
        )
    }

    @Test
    fun availableReportingPeriodIncomeDoesNotDoubleCountRecordedCarryover() {
        val periods = listOf(
            PeriodIncome(
                start = "2026-11-09", end = "2026-11-23", amount = 40000.0,
                earnedAmount = 40000.0
            ),
            PeriodIncome(
                start = "2026-11-24", end = "2026-12-08", amount = 35000.0,
                earnedAmount = 30000.0, carryoverAmount = 5000.0
            )
        )
        val expenses = listOf(
            Expense(1L, "Покупки", "Продукты", 30000.0, "2026-11-20"),
            Expense(2L, "Перенос остатка", "Перенос средств", 10000.0, "2026-11-23")
        )
        val available = AnalyticsBudgetCalculator.reportingPeriodAvailableIncome(
            periods, java.time.LocalDate.parse("2026-11-24"), java.time.LocalDate.parse("2026-12-08"),
            expenses, 9, 23
        )
        assertEquals(35000.0, available, 0.001)
    }

    @Test
    fun availableReportingPeriodIncomeAddsLegacyCarryoverOnlyWhenNoExplicitTransferExists() {
        val periods = listOf(
            PeriodIncome(
                start = "2026-11-09", end = "2026-11-23", amount = 40000.0,
                earnedAmount = 40000.0
            ),
            PeriodIncome(
                start = "2026-11-24", end = "2026-12-08", amount = 30000.0,
                earnedAmount = 30000.0
            )
        )
        val expenses = listOf(
            Expense(1L, "Покупки", "Продукты", 30000.0, "2026-11-20")
        )
        val available = AnalyticsBudgetCalculator.reportingPeriodAvailableIncome(
            periods, java.time.LocalDate.parse("2026-11-24"), java.time.LocalDate.parse("2026-12-08"),
            expenses, 9, 23
        )
        assertEquals(40000.0, available, 0.001)
    }

    @Test
    fun cycleCategoryPlanWeightsEachPeriodsOwnIncomeAndSavedShare() {
        val firstStart = java.time.LocalDate.parse("2026-11-09")
        val firstEnd = java.time.LocalDate.parse("2026-11-23")
        val secondStart = java.time.LocalDate.parse("2026-11-24")
        val secondEnd = java.time.LocalDate.parse("2026-12-08")
        val cycle = ReportingPeriodCategoryPlanCalculator.cyclePlan(
            currentShares = listOf("Продукты" to 20.0, "Резервные средства" to 80.0),
            savedPlans = mapOf(
                (firstStart.toString() to firstEnd.toString()) to mapOf("Продукты" to 20.0),
                (secondStart.toString() to secondEnd.toString()) to mapOf("Продукты" to 40.0)
            ),
            firstStart = firstStart,
            firstEnd = firstEnd,
            secondStart = secondStart,
            secondEnd = secondEnd,
            firstIncome = 60000.0,
            secondIncome = 40000.0
        )
        assertEquals(28.0, cycle.averagePercentages["Продукты"]!!, 0.001)
        assertEquals(
            28000.0,
            100000.0 * cycle.averagePercentages["Продукты"]!! / 100.0,
            0.001
        )
    }

    @Test
    fun redistributionUsesConfiguredDonorTiersAndProtectsFivePercentOrLess() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf(
                "Кафе" to 10.0,
                "Резервные средства" to 1.0,
                "Продукты" to 30.0,
                "Здоровье" to 20.0,
                "Транспорт" to 15.0,
                "Накопления" to 10.0,
                "Личные расходы" to 10.0,
                "Перенос средств" to 4.0
            ),
            spentByCategory = mapOf(
                "Кафе" to 22000.0,
                "Резервные средства" to 0.0,
                "Продукты" to 24000.0,
                "Здоровье" to 17000.0,
                "Транспорт" to 13500.0,
                "Накопления" to 9400.0,
                "Личные расходы" to 9500.0,
                "Перенос средств" to 0.0
            ),
            budgetPerPercent = 1000.0,
            safetyBufferPercent = 0.0
        )
        assertEquals(
            listOf("Резервные средства", "Продукты", "Здоровье", "Транспорт", "Накопления"),
            result.transfers.map { it.fromCategory }
        )
        assertEquals(12000.0, result.totalTransferred, 0.001)
        assertEquals(500.0, result.transfers.last().amount, 0.001)
    }

}
