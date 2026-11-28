package ru.scooblydoon.thunbudget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRedistributionForecastTest {
    private val start = LocalDate.parse("2026-11-09")
    private val end = LocalDate.parse("2026-11-23")
    private val today = LocalDate.parse("2026-11-16")

    private fun category(name: String, protected: Boolean? = null) =
        BudgetCategory(name, 10.0, protected)

    private fun forecast(
        name: String,
        central: Double = 0.0,
        upper: Double = central,
        history: Boolean = true
    ) = CategoryForecast(
        category = name,
        averageMonthlySpend = 0.0,
        forecastForNextMonth = 0.0,
        forecastForPeriod = central,
        historyPeriods = if (history) 4 else 0,
        hasHistory = history,
        averagePeriodSpend = 0.0,
        forecastHighForPeriod = upper,
        positiveHistoryPeriods = if (history) 3 else 0
    )

    @Test
    fun actualAndFutureExpensesAreSeparatedAtTodayBoundary() {
        val expenses = listOf(
            Expense(1, "Покупка", "Продукты", 100.0, "2026-11-16"),
            Expense(2, "Будущее", "Продукты", 50.0, "2026-11-17"),
            Expense(3, "После периода", "Продукты", 99.0, "2026-11-24")
        )
        val input = BudgetRedistributionForecastAssembler.buildInputs(
            start, end, today, listOf(category("Продукты")), expenses, emptyList(),
            listOf(forecast("Продукты", 20.0, 30.0)), mapOf("Продукты" to 500.0)
        ).single()

        assertEquals(100.0, input.actualToDate, 0.001)
        assertEquals(50.0, input.knownFutureExpenses, 0.001)
        assertEquals(20.0, input.variableForecast, 0.001)
        assertEquals(30.0, input.variableForecastUpper, 0.001)
    }

    @Test
    fun futureExpenseMatchingRecurringCommitmentIsCountedOnlyOnce() {
        val payment = RecurringPayment(
            id = "rent", title = "Коммуналка", amount = 500.0,
            category = "Жильё", dayOfMonth = 20
        )
        val futureExpense = Expense(1, "Оплата ЖКХ", "жильё", 500.0, "2026-11-20")
        val unmatched = RecurringPayment(
            id = "insurance", title = "Страховка", amount = 200.0,
            category = "Жильё", dayOfMonth = 22
        )

        val commitments = RecurringPaymentForecastCalculator.unpaidCommitments(
            listOf(payment, unmatched), listOf(futureExpense), start, end, today
        )

        assertEquals(200.0, commitments["Жильё"]!!, 0.001)
    }

    @Test
    fun matchingIsOneToOneForDuplicateRecurringSchedules() {
        val payments = listOf(
            RecurringPayment(id = "p1", title = "Первый", amount = 500.0, category = "Жильё", dayOfMonth = 20),
            RecurringPayment(id = "p2", title = "Второй", amount = 500.0, category = "Жильё", dayOfMonth = 20)
        )
        val expenses = listOf(Expense(1, "Уже внесено", "Жильё", 500.0, "2026-11-20"))

        val commitments = RecurringPaymentForecastCalculator.unpaidCommitments(
            payments, expenses, start, end, today
        )

        assertEquals(500.0, commitments["Жильё"]!!, 0.001)
    }

    @Test
    fun completedAndInactivePaymentsAreExcluded() {
        val completed = RecurringPayment(
            id = "done", title = "Свет", amount = 100.0, category = "ЖКХ",
            dayOfMonth = 18, completedMonths = setOf("2026-11")
        )
        val inactive = RecurringPayment(
            id = "off", title = "Интернет", amount = 200.0, category = "ЖКХ",
            dayOfMonth = 19, active = false
        )
        val commitments = RecurringPaymentForecastCalculator.unpaidCommitments(
            listOf(completed, inactive), emptyList(), start, end, today
        )
        assertTrue(commitments.isEmpty())
    }

    @Test
    fun futureVariableForecastIsUsedForRecipientAndUpperForecastForDonor() {
        val inputs = BudgetRedistributionForecastAssembler.buildInputs(
            start, end, today, listOf(category("Продукты")),
            emptyList(), emptyList(), listOf(forecast("Продукты", 75.0, 120.0)),
            mapOf("Продукты" to 400.0)
        ).single()

        assertEquals(75.0, inputs.variableForecast, 0.001)
        assertEquals(120.0, inputs.variableForecastUpper, 0.001)
    }

    @Test
    fun missingForecastHistoryDoesNotBecomeDonorEvidence() {
        val inputs = BudgetRedistributionForecastAssembler.buildInputs(
            start, end, today, listOf(category("Новая категория")),
            emptyList(), emptyList(), listOf(forecast("Новая категория", 0.0, history = false)),
            mapOf("Новая категория" to 1_000.0)
        ).single()

        assertFalse(inputs.hasEvidence)
    }


    @Test
    fun scheduledBillIsIncludedAsAnUnpaidCommitmentForItsCurrentPeriod() {
        val payment = RecurringPayment(
            id = "rent", title = "Аренда", amount = 1_500.0,
            category = "Жильё и ЖКХ", dayOfMonth = 20
        )
        val inputs = BudgetRedistributionForecastAssembler.buildInputs(
            start, end, today,
            listOf(category("Транспорт"), category("Жильё и ЖКХ")),
            emptyList(), listOf(payment),
            listOf(forecast("Транспорт"), forecast("Жильё и ЖКХ")),
            mapOf("Транспорт" to 100.0, "Жильё и ЖКХ" to 2_000.0)
        )
        val rent = inputs.first { it.name == "Жильё и ЖКХ" }
        assertEquals(1_500.0, rent.unpaidRecurringPayments, 0.001)
        assertEquals(0.0, rent.actualToDate, 0.001)
    }

    @Test
    fun pastReportingPeriodHasNoFutureForecastWhenForecastRangeIsEmpty() {
        val pastEnd = LocalDate.parse("2026-11-08")
        val inputs = BudgetRedistributionForecastAssembler.buildInputs(
            start.minusDays(20), pastEnd, today, listOf(category("Продукты")),
            listOf(Expense(1, "Факт", "Продукты", 100.0, "2026-11-07")),
            emptyList(), listOf(forecast("Продукты", 50.0, 70.0)),
            mapOf("Продукты" to 300.0)
        ).single()

        assertEquals(100.0, inputs.actualToDate, 0.001)
        assertEquals(0.0, inputs.knownFutureExpenses, 0.001)
        assertEquals(0.0, inputs.variableForecast, 0.001)
        assertEquals(0.0, inputs.variableForecastUpper, 0.001)
    }
}
