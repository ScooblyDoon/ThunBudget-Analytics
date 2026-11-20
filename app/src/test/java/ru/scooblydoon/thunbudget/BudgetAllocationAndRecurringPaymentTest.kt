package ru.scooblydoon.thunbudget

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BudgetAllocationTest {
    @Test fun decemberUsesJuneThroughNovember() {
        val window = BudgetAllocationCalculator.windowFor(LocalDate.of(2026, 12, 20))
        assertEquals(LocalDate.of(2026, 6, 1), window.from)
        assertEquals(LocalDate.of(2026, 11, 30), window.to)
    }

    @Test fun suggestionsUseSixMonthSpendShareAndFlagCategoriesWithoutHistory() {
        val result = BudgetAllocationCalculator.suggest(
            listOf("Продукты", "Транспорт", "Здоровье"),
            listOf(
                AllocationExpense("Продукты", 600.0, LocalDate.of(2026, 6, 2)),
                AllocationExpense("Транспорт", 400.0, LocalDate.of(2026, 11, 2)),
                AllocationExpense("Продукты", 1000.0, LocalDate.of(2026, 5, 31))
            ),
            LocalDate.of(2026, 12, 1)
        )
        assertEquals(80.0, result[0].suggestedPercent!!, 0.001)
        assertEquals(20.0, result[1].suggestedPercent!!, 0.001)
        assertNull(result[2].suggestedPercent)
        assertFalse(result[2].hasExpenseHistory)
    }


    @Test fun shortHistoryUsesEveryAvailableCompletedMonth() {
        val result = BudgetAllocationCalculator.suggest(
            listOf("Продукты", "Транспорт"),
            listOf(
                AllocationExpense("Продукты", 300.0, LocalDate.of(2026, 11, 2)),
                AllocationExpense("Транспорт", 100.0, LocalDate.of(2026, 11, 5))
            ),
            LocalDate.of(2026, 12, 1)
        )
        assertEquals(75.0, result[0].suggestedPercent!!, 0.001)
        assertEquals(25.0, result[1].suggestedPercent!!, 0.001)
        val window = BudgetAllocationCalculator.windowFor(
            LocalDate.of(2026, 12, 1),
            listOf(AllocationExpense("Продукты", 300.0, LocalDate.of(2026, 11, 2)))
        )
        assertEquals(LocalDate.of(2026, 11, 1), window.from)
        assertEquals(LocalDate.of(2026, 11, 30), window.to)
    }

    @Test fun sixOrMoreAvailableMonthsUseOnlyLastSixCompletedMonths() {
        val history = (1..11).map { month ->
            AllocationExpense("Продукты", month * 10.0, LocalDate.of(2026, month, 2))
        }
        val window = BudgetAllocationCalculator.windowFor(LocalDate.of(2026, 12, 1), history)
        assertEquals(LocalDate.of(2026, 6, 1), window.from)
        assertEquals(LocalDate.of(2026, 11, 30), window.to)
        val suggestion = BudgetAllocationCalculator.suggest(listOf("Продукты"), history, LocalDate.of(2026, 12, 1)).single()
        assertEquals(60.0 + 70.0 + 80.0 + 90.0 + 100.0 + 110.0, suggestion.totalSpent, 0.001)
    }

    @Test fun redistributionUsesDonorsInTwentyFifteenTenPercentTiersAndLeavesSmallReserves() {
        val categories = listOf("Превышение" to 20.0, "Запас20" to 30.0, "Запас15" to 30.0, "Запас10" to 10.0, "Запас5" to 10.0)
        val result = BudgetAllocationCalculator.redistribute(
            categories,
            mapOf("Превышение" to 300.0, "Запас20" to 240.0, "Запас15" to 255.0, "Запас10" to 90.0, "Запас5" to 99.0),
            10.0
        )
        assertEquals("Запас20", result.transfers.first().fromCategory)
        assertTrue(result.transfers.none { it.fromCategory == "Запас5" })
        assertEquals(100.0, result.updatedPercents.values.sum(), 0.001)
        assertTrue(result.updatedPercents["Превышение"]!! > 20.0)
    }

    @Test fun freeBalanceIsShownAsPercentageOfCategoryPlan() {
        assertEquals(20.0, BudgetAllocationCalculator.freePercent(1000.0, 800.0)!!, 0.001)
        assertEquals(-10.0, BudgetAllocationCalculator.freePercent(1000.0, 1100.0)!!, 0.001)
        assertNull(BudgetAllocationCalculator.freePercent(0.0, 0.0))
    }

    @Test fun reserveAppearsOnlyWhenThereIsAnUnallocatedRemainder() {
        assertEquals(15.0, BudgetAllocationCalculator.reservePercent(listOf(40.0, 45.0)), 0.001)
        assertEquals(0.0, BudgetAllocationCalculator.reservePercent(listOf(40.0, 60.0)), 0.001)
        assertEquals(1, BudgetAllocationCalculator.withReserve(listOf("Еда" to 100.0)).size)
        assertEquals(2, BudgetAllocationCalculator.withReserve(listOf("Еда" to 85.0)).size)
    }
}

class RecurringPaymentTest {
    @Test fun dueDayClampsToLastDayOfShortMonth() {
        val payment = RecurringPayment(title="Аренда", amount=1000.0, category="Жильё", dayOfMonth=31)
        assertEquals(LocalDate.of(2026, 2, 28), payment.dueDate(YearMonth.of(2026, 2)))
    }

    @Test fun paymentIsPendingUntilMonthIsMarkedCompleted() {
        val payment = RecurringPayment(title="Интернет", amount=500.0, category="Связь", dayOfMonth=10)
        assertFalse(payment.isCompleted(YearMonth.of(2026, 11)))
        assertTrue(payment.copy(completedMonths=setOf("2026-11")).isCompleted(YearMonth.of(2026, 11)))
    }
}
