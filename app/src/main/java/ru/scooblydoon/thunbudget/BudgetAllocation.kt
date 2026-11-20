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

    fun windowFor(asOf: LocalDate, expenses: List<AllocationExpense> = emptyList()): AllocationWindow {
        val lastCompletedMonth = YearMonth.from(asOf).minusMonths(1)
        val availableMonths = expenses.asSequence()
            .map { YearMonth.from(it.date) }
            .filter { !it.isAfter(lastCompletedMonth) }
            .distinct()
            .sorted()
            .toList()
        val firstMonth = when {
            availableMonths.isEmpty() -> lastCompletedMonth
            availableMonths.size >= 6 -> lastCompletedMonth.minusMonths(5)
            else -> availableMonths.first()
        }
        return AllocationWindow(firstMonth.atDay(1), lastCompletedMonth.atEndOfMonth())
    }

    fun suggest(
        categories: List<String>,
        expenses: List<AllocationExpense>,
        asOf: LocalDate
    ): List<AllocationSuggestion> {
        val window = windowFor(asOf, expenses)
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

    fun freePercent(cap: Double, spent: Double): Double? =
        if (cap > 0.0) (cap - spent) / cap * 100.0 else null

    data class Transfer(val fromCategory: String, val toCategory: String, val amount: Double, val donorFreePercent: Double)
    data class Redistribution(val transfers: List<Transfer>, val updatedPercents: Map<String, Double>) {
        val totalTransferred: Double get() = transfers.sumOf { it.amount }
    }

    /**
     * Covers overspent categories from donors with the largest relative remaining balance.
     * Donor priority is >=20%, then >=15%, then >=10%; <=5% and the 6–9% gap are not used.
     */
    fun redistribute(
        categories: List<Pair<String, Double>>,
        spentByCategory: Map<String, Double>,
        budgetPerPercent: Double
    ): Redistribution {
        if (budgetPerPercent <= 0.0) return Redistribution(emptyList(), categories.toMap())
        val updated = categories.toMap().toMutableMap()
        val caps = categories.associate { it.first to it.second * budgetPerPercent }
        val remaining = caps.mapValues { (name, cap) -> cap - (spentByCategory[name] ?: 0.0) }.toMutableMap()
        val deficits = categories.mapNotNull { (name, _) ->
            val deficit = -(remaining[name] ?: 0.0)
            if (deficit > 0.0001 && !name.equals(RESERVE_CATEGORY, true)) name to deficit else null
        }.sortedByDescending { it.second }
        val donors = categories.mapNotNull { (name, _) ->
            val cap = caps[name] ?: 0.0
            val spare = remaining[name] ?: 0.0
            val free = freePercent(cap, spentByCategory[name] ?: 0.0)
            if (cap > 0.0 && spare > 0.0 && free != null && free >= 10.0 &&
                !name.equals(RESERVE_CATEGORY, true) && !name.equals("Накопления", true)
            ) Triple(name, spare, free) else null
        }.sortedWith(compareByDescending<Triple<String, Double, Double>> { donorTier(it.third) }.thenByDescending { it.third }.thenByDescending { it.second })
        val transfers = mutableListOf<Transfer>()
        val donorRemaining = donors.associate { it.first to it.second }.toMutableMap()
        deficits.forEach { (target, deficit) ->
            var uncovered = deficit
            donors.forEach { donor ->
                if (uncovered <= 0.0001 || donor.first == target) return@forEach
                val available = donorRemaining[donor.first] ?: 0.0
                if (available <= 0.0) return@forEach
                val amount = minOf(uncovered, available)
                transfers += Transfer(donor.first, target, amount, donor.third)
                donorRemaining[donor.first] = available - amount
                uncovered -= amount
            }
        }
        transfers.forEach { transfer ->
            val points = transfer.amount / budgetPerPercent
            updated[transfer.toCategory] = (updated[transfer.toCategory] ?: 0.0) + points
            updated[transfer.fromCategory] = ((updated[transfer.fromCategory] ?: 0.0) - points).coerceAtLeast(0.0)
        }
        return Redistribution(transfers, updated)
    }

    private fun donorTier(freePercent: Double): Int = when {
        freePercent >= 20.0 -> 3
        freePercent >= 15.0 -> 2
        freePercent >= 10.0 -> 1
        else -> 0
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
