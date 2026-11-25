package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomeLedgerMigrationTest {
    @Test
    fun convertsLegacyAggregateWithoutChangingAmountOrPeriod() {
        val migrated = IncomeLedgerMigration.migrate(
            listOf(LegacyPeriodIncome("2026-11-01", "2026-11-30", 54000.0))
        )
        assertEquals(1, migrated.size)
        val entry = migrated.single()
        assertEquals(54000.0, entry.amount, 0.0)
        assertEquals("2026-11-30", entry.date)
        assertEquals("2026-11-01", entry.periodStart)
        assertEquals("2026-11-30", entry.periodEnd)
        assertTrue(entry.isLegacyAggregate)
        assertEquals("Перенесённый доход за период", entry.title)
    }

    @Test
    fun preservesExistingEntriesAndDoesNotDuplicateLegacyIds() {
        val legacy = listOf(LegacyPeriodIncome("2026-11-01", "2026-11-30", 54000.0))
        val first = IncomeLedgerMigration.migrate(legacy)
        val second = IncomeLedgerMigration.migrate(legacy, first)
        assertEquals(first, second)
    }

    @Test
    fun migrationPreservesEarnedAndCarryoverAmountsSeparately() {
        val migrated = IncomeLedgerMigration.migrate(
            listOf(
                LegacyPeriodIncome(
                    start = "2026-11-09",
                    end = "2026-11-23",
                    amount = 70000.0,
                    earnedAmount = 50000.0,
                    carryoverAmount = 20000.0
                )
            )
        )
        assertEquals(2, migrated.size)
        assertEquals(50000.0, migrated.filterNot { it.isCarryover }.sumOf { it.amount }, 0.001)
        assertEquals(20000.0, migrated.filter { it.isCarryover }.sumOf { it.amount }, 0.001)
    }

    @Test
    fun leavesBaseBudgetIncomeOutsideTheLedger() {
        val migrated = IncomeLedgerMigration.migrate(
            listOf(LegacyPeriodIncome("2026-11-01", "2026-11-30", 0.0))
        )
        assertTrue(migrated.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPeriod() {
        IncomeLedgerMigration.migrate(
            listOf(LegacyPeriodIncome("2026-11-30", "2026-11-01", 1000.0))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeOrNonFiniteAmount() {
        IncomeLedgerMigration.migrate(
            listOf(LegacyPeriodIncome("2026-11-01", "2026-11-30", Double.NaN))
        )
    }
}
