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
        // In reporting-period mode, the month is the sum of earned income
        // from the periods ending in that calendar month. Never extrapolate
        // a daily average to the calendar month's length.
        return selected.sumOf { it.earnedAmount.coerceAtLeast(0.0) }
    }

    fun carryoverBefore(
        currentPeriodStart: String,
        periodIncomes: List<PeriodIncome>,
        expenses: List<Expense>,
        firstStartDay: Int = 9,
        firstEndDay: Int = 23
    ): Double {
        val cutoff = java.time.LocalDate.parse(currentPeriodStart)
        val earliest = periodIncomes.mapNotNull { row ->
            runCatching { java.time.LocalDate.parse(row.start) }.getOrNull()
        }.minOrNull() ?: return 0.0
        val firstMonth = java.time.YearMonth.from(earliest)
        val lastMonth = java.time.YearMonth.from(cutoff)
        val monthCount = java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, lastMonth).coerceAtLeast(0L)
        val periods = (0L..monthCount).flatMap { offset ->
            val month = firstMonth.plusMonths(offset)
            runCatching { ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay) }
                .getOrNull()
                ?.let { listOf(it.first, it.second) }
                .orEmpty()
        }.filter { it.start >= earliest && it.end.isBefore(cutoff) }
            .distinctBy { it.start to it.end }
            .sortedBy { it.start }
        val incomeByPeriod = periodIncomes.associate { row ->
            (row.start to row.end) to row.amount.coerceAtLeast(0.0)
        }
        var balance = 0.0
        periods.forEach { period ->
            val amount = incomeByPeriod[period.start.toString() to period.end.toString()] ?: 0.0
            val spent = expenses.asSequence().filter { expense ->
                runCatching {
                    val date = java.time.LocalDate.parse(expense.date)
                    !date.isBefore(period.start) && !date.isAfter(period.end)
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
        val regular = currentShares.filterNot {
            it.first.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) ||
                it.first.equals("Перенос средств", true)
        }
        val relevantSpent = actualSpent.filterKeys { !it.equals("Перенос средств", true) }
        val total = relevantSpent.values.sumOf { it.coerceAtLeast(0.0) }
        if (regular.isEmpty()) return DynamicAllocationProposal(emptyMap(), total, false)
        if (total <= 0.0) return DynamicAllocationProposal(regular.toMap(), 0.0, false)
        val knownNames = regular.map { it.first }.toSet()
        val actualTotal = relevantSpent.filterKeys { it in knownNames }.values.sumOf { it.coerceAtLeast(0.0) }
        val spentShares = regular.associate { (name, _) ->
            name to ((relevantSpent[name] ?: 0.0).coerceAtLeast(0.0) / total * 100.0)
        }
        val actualNames = regular.filter { (relevantSpent[it.first] ?: 0.0) > 0.0 }
        val noSpend = regular.filter { (relevantSpent[it.first] ?: 0.0) <= 0.0 }
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
