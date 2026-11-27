package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BudgetAllocationReportingCycleTest {
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
}
