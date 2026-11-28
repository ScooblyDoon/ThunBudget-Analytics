package ru.scooblydoon.thunbudget

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

object RecurringPaymentForecastCalculator {
    /**
     * Returns unpaid recurring commitments for occurrences inside the exact
     * reporting period. A recorded expense suppresses at most one matching
     * occurrence, matched by normalized category, due date and kopeck amount.
     */
    fun unpaidCommitments(
        payments: List<RecurringPayment>,
        expenses: List<Expense>,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        today: LocalDate
    ): Map<String, Double> {
        require(!periodEnd.isBefore(periodStart))
        val parsedExpenses = expenses.mapIndexedNotNull { index, row ->
            val date = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return@mapIndexedNotNull null
            if (date.isBefore(periodStart) || date.isAfter(periodEnd) ||
                row.amount <= 0.0 || !row.amount.isFinite() ||
                row.category.equals("Перенос средств", ignoreCase = true)
            ) null else ParsedExpense(index, normalize(row.category), date, cents(row.amount))
        }
        val consumedExpenseIndexes = mutableSetOf<Int>()
        val totals = linkedMapOf<String, Long>()

        payments.asSequence()
            .filter { it.active }
            .sortedWith(compareBy<RecurringPayment> { normalize(it.category) }.thenBy { it.id })
            .forEach { payment ->
                var month = YearMonth.from(periodStart)
                val lastMonth = YearMonth.from(periodEnd)
                while (!month.isAfter(lastMonth)) {
                    val dueDate = payment.dueDate(month)
                    if (!dueDate.isBefore(periodStart) &&
                        !dueDate.isAfter(periodEnd) &&
                        !payment.isCompleted(month)
                    ) {
                        val match = parsedExpenses.firstOrNull { expense ->
                            expense.index !in consumedExpenseIndexes &&
                                expense.category == normalize(payment.category) &&
                                expense.date == dueDate &&
                                expense.amountCents == cents(payment.amount)
                        }
                        if (match != null) {
                            consumedExpenseIndexes += match.index
                        } else {
                            val key = payment.category
                            totals[key] = (totals[key] ?: 0L) + cents(payment.amount)
                        }
                    }
                    month = month.plusMonths(1)
                }
            }
        return totals.mapValues { (_, amount) -> amount / 100.0 }
    }

    private data class ParsedExpense(
        val index: Int,
        val category: String,
        val date: LocalDate,
        val amountCents: Long
    )

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace('ё', 'е')

    private fun cents(value: Double): Long =
        BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
            .movePointRight(2).longValueExact()
}

object BudgetRedistributionForecastAssembler {
    fun buildInputs(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        today: LocalDate,
        categories: List<BudgetCategory>,
        expenses: List<Expense>,
        recurringPayments: List<RecurringPayment>,
        forecasts: List<CategoryForecast>,
        plannedAmounts: Map<String, Double>,
        includeStatisticalForecast: Boolean = true
    ): List<RedistributionCategoryInput> {
        require(!periodEnd.isBefore(periodStart))
        val actualEnd = minOf(today, periodEnd)
        val commitments = RecurringPaymentForecastCalculator.unpaidCommitments(
            payments = recurringPayments,
            expenses = expenses,
            periodStart = periodStart,
            periodEnd = periodEnd,
            today = today
        )
        val forecastByCategory = forecasts.associateBy { normalize(it.category) }

        return categories
            .filterNot { it.name.equals("Перенос средств", ignoreCase = true) }
            .map { category ->
                val normalizedCategory = normalize(category.name)
                val actual = expenses.asSequence()
                    .filter { it.category.equals(category.name, ignoreCase = true) }
                    .mapNotNull { row ->
                        val date = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return@mapNotNull null
                        row.takeIf {
                            !date.isBefore(periodStart) && !date.isAfter(actualEnd) &&
                                !it.category.equals("Перенос средств", ignoreCase = true)
                        }
                    }
                    .sumOf { it.amount.coerceAtLeast(0.0) }
                val future = expenses.asSequence()
                    .filter { row ->
                        row.category.equals(category.name, ignoreCase = true) &&
                            !row.category.equals("Перенос средств", ignoreCase = true)
                    }
                    .mapNotNull { row ->
                        val date = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return@mapNotNull null
                        row.takeIf {
                            date.isAfter(today) && !date.isBefore(periodStart) && !date.isAfter(periodEnd)
                        }
                    }
                    .sumOf { it.amount.coerceAtLeast(0.0) }
                val forecast = forecastByCategory[normalizedCategory]
                val defaultProtected = BudgetRedistributionProtectionDefaults
                    .isProtectedByDefault(category.name)
                val explicitlyOrByDefaultProtected =
                    (category.protectedFromRedistribution ?: defaultProtected) ||
                        normalizedCategory.contains("накоп")
                RedistributionCategoryInput(
                    name = category.name,
                    plannedAmount = plannedAmounts.entries.firstOrNull {
                        normalize(it.key) == normalizedCategory
                    }?.value ?: 0.0,
                    actualToDate = actual,
                    knownFutureExpenses = future,
                    unpaidRecurringPayments = commitments.entries.firstOrNull {
                        normalize(it.key) == normalizedCategory
                    }?.value ?: 0.0,
                    // Statistical forecast remains available to Analytics, but is
                    // optional for redistribution. The compensation engine can use
                    // actual spending and known commitments only.
                    variableForecast = if (includeStatisticalForecast && periodEnd.isAfter(today)) {
                        forecast?.forecastForPeriod?.coerceAtLeast(0.0) ?: 0.0
                    } else 0.0,
                    variableForecastUpper = if (includeStatisticalForecast && periodEnd.isAfter(today)) {
                        forecast?.forecastHighForPeriod?.coerceAtLeast(0.0)
                            ?: forecast?.forecastForPeriod?.coerceAtLeast(0.0) ?: 0.0
                    } else 0.0,
                    hasEvidence = forecast?.hasHistory == true || (forecast?.positiveHistoryPeriods ?: 0) > 0,
                    protectedFromRedistribution = explicitlyOrByDefaultProtected
                )
            }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace('ё', 'е')
}
