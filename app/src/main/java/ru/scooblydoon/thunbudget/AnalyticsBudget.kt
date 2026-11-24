package ru.scooblydoon.thunbudget

import kotlin.math.max

internal data class CategoryBudgetMetrics(
    val remainingPercent: Double?,
    val requiredIncreasePercent: Double?
)

internal data class DynamicAllocationProposal(
    val percentages: Map<String, Double>,
    val totalActualSpend: Double,
    val basedOnActuals: Boolean
)

internal object AnalyticsBudgetCalculator {
    fun monthlyIncome(
        fixedMonthlyIncome: Double,
        periodIncomes: List<PeriodIncome>,
        month: java.time.YearMonth,
        useReportingPeriods: Boolean
    ): Double {
        if (!useReportingPeriods) return fixedMonthlyIncome.coerceAtLeast(0.0)
        val selected = periodIncomes.filter { row ->
            runCatching { java.time.YearMonth.from(java.time.LocalDate.parse(row.end)) == month }.getOrDefault(false)
        }
        val totalIncome = selected.sumOf { it.amount.coerceAtLeast(0.0) }
        val coveredDays = selected.sumOf { row ->
            runCatching {
                val start = java.time.LocalDate.parse(row.start)
                val end = java.time.LocalDate.parse(row.end)
                if (end.isBefore(start)) 0L else java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L
            }.getOrDefault(0L)
        }
        if (totalIncome <= 0.0 || coveredDays <= 0L) return 0.0
        return totalIncome / coveredDays.toDouble() * month.lengthOfMonth().toDouble()
    }


    fun carryoverBefore(currentPeriodStart: String, periodIncomes: List<PeriodIncome>, expenses: List<Expense>): Double {
        val cutoff = java.time.LocalDate.parse(currentPeriodStart)
        val completed = periodIncomes.mapNotNull { row ->
            runCatching {
                val start = java.time.LocalDate.parse(row.start)
                val end = java.time.LocalDate.parse(row.end)
                if (end.isBefore(cutoff) && !end.isBefore(start)) Triple(start, end, row.amount.coerceAtLeast(0.0)) else null
            }.getOrNull()
        }.sortedBy { it.first }
        var balance = 0.0
        completed.forEach { (start, end, amount) ->
            val spent = expenses.asSequence().filter { expense ->
                runCatching {
                    val date = java.time.LocalDate.parse(expense.date)
                    !date.isBefore(start) && !date.isAfter(end)
                }.getOrDefault(false)
            }.sumOf { it.amount.coerceAtLeast(0.0) }
            balance = (balance + amount - spent).coerceAtLeast(0.0)
        }
        return balance
    }

    fun metrics(plannedBudget: Double, actualSpent: Double): CategoryBudgetMetrics {
        if (plannedBudget <= 0.0) return CategoryBudgetMetrics(null, null)
        val remaining = (plannedBudget - actualSpent) / plannedBudget * 100.0
        val increase = if (actualSpent > plannedBudget) (actualSpent / plannedBudget - 1.0) * 100.0 else 0.0
        return CategoryBudgetMetrics(remaining, increase)
    }

    /**
     * Recalculates category shares from actual spending in the currently selected Overview period.
     * Categories without actuals keep their current relative shares from the unspent budget.
     */
    fun dynamicProposal(
        currentShares: List<Pair<String, Double>>,
        actualSpent: Map<String, Double>
    ): DynamicAllocationProposal {
        val regular = currentShares.filterNot { it.first.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) }
        val total = actualSpent.values.sumOf { it.coerceAtLeast(0.0) }
        if (regular.isEmpty()) return DynamicAllocationProposal(emptyMap(), total, false)
        if (total <= 0.0) return DynamicAllocationProposal(regular.toMap(), 0.0, false)
        val knownNames = regular.map { it.first }.toSet()
        val actualTotal = actualSpent.filterKeys { it in knownNames }.values.sumOf { it.coerceAtLeast(0.0) }
        val spentShares = regular.associate { (name, _) ->
            name to ((actualSpent[name] ?: 0.0).coerceAtLeast(0.0) / total * 100.0)
        }
        val actualNames = regular.filter { (actualSpent[it.first] ?: 0.0) > 0.0 }
        val noSpend = regular.filter { (actualSpent[it.first] ?: 0.0) <= 0.0 }
        val remainder = (100.0 - actualNames.sumOf { spentShares[it.first] ?: 0.0 }).coerceAtLeast(0.0)
        val noSpendCurrent = noSpend.sumOf { it.second.coerceAtLeast(0.0) }
        val result = spentShares.toMutableMap()
        noSpend.forEach { (name, current) ->
            result[name] = if (noSpendCurrent > 0.0) remainder * current.coerceAtLeast(0.0) / noSpendCurrent
                else remainder / noSpend.size.coerceAtLeast(1)
        }
        // Keep shares normalized when some expense categories are not configured in the budget.
        val sum = result.values.sum()
        if (sum > 0.0) result.keys.toList().forEach { result[it] = (result[it] ?: 0.0) * 100.0 / sum }
        return DynamicAllocationProposal(result, total, actualTotal > 0.0)
    }
}
