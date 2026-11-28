package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetAllocationReportingCycleTest {

    @Test
    fun projectedOverspendIsProposedBeforeActualSpendingCrossesTheLimit() {
        val categories = listOf(
            "Продукты" to 20.0,
            "Кафе" to 30.0,
            "Одежда" to 50.0
        )
        val result = BudgetAllocationCalculator.redistribute(
            categories = categories,
            spentByCategory = mapOf(
                "Продукты" to 18000.0,
                "Кафе" to 8000.0,
                "Одежда" to 12000.0
            ),
            budgetPerPercent = 1000.0,
            forecastSpentByCategory = mapOf(
                "Продукты" to 25000.0,
                "Кафе" to 10000.0,
                "Одежда" to 15000.0
            ),
            safetyBufferPercent = 2.0
        )

        assertEquals(1, result.deficits.size)
        assertEquals("Продукты", result.deficits.single().category)
        assertEquals(18000.0, result.deficits.single().actualSpend, 0.001)
        assertEquals(25000.0, result.deficits.single().projectedSpend, 0.001)
        assertEquals(5000.0, result.deficits.single().amount, 0.001)
        assertEquals(1, result.transfers.size)
        assertEquals("Одежда", result.transfers.single().fromCategory)
        assertEquals("Продукты", result.transfers.single().toCategory)
        assertEquals(5000.0, result.transfers.single().amount, 0.001)
        assertEquals(0.0, result.totalUncovered, 0.001)
    }

    @Test
    fun reportsUncoveredForecastDeficitWhenAllRemainingFundsAreProtected() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf("Покупки" to 50.0, "Накопления" to 50.0),
            spentByCategory = mapOf("Покупки" to 0.0, "Накопления" to 0.0),
            budgetPerPercent = 1000.0,
            forecastSpentByCategory = mapOf("Покупки" to 60000.0, "Накопления" to 0.0),
            safetyBufferPercent = 2.0
        )

        assertEquals(1, result.deficits.size)
        assertTrue(result.transfers.isEmpty())
        assertEquals(10000.0, result.totalUncovered, 0.001)
        assertEquals(10000.0, result.uncoveredByCategory["Покупки"]!!, 0.001)
    }


    @Test
    fun zeroReportingPeriodIncomeStillShowsOverspendingWithoutCreatingDonors() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf("Продукты" to 100.0),
            spentByCategory = mapOf("Продукты" to 1200.0),
            budgetPerPercent = 0.0,
            forecastSpentByCategory = mapOf("Продукты" to 1200.0)
        )

        assertEquals(1, result.deficits.size)
        assertEquals(1200.0, result.deficits.single().amount, 0.001)
        assertTrue(result.transfers.isEmpty())
        assertEquals(1200.0, result.totalUncovered, 0.001)
    }

    @Test
    fun redistributionUsesTwentyFifteenTenAndAboveFivePercentDonorTiers() {
        val categories = listOf(
            "Превышение" to 10.0,
            "Донор 22" to 20.0,
            "Донор 18" to 20.0,
            "Донор 12" to 20.0,
            "Донор 8" to 20.0,
            "Донор 5" to 20.0,
            "Перенос средств" to 100.0,
            "Накопления" to 20.0
        )
        val projected = mapOf(
            "Превышение" to 200.0,
            "Донор 22" to 156.0, // 22% free
            "Донор 18" to 164.0, // 18% free
            "Донор 12" to 176.0, // 12% free
            "Донор 8" to 184.0,  // 8% free
            "Донор 5" to 190.0,  // 5% free: not eligible
            "Перенос средств" to 0.0,
            "Накопления" to 0.0
        )
        val result = BudgetAllocationCalculator.redistribute(
            categories = categories,
            spentByCategory = mapOf("Превышение" to 200.0),
            budgetPerPercent = 10.0,
            forecastSpentByCategory = projected,
            safetyBufferPercent = 0.0
        )

        assertEquals(
            listOf("Донор 22", "Донор 18", "Донор 12"),
            result.transfers.map { it.fromCategory }
        )
        assertEquals(listOf(44.0, 36.0, 20.0), result.transfers.map { it.amount })
        assertFalse(result.transfers.any { it.fromCategory == "Перенос средств" })
        assertFalse(result.transfers.any { it.fromCategory == "Накопления" })
        assertFalse(result.transfers.any { it.fromCategory == "Донор 5" })
        assertEquals(100.0, result.totalTransferred, 0.001)
    }

    @Test
    fun keepsOneGlobalSafetyBufferAcrossAllOrdinaryDonors() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf(
                "Транспорт" to 10.0,
                "Донор A" to 25.0,
                "Донор B" to 25.0
            ),
            spentByCategory = mapOf(
                "Транспорт" to 17000.0,
                "Донор A" to 20000.0,
                "Донор B" to 20000.0
            ),
            budgetPerPercent = 1000.0,
            safetyBufferPercent = 2.0
        )

        // Income is 100,000 ₽, so the single protected global buffer is 2,000 ₽.
        // Donors have 10,000 ₽ free in total; 8,000 ₽ is available for transfers.
        // The 7,000 ₽ transport deficit is fully covered without consuming the buffer.
        assertEquals(7000.0, result.totalTransferred, 0.001)
        assertEquals(0.0, result.totalUncovered, 0.001)
        assertEquals(listOf(5000.0, 2000.0), result.transfers.map { it.amount }.sortedDescending())
    }

    @Test
    fun housingAndUtilitiesAreNotUsedAsDonorsByDefault() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf(
                "Транспорт" to 10.0,
                "Жильё и ЖКХ" to 90.0
            ),
            spentByCategory = mapOf(
                "Транспорт" to 13000.0,
                "Жильё и ЖКХ" to 0.0
            ),
            budgetPerPercent = 1000.0,
            safetyBufferPercent = 0.0
        )

        assertTrue(result.transfers.isEmpty())
        assertEquals(3000.0, result.totalUncovered, 0.001)
    }


    @Test
    fun explicitDonorProtectionOverridesAHighAvailableBalance() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf("Транспорт" to 10.0, "Кафе" to 90.0),
            spentByCategory = mapOf("Транспорт" to 13000.0, "Кафе" to 0.0),
            budgetPerPercent = 1000.0,
            safetyBufferPercent = 0.0,
            protectedDonorCategories = setOf("Кафе"),
            donorEvidenceAvailableCategories = setOf("Транспорт", "Кафе")
        )

        assertTrue(result.transfers.isEmpty())
        assertEquals(3000.0, result.totalUncovered, 0.001)
    }

    @Test
    fun categoryWithoutUsableHistoryIsNotTreatedAsFreeMoney() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf("Транспорт" to 10.0, "Новая категория" to 90.0),
            spentByCategory = mapOf("Транспорт" to 13000.0, "Новая категория" to 0.0),
            budgetPerPercent = 1000.0,
            safetyBufferPercent = 0.0,
            donorEvidenceAvailableCategories = setOf("Транспорт")
        )

        assertTrue(result.transfers.isEmpty())
        assertEquals(3000.0, result.totalUncovered, 0.001)
    }

    @Test
    fun conservativeUpperForecastProtectsMoneyNeededByTheDonorCategory() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf("Транспорт" to 10.0, "Кафе" to 90.0),
            spentByCategory = mapOf("Транспорт" to 13000.0, "Кафе" to 0.0),
            budgetPerPercent = 1000.0,
            forecastSpentByCategory = mapOf("Транспорт" to 13000.0, "Кафе" to 1000.0),
            donorForecastSpentByCategory = mapOf("Транспорт" to 13000.0, "Кафе" to 86000.0),
            donorEvidenceAvailableCategories = setOf("Транспорт", "Кафе"),
            safetyBufferPercent = 0.0
        )

        // The central forecast leaves 89,000 ₽ in Cafe, but the upper forecast
        // leaves only 4.4% of its plan, below the strict >5% donor threshold.
        assertTrue(result.transfers.isEmpty())
        assertEquals(3000.0, result.totalUncovered, 0.001)
    }

    @Test
    fun reserveCategoryIsUsedBeforeOrdinaryDonors() {
        val result = BudgetAllocationCalculator.redistribute(
            categories = listOf(
                "Транспорт" to 10.0,
                "Резервные средства" to 20.0,
                "Кафе" to 70.0
            ),
            spentByCategory = mapOf(
                "Транспорт" to 13000.0,
                "Резервные средства" to 2000.0,
                "Кафе" to 0.0
            ),
            budgetPerPercent = 1000.0,
            safetyBufferPercent = 2.0
        )

        assertEquals("Резервные средства", result.transfers.first().fromCategory)
        assertEquals(3000.0, result.totalTransferred, 0.001)
        assertEquals(0.0, result.totalUncovered, 0.001)
    }

    @Test
    fun essentialCategoryNamesDefaultToProtectedButFlexibleCategoriesDoNot() {
        assertTrue(BudgetRedistributionProtectionDefaults.isProtectedByDefault("Жильё и ЖКХ"))
        assertTrue(BudgetRedistributionProtectionDefaults.isProtectedByDefault("Аренда"))
        assertTrue(BudgetRedistributionProtectionDefaults.isProtectedByDefault("Кредит"))
        assertTrue(BudgetRedistributionProtectionDefaults.isProtectedByDefault("Страхование"))
        assertTrue(BudgetRedistributionProtectionDefaults.isProtectedByDefault("Налог"))
        assertFalse(BudgetRedistributionProtectionDefaults.isProtectedByDefault("Кафе и развлечения"))
    }

}
