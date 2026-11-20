package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth

/**
 * A six-month rolling allocation proposal. The current month is deliberately excluded:
 * in December the sample is June 1 through November 30.
 */
data class AllocationExpense(val category: String, val amount: Double, val date: LocalDate)

data class AllocationSuggestion(
    val category: String,
    val suggestedPercent: Double?,
    val hasExpenseHistory: Boolean,
    val totalSpent: Double
)

data class AllocationWindow(val from: LocalDate, val to: LocalDate)

object BudgetAllocationCalculator {
    const val RESERVE_CATEGORY = "Резервные средства"

    fun windowFor(asOf: LocalDate): AllocationWindow {
        val currentMonth = YearMonth.from(asOf)
        val lastCompletedMonth = currentMonth.minusMonths(1)
        val firstMonth = lastCompletedMonth.minusMonths(5)
        return AllocationWindow(firstMonth.atDay(1), lastCompletedMonth.atEndOfMonth())
    }

    fun suggest(
        categories: List<String>,
        expenses: List<AllocationExpense>,
        asOf: LocalDate
    ): List<AllocationSuggestion> {
        val window = windowFor(asOf)
        val knownCategories = categories.filterNot { it.equals(RESERVE_CATEGORY, ignoreCase = true) }
        val totals = knownCategories.associateWith { category ->
            expenses.asSequence()
                .filter { it.category.equals(category, ignoreCase = true) }
                .filter { !it.date.isBefore(window.from) && !it.date.isAfter(window.to) }
                .sumOf { it.amount.coerceAtLeast(0.0) }
        }
        val grandTotal = totals.values.sum()
        return knownCategories.map { category ->
            val spent = totals[category] ?: 0.0
            AllocationSuggestion(
                category = category,
                suggestedPercent = if (spent > 0.0 && grandTotal > 0.0) spent / grandTotal * 100.0 else null,
                hasExpenseHistory = spent > 0.0,
                totalSpent = spent
            )
        }
    }

    /** The reserve always absorbs the unallocated remainder and is hidden at exactly 100%. */
    fun reservePercent(categoryPercents: List<Double>): Double =
        (100.0 - categoryPercents.sum()).coerceIn(0.0, 100.0)

    fun withReserve(categoryPercents: List<Pair<String, Double>>): List<Pair<String, Double>> {
        val regular = categoryPercents.filterNot { it.first.equals(RESERVE_CATEGORY, ignoreCase = true) }
        val reserve = reservePercent(regular.map { it.second })
        return if (reserve > 0.0001) regular + (RESERVE_CATEGORY to reserve) else regular
    }
}
