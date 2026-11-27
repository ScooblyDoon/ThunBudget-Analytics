package ru.scooblydoon.thunbudget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BudgetRedistributionPersistenceTest {
    private val start = LocalDate.parse("2026-11-09")
    private val end = LocalDate.parse("2026-11-23")
    private val fingerprint = "a".repeat(64)

    private fun row(
        from: String = "Продукты",
        to: String = "Транспорт",
        amount: Double = 125.50,
        fp: String = fingerprint,
        at: String = "2026-11-27T12:00:00Z"
    ) = AppliedBudgetRedistributionTransfer(start, end, from, to, amount, fp, at)

    @Test
    fun emptyLedgerHasAnExplicitVersionedRepresentation() {
        val encoded = BudgetRedistributionLedgerCodec.encode(emptyList())
        assertEquals(emptyList<AppliedBudgetRedistributionTransfer>(), BudgetRedistributionLedgerCodec.decode(encoded))
        assertThrows(IllegalArgumentException::class.java) { BudgetRedistributionLedgerCodec.decode("legacy-format") }
    }

    @Test
    fun ledgerRoundTripPreservesMultipleRowsAndSpecialCategoryNames() {
        val rows = listOf(
            row(from = "Продукты\tи покупки", amount = 125.50),
            row(from = "Резервные средства", to = "Жильё / ЖКХ", amount = 12.25, fp = "b".repeat(64))
        )

        assertEquals(rows, BudgetRedistributionLedgerCodec.decode(BudgetRedistributionLedgerCodec.encode(rows)))
    }

    @Test
    fun oneProposalCanContainSeveralDistinctTransfers() {
        val rows = listOf(
            row(from = "Продукты", to = "Транспорт", amount = 50.0),
            row(from = "Развлечения", to = "Транспорт", amount = 75.0)
        )
        assertEquals(2, BudgetRedistributionLedgerCodec.decode(BudgetRedistributionLedgerCodec.encode(rows)).size)
    }

    @Test
    fun duplicateTransferRowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetRedistributionLedgerCodec.encode(listOf(row(), row()))
        }
    }

    @Test
    fun invalidMoneyPrecisionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetRedistributionLedgerCodec.encode(listOf(row(amount = 1.234)))
        }
    }
}
