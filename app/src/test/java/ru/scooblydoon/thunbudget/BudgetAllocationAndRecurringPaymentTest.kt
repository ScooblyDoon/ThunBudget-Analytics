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
        assertEquals(60.0, result[0].suggestedPercent!!, 0.001)
        assertEquals(40.0, result[1].suggestedPercent!!, 0.001)
        assertNull(result[2].suggestedPercent)
        assertFalse(result[2].hasExpenseHistory)
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
