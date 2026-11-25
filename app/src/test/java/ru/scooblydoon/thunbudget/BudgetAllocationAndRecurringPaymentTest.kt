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

    @Test fun redistributionUsesReserveFirstAndProtectsSavingsAndSmallDonors() {
        val categories = listOf(
            "Цель" to 20.0,
            "Накопления" to 20.0,
            BudgetAllocationCalculator.RESERVE_CATEGORY to 5.0,
            "Запас9" to 20.0,
            "Запас10" to 20.0,
            "Перерасход" to 15.0
        )
        val result = BudgetAllocationCalculator.redistribute(
            categories,
            mapOf(
                "Цель" to 300.0,
                "Накопления" to 100.0,
                BudgetAllocationCalculator.RESERVE_CATEGORY to 0.0,
                "Запас9" to 182.0,
                "Запас10" to 180.0,
                "Перерасход" to 200.0
            ),
            10.0
        )
        assertTrue(result.transfers.isNotEmpty())
        assertEquals(BudgetAllocationCalculator.RESERVE_CATEGORY, result.transfers.first().fromCategory)
        assertTrue(result.transfers.none { it.fromCategory == "Накопления" || it.fromCategory == "Запас9" })
    }

    @Test fun forecastDonorKeepsTwoPercentSafetyMargin() {
        val categories = listOf("Кафе" to 10.0, "Транспорт" to 10.0)
        val result = BudgetAllocationCalculator.redistribute(
            categories = categories,
            spentByCategory = mapOf("Кафе" to 13000.0, "Транспорт" to 0.0),
            budgetPerPercent = 1000.0,
            forecastSpentByCategory = mapOf("Транспорт" to 5000.0),
            safetyBufferPercent = 2.0
        )
        assertEquals(1, result.transfers.size)
        assertEquals("Транспорт", result.transfers.single().fromCategory)
        assertEquals("Кафе", result.transfers.single().toCategory)
        assertEquals(3000.0, result.transfers.single().amount, 0.001)
        assertEquals(7.0, result.updatedPercents["Транспорт"]!!, 0.001)
        assertEquals(13.0, result.updatedPercents["Кафе"]!!, 0.001)
    }

    @Test fun redistributionPrioritizesTwentyPercentDonorBeforeFifteenAndTen() {
        val categories = listOf(
            "Перерасход" to 10.0,
            "Запас15" to 20.0,
            "Запас20" to 20.0,
            "Запас10" to 20.0
        )
        val result = BudgetAllocationCalculator.redistribute(
            categories,
            mapOf(
                "Перерасход" to 150.0,
                "Запас15" to 170.0,
                "Запас20" to 160.0,
                "Запас10" to 180.0
            ),
            10.0
        )
        assertEquals("Запас20", result.transfers.first().fromCategory)
        assertEquals(20.0, result.transfers.first().donorFreePercent, 0.001)
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

    @Test fun confirmationIsEnabledOnlyForActiveUncompletedPaymentsDueBySelectedDate() {
        val month = YearMonth.of(2026, 11)
        val duePayment = RecurringPayment(title="Интернет", amount=500.0, category="Связь", dayOfMonth=10)
        val notYetDue = RecurringPayment(title="Аренда", amount=1000.0, category="Жильё", dayOfMonth=25)
        assertTrue(duePayment.canConfirm(month, LocalDate.of(2026, 11, 20)))
        assertFalse(notYetDue.canConfirm(month, LocalDate.of(2026, 11, 20)))
        assertFalse(duePayment.copy(active=false).canConfirm(month, LocalDate.of(2026, 11, 20)))
        assertFalse(duePayment.copy(completedMonths=setOf("2026-11")).canConfirm(month, LocalDate.of(2026, 11, 20)))
    }

    @Test fun confirmationUsesTheSelectedHistoricalMonth() {
        val payment = RecurringPayment(title="Интернет", amount=500.0, category="Связь", dayOfMonth=31)
        val february = YearMonth.of(2026, 2)
        assertTrue(payment.canConfirm(february, LocalDate.of(2026, 3, 1)))
        assertFalse(payment.copy(completedMonths=setOf("2026-02")).canConfirm(february, LocalDate.of(2026, 3, 1)))
    }
}


class RecurringPaymentReminderTest {
    @Test fun remindersAreScheduledOnlyOneDayBeforeAndOnDueDate() {
        val today = LocalDate.of(2026, 11, 24)
        val tomorrowPayment = RecurringPayment(title="Интернет", amount=500.0, category="Связь", dayOfMonth=25)
        val todayPayment = RecurringPayment(title="Аренда", amount=1000.0, category="Жильё", dayOfMonth=24)
        val laterPayment = RecurringPayment(title="Страховка", amount=2000.0, category="Прочее", dayOfMonth=27)

        val reminders = recurringPaymentRemindersFor(listOf(tomorrowPayment, todayPayment, laterPayment), today)

        assertEquals(2, reminders.size)
        assertTrue(reminders.any { it.payment.id == tomorrowPayment.id && it.isDayBefore })
        assertTrue(reminders.any { it.payment.id == todayPayment.id && !it.isDayBefore })
        assertFalse(reminders.any { it.payment.id == laterPayment.id })
    }

    @Test fun overdueInactiveAndCompletedPaymentsDoNotGenerateReminders() {
        val today = LocalDate.of(2026, 11, 24)
        val overdue = RecurringPayment(title="Старый платёж", amount=100.0, category="Прочее", dayOfMonth=20)
        val inactive = RecurringPayment(title="Пауза", amount=100.0, category="Прочее", dayOfMonth=25, active=false)
        val completedToday = RecurringPayment(
            title="Уже оплачен", amount=100.0, category="Прочее", dayOfMonth=24,
            completedMonths=setOf("2026-11")
        )

        assertTrue(recurringPaymentRemindersFor(listOf(overdue, inactive, completedToday), today).isEmpty())
    }

    @Test fun tomorrowReminderWorksAcrossMonthBoundaryAndShortMonths() {
        val today = LocalDate.of(2026, 2, 27)
        val payment = RecurringPayment(title="Платёж", amount=100.0, category="Прочее", dayOfMonth=28)
        val reminders = recurringPaymentRemindersFor(listOf(payment), today)

        assertEquals(1, reminders.size)
        assertEquals(LocalDate.of(2026, 2, 28), reminders.single().dueDate)
        assertTrue(reminders.single().isDayBefore)
    }

    @Test fun firstDayOfMonthPaymentIsRemindedOnPreviousMonthsLastDay() {
        val today = LocalDate.of(2026, 11, 30)
        val payment = RecurringPayment(title="Подписка", amount=100.0, category="Прочее", dayOfMonth=1)
        val reminder = recurringPaymentRemindersFor(listOf(payment), today).single()

        assertEquals(LocalDate.of(2026, 12, 1), reminder.dueDate)
        assertTrue(reminder.isDayBefore)
    }
}
