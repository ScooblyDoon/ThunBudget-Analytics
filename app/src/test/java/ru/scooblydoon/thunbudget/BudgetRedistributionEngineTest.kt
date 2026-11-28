package ru.scooblydoon.thunbudget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRedistributionEngineTest {
    private val start = LocalDate.parse("2026-11-09")
    private val end = LocalDate.parse("2026-11-23")

    private fun category(
        name: String,
        planned: Double,
        actual: Double = 0.0,
        future: Double = 0.0,
        recurring: Double = 0.0,
        variable: Double = 0.0,
        variableUpper: Double = variable,
        evidence: Boolean = true,
        protected: Boolean? = false
    ) = RedistributionCategoryInput(
        name = name,
        plannedAmount = planned,
        actualToDate = actual,
        knownFutureExpenses = future,
        unpaidRecurringPayments = recurring,
        variableForecast = variable,
        variableForecastUpper = variableUpper,
        hasEvidence = evidence,
        protectedFromRedistribution = protected
    )

    @Test
    fun screenshotOverspendIsCoveredFromActuallyFreeCategoryBudget() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 20_000.0,
            listOf(
                category("Транспорт", 2_582.0, actual = 3_528.0),
                category("Продукты", 5_000.0, actual = 1_000.0)
            )
        )

        assertEquals(946.0, proposal.transfers.sumOf { it.amount }, 0.001)
        assertEquals("Продукты", proposal.transfers.single().fromCategory)
        assertEquals("Транспорт", proposal.transfers.single().toCategory)
        assertEquals(0.0, proposal.uncoveredByCategory.values.sum(), 0.001)
    }

    @Test
    fun reserveIsUsedBeforeOrdinaryDonors() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 10_000.0,
            listOf(
                category("Обычная категория", 3_000.0, actual = 500.0),
                category("Транспорт", 100.0, actual = 1_000.0),
                category("Резервные средства", 150.0, evidence = false)
            )
        )

        assertEquals("Резервные средства", proposal.transfers.first().fromCategory)
        assertTrue(proposal.transfers.any { it.fromCategory == "Обычная категория" })
    }

    @Test
    fun ordinaryDonorsAreOrderedByFreePercentBands() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 1_000.0,
            listOf(
                category("Донор 10", 2_000.0, actual = 1_760.0),
                category("Донор 5", 2_000.0, actual = 1_850.0),
                category("Донор 20", 2_000.0, actual = 1_560.0),
                category("Получатель", 100.0, actual = 5_000.0),
                category("Донор 15", 2_000.0, actual = 1_660.0)
            )
        )

        assertEquals(
            listOf("Донор 20", "Донор 15", "Донор 10", "Донор 5"),
            proposal.transfers.map { it.fromCategory }.distinct()
        )
    }

    @Test
    fun protectedAndNoHistoryCategoriesAreNeverOrdinaryDonors() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 1_000.0,
            listOf(
                category("Транспорт", 100.0, actual = 1_000.0),
                category("Жильё", 3_000.0, evidence = true, protected = true),
                category("Новая категория", 3_000.0, evidence = false),
                category("Развлечения", 2_000.0, actual = 500.0)
            )
        )

        assertTrue(proposal.transfers.isNotEmpty())
        assertTrue(proposal.transfers.all { it.fromCategory == "Развлечения" })
    }

    @Test
    fun conservativeUpperForecastProtectsDonorFunds() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 1_000.0,
            listOf(
                category("Транспорт", 100.0, actual = 500.0),
                category("Здоровье", 2_000.0, actual = 1_000.0, variable = 100.0, variableUpper = 1_100.0)
            )
        )

        assertTrue(proposal.transfers.isEmpty())
        assertTrue(proposal.uncoveredByCategory["Транспорт"]!! > 0.0)
    }

    @Test
    fun twoOrdinaryDonorsShareOneGlobalSafetyBuffer() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 10_000.0,
            listOf(
                category("Получатель", 100.0, actual = 5_000.0),
                category("Донор А", 1_000.0),
                category("Донор Б", 1_000.0)
            )
        )

        assertEquals(1_800.0, proposal.transfers.sumOf { it.amount }, 0.001)
    }

    @Test
    fun partialCoverageShowsTheUncoveredRemainder() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 1_000.0,
            listOf(
                category("Транспорт", 100.0, actual = 600.0),
                category("Резервные средства", 300.0, evidence = false)
            )
        )

        assertEquals(300.0, proposal.transfers.sumOf { it.amount }, 0.001)
        assertEquals(200.0, proposal.uncoveredByCategory["Транспорт"]!!, 0.001)
    }

    @Test
    fun definedZeroIncomeNeverCreatesDonorBudget() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 0.0,
            listOf(
                category("Транспорт", 0.0, actual = 600.0),
                category("Продукты", 0.0)
            )
        )

        assertTrue(proposal.transfers.isEmpty())
        assertFalse(proposal.deficits.isEmpty())
        assertEquals(600.0, proposal.uncoveredByCategory["Транспорт"]!!, 0.001)
    }

    @Test
    fun transferAmountsAreRoundedToKopecks() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 1_000.0,
            listOf(
                category("Транспорт", 100.0, actual = 110.125),
                category("Резервные средства", 50.0, evidence = false)
            )
        )

        assertEquals(10.13, proposal.transfers.single().amount, 0.001)
    }

    @Test
    fun fingerprintIsDeterministicAndIndependentOfInputOrdering() {
        val first = listOf(category("Транспорт", 100.0, actual = 140.0), category("Продукты", 500.0, actual = 30.0))
        val second = first.reversed()

        val a = BudgetRedistributionCalculator.calculate(start, end, 1_000.0, first)
        val b = BudgetRedistributionCalculator.calculate(start, end, 1_000.0, second)

        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(64, a.fingerprint.length)
    }

    @Test
    fun sameProposalFingerprintCannotBeAppliedTwice() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 1_000.0,
            listOf(category("Транспорт", 100.0, actual = 300.0), category("Резервные средства", 500.0))
        )
        val firstApplication = BudgetRedistributionCalculator.appendAppliedTransfers(
            proposal, emptyList(), "2026-11-27T12:00:00Z"
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            BudgetRedistributionCalculator.appendAppliedTransfers(
                proposal, firstApplication, "2026-11-27T12:01:00Z"
            )
        }
    }




    @Test
    fun displayedCategoryPercentReflectsTransferredPlanWithoutChangingSavedShares() {
        assertEquals(17.0, BudgetRedistributionCalculator.effectiveCategoryPercent(1_700.0, 10_000.0, 20.0), 0.001)
        assertEquals(13.0, BudgetRedistributionCalculator.effectiveCategoryPercent(1_300.0, 10_000.0, 20.0), 0.001)
        assertEquals(20.0, BudgetRedistributionCalculator.effectiveCategoryPercent(0.0, 0.0, 20.0), 0.001)
        assertEquals(100.0, listOf(1_700.0, 1_300.0, 7_000.0).sumOf {
            BudgetRedistributionCalculator.effectiveCategoryPercent(it, 10_000.0, 0.0)
        }, 0.001)
    }

    @Test
    fun netAdjustmentsForReportingCycleIncludeOnlyItsExactTwoPeriods() {
        val secondStart = end.plusDays(1)
        val secondEnd = secondStart.plusDays(14)
        val transfers = listOf(
            AppliedBudgetRedistributionTransfer(start, end, "Продукты", "Транспорт", 250.0, "a".repeat(64), "2026-11-27T12:00:00Z"),
            AppliedBudgetRedistributionTransfer(secondStart, secondEnd, "Продукты", "Здоровье", 100.0, "b".repeat(64), "2026-11-27T12:00:00Z"),
            AppliedBudgetRedistributionTransfer(secondEnd.plusDays(1), secondEnd.plusDays(15), "Продукты", "Покупки", 75.0, "c".repeat(64), "2026-11-27T12:00:00Z")
        )
        val selected = BudgetRedistributionCalculator.netAdjustmentsForPeriods(
            listOf(start to end, secondStart to secondEnd), transfers
        )
        assertEquals(-350.0, selected["Продукты"]!!, 0.001)
        assertEquals(250.0, selected["Транспорт"]!!, 0.001)
        assertEquals(100.0, selected["Здоровье"]!!, 0.001)
        assertFalse(selected.containsKey("Покупки"))
    }

    @Test
    fun explicitFalseCanUnprotectANameBasedDefaultButNeverSavings() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 10_000.0,
            listOf(
                category("Транспорт", 100.0, actual = 500.0),
                category("Жильё и ЖКХ", 2_000.0, protected = false),
                category("Накопления", 3_000.0, protected = false),
                category("Покупки", 1_000.0, actual = 200.0)
            )
        )
        assertTrue(proposal.transfers.isNotEmpty())
        assertTrue(proposal.transfers.none { it.fromCategory == "Накопления" })
        assertTrue(proposal.transfers.any { it.fromCategory == "Жильё и ЖКХ" })
    }

    @Test
    fun absentProtectionOverrideUsesNameBasedDefaults() {
        val proposal = BudgetRedistributionCalculator.calculate(
            start, end, 10_000.0,
            listOf(
                category("Транспорт", 100.0, actual = 500.0),
                category("Жильё и ЖКХ", 2_000.0, protected = null),
                category("Покупки", 1_000.0, actual = 200.0)
            )
        )
        assertTrue(proposal.transfers.isNotEmpty())
        assertTrue(proposal.transfers.none { it.fromCategory == "Жильё и ЖКХ" })
    }

    @Test
    fun netAdjustmentsAreIsolatedToExactReportingPeriod() {
        val transfers = listOf(
            AppliedBudgetRedistributionTransfer(start, end, "Продукты", "Транспорт", 250.0, "a".repeat(64), "2026-11-27T12:00:00Z"),
            AppliedBudgetRedistributionTransfer(start.plusDays(15), end.plusDays(15), "Продукты", "Здоровье", 100.0, "b".repeat(64), "2026-11-27T12:00:00Z")
        )

        val selected = BudgetRedistributionCalculator.netAdjustments(start, end, transfers)

        assertEquals(-250.0, selected["Продукты"]!!, 0.001)
        assertEquals(250.0, selected["Транспорт"]!!, 0.001)
        assertFalse(selected.containsKey("Здоровье"))
    }
}
