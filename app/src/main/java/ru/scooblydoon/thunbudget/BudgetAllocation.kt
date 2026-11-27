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
            availableMonths.isEmpty() -> lastCompletedMonth.minusMonths(5)
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
        val consumptionExpenses = expenses.filterNot { it.category.equals("Перенос средств", ignoreCase = true) }
        val window = windowFor(asOf, consumptionExpenses)
        val knownCategories = categories.filterNot { it.equals(RESERVE_CATEGORY, ignoreCase = true) || it.equals("Перенос средств", ignoreCase = true) }
        val totals = knownCategories.associateWith { category ->
            consumptionExpenses.asSequence()
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

    data class Deficit(
        val category: String,
        val actualSpend: Double,
        val projectedSpend: Double,
        val plannedAmount: Double,
        val amount: Double
    )
    data class Transfer(val fromCategory: String, val toCategory: String, val amount: Double, val donorFreePercent: Double)
    data class Redistribution(
        val transfers: List<Transfer>,
        val updatedPercents: Map<String, Double>,
        val deficits: List<Deficit> = emptyList(),
        val uncoveredByCategory: Map<String, Double> = emptyMap()
    ) {
        val totalTransferred: Double get() = transfers.sumOf { it.amount }
        val totalUncovered: Double get() = uncoveredByCategory.values.sumOf { it.coerceAtLeast(0.0) }
    }

    /**
     * Covers actual overspending from reserve categories first, then from categories
     * whose forecast remains safely below their planned limit.
     *
     * forecastSpentByCategory is the expected total spend for the selected period
     * (actual spend to date plus the forecast for its remaining days). Ordinary
     * donors keep a safety margin of [safetyBufferPercent] percentage points.
     */
    fun redistribute(
        categories: List<Pair<String, Double>>,
        spentByCategory: Map<String, Double>,
        budgetPerPercent: Double,
        forecastSpentByCategory: Map<String, Double> = emptyMap(),
        safetyBufferPercent: Double = 2.0
    ): Redistribution {
        if (!budgetPerPercent.isFinite() || budgetPerPercent < 0.0) {
            return Redistribution(emptyList(), categories.toMap())
        }

        // "Перенос средств" is a technical period-closing entry, never a
        // spending category, deficit, reserve or donor in redistribution.
        val budgetCategories = categories.filterNot {
            it.first.equals("Перенос средств", ignoreCase = true)
        }

        fun isReserve(name: String): Boolean =
            name.equals(RESERVE_CATEGORY, ignoreCase = true) ||
                name.equals("Непредвиденные расходы", ignoreCase = true)

        val updated = categories.toMap().toMutableMap()
        val caps = budgetCategories.associate { it.first to it.second.coerceAtLeast(0.0) * budgetPerPercent }
        val actual = budgetCategories.associate { (name, _) -> name to (spentByCategory[name] ?: 0.0).coerceAtLeast(0.0) }
        val projected = budgetCategories.associate { (name, _) ->
            name to maxOf(actual[name] ?: 0.0, (forecastSpentByCategory[name] ?: actual[name] ?: 0.0).coerceAtLeast(0.0))
        }

        // Cover both an existing overspend and a predicted end-of-period overspend.
        // The current fact is retained for the proposal UI; the transfer amount is
        // based on the forecasted total spend, not only the amount already spent.
        val deficits = budgetCategories.mapNotNull { (name, _) ->
            val actualSpend = actual[name] ?: 0.0
            val projectedSpend = projected[name] ?: actualSpend
            val plannedAmount = caps[name] ?: 0.0
            val deficit = projectedSpend - plannedAmount
            if (deficit > 0.0001 && !isReserve(name)) {
                Deficit(name, actualSpend, projectedSpend, plannedAmount, deficit)
            } else {
                null
            }
        }.sortedByDescending { it.amount }

        val bufferAmount = safetyBufferPercent.coerceAtLeast(0.0) * budgetPerPercent
        val donors = budgetCategories.mapNotNull { (name, _) ->
            val cap = caps[name] ?: 0.0
            val projectedSpend = projected[name] ?: (actual[name] ?: 0.0)
            val effectiveFreePercent = freePercent(cap, projectedSpend)
            val reserve = isReserve(name)
            val available = if (reserve) {
                // Even reserve categories must retain money already expected to be spent.
                (cap - projectedSpend).coerceAtLeast(0.0)
            } else {
                (cap - projectedSpend - bufferAmount).coerceAtLeast(0.0)
            }
            val eligible = cap > 0.0 && available > 0.0001 &&
                (reserve || (effectiveFreePercent != null && effectiveFreePercent > 5.0)) &&
                !name.equals("Накопления", ignoreCase = true)
            if (eligible) Triple(name, available, effectiveFreePercent ?: 100.0) else null
        }.sortedWith(
            compareByDescending<Triple<String, Double, Double>> { isReserve(it.first) }
                .thenByDescending { if (isReserve(it.first)) Int.MAX_VALUE else donorTier(it.third) }
                .thenByDescending { it.third }
                .thenByDescending { it.second }
        )

        val transfers = mutableListOf<Transfer>()
        val uncoveredByCategory = mutableMapOf<String, Double>()
        val donorRemaining = donors.associate { it.first to it.second }.toMutableMap()
        deficits.forEach { deficit ->
            var uncovered = deficit.amount
            donors.forEach donorLoop@ { donor ->
                if (uncovered <= 0.0001 || donor.first == deficit.category) return@donorLoop
                val available = donorRemaining[donor.first] ?: 0.0
                if (available <= 0.0) return@donorLoop
                val amount = minOf(uncovered, available)
                transfers += Transfer(donor.first, deficit.category, amount, donor.third)
                donorRemaining[donor.first] = available - amount
                uncovered -= amount
            }
            if (uncovered > 0.0001) uncoveredByCategory[deficit.category] = uncovered
        }

        if (budgetPerPercent > 0.0) {
            transfers.forEach { transfer ->
                val points = transfer.amount / budgetPerPercent
                updated[transfer.toCategory] = (updated[transfer.toCategory] ?: 0.0) + points
                updated[transfer.fromCategory] = ((updated[transfer.fromCategory] ?: 0.0) - points).coerceAtLeast(0.0)
            }
        }
        return Redistribution(transfers, updated, deficits, uncoveredByCategory)
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
        val regular = categoryPercents
            .filterNot {
                it.first.equals(RESERVE_CATEGORY, ignoreCase = true) ||
                    it.first.equals("Перенос средств", ignoreCase = true)
            }
            .map { (name, percent) ->
                name to (percent.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0)
            }

        val rawTotal = regular.sumOf { it.second }
        val normalized = if (rawTotal > 100.0) {
            regular.map { (name, percent) -> name to (percent * 100.0 / rawTotal) }
        } else {
            regular
        }
        val reserve = (100.0 - normalized.sumOf { it.second }).coerceAtLeast(0.0)
        val result = if (reserve > 0.0001) normalized + (RESERVE_CATEGORY to reserve) else normalized

        return if (categoryPercents.any { it.first.equals("Перенос средств", ignoreCase = true) }) {
            result + ("Перенос средств" to 0.0)
        } else {
            result
        }
    }
}
