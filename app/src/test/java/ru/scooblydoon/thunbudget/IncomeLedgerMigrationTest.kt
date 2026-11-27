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
    fun doesNotImportLegacyAggregateWhenLedgerAlreadyContainsPeriodEntries() {
        val existing = listOf(
            IncomeEntry(
                id = "detail-earned", title = "Зарплата", amount = 50000.0,
                date = "2026-11-23", periodStart = "2026-11-09", periodEnd = "2026-11-23"
            ),
            IncomeEntry(
                id = "detail-carryover", title = "Перенос средств", amount = 20000.0,
                date = "2026-11-23", periodStart = "2026-11-09", periodEnd = "2026-11-23",
                isCarryover = true
            )
        )
        val migrated = IncomeLedgerMigration.migrate(
            listOf(
                LegacyPeriodIncome(
                    start = "2026-11-09", end = "2026-11-23", amount = 70000.0,
                    earnedAmount = 50000.0, carryoverAmount = 20000.0
                )
            ),
            existing
        )
        assertEquals(existing, migrated)
        assertEquals(50000.0, migrated.filterNot { it.isCarryover }.sumOf { it.amount }, 0.001)
        assertEquals(20000.0, migrated.filter { it.isCarryover }.sumOf { it.amount }, 0.001)
    }

    @Test
    fun importsOnlyMissingPartOfLegacyPeriodAmounts() {
        val existing = listOf(
            IncomeEntry(
                id = "partial-earned", title = "Зарплата", amount = 30000.0,
                date = "2026-11-23", periodStart = "2026-11-09", periodEnd = "2026-11-23"
            ),
            IncomeEntry(
                id = "partial-carryover", title = "Перенос средств", amount = 5000.0,
                date = "2026-11-23", periodStart = "2026-11-09", periodEnd = "2026-11-23",
                isCarryover = true
            )
        )
        val migrated = IncomeLedgerMigration.migrate(
            listOf(
                LegacyPeriodIncome(
                    start = "2026-11-09", end = "2026-11-23", amount = 70000.0,
                    earnedAmount = 50000.0, carryoverAmount = 20000.0
                )
            ),
            existing
        )
        assertEquals(50000.0, migrated.filterNot { it.isCarryover }.sumOf { it.amount }, 0.001)
        assertEquals(20000.0, migrated.filter { it.isCarryover }.sumOf { it.amount }, 0.001)
        assertEquals(4, migrated.size)
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

    @Test
    fun replacingPrimaryIncomePreservesAdditionalIncomeAndCarryover() {
        val start = "2026-11-09"
        val end = "2026-11-23"
        val entries = listOf(
            IncomeEntry(
                id = "main", title = "Доход за период", amount = 30000.0,
                date = end, periodStart = start, periodEnd = end
            ),
            IncomeEntry(
                id = "bonus", title = "Премия", amount = 5000.0,
                date = "2026-11-15", periodStart = start, periodEnd = end,
                isAdditional = true
            ),
            IncomeEntry(
                id = "carry", title = "Перенос средств", amount = 2000.0,
                date = start, periodStart = start, periodEnd = end,
                isCarryover = true
            ),
            IncomeEntry(
                id = "next", title = "Дополнительный доход другого периода", amount = 1000.0,
                date = "2026-12-01", periodStart = "2026-11-24", periodEnd = "2026-12-08",
                isAdditional = true
            )
        )

        val updated = IncomeLedgerRules.replacePrimaryIncome(entries, start, end, 40000.0)

        assertEquals(4, updated.size)
        assertEquals(40000.0, updated.single { it.id.startsWith("manual-") }.amount, 0.001)
        assertEquals(5000.0, updated.single { it.id == "bonus" }.amount, 0.001)
        assertTrue(updated.single { it.id == "bonus" }.isAdditional)
        assertEquals(2000.0, updated.single { it.id == "carry" }.amount, 0.001)
        assertTrue(updated.single { it.id == "carry" }.isCarryover)
        assertEquals(1000.0, updated.single { it.id == "next" }.amount, 0.001)
    }

    @Test
    fun clearingPrimaryIncomeDoesNotDeleteAdditionalOrCarryoverEntries() {
        val start = "2026-11-09"
        val end = "2026-11-23"
        val entries = listOf(
            IncomeEntry(
                id = "main", title = "Доход за период", amount = 30000.0,
                date = end, periodStart = start, periodEnd = end
            ),
            IncomeEntry(
                id = "bonus", title = "Премия", amount = 5000.0,
                date = "2026-11-15", periodStart = start, periodEnd = end,
                isAdditional = true
            ),
            IncomeEntry(
                id = "carry", title = "Перенос средств", amount = 2000.0,
                date = start, periodStart = start, periodEnd = end,
                isCarryover = true
            )
        )

        val updated = IncomeLedgerRules.replacePrimaryIncome(entries, start, end, 0.0)

        assertEquals(2, updated.size)
        assertEquals(listOf("bonus", "carry"), updated.map { it.id }.sorted())
    }

}
