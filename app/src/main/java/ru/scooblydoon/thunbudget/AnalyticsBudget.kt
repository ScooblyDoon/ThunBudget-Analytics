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

internal data class CalendarMonthIncomeEstimate(
    val enteredIncome: Double,
    val forecastIncome: Double,
    val coveredDays: Int,
    val uncoveredPastDays: Int,
    val forecastDays: Int,
    val uncoveredFutureDays: Int,
    val monthDays: Int,
    val averageDailyIncome: Double?
) {
    val totalIncome: Double get() = enteredIncome + forecastIncome
}

internal data class ReportingCycleIncomeEstimate(
    val firstStart: java.time.LocalDate,
    val firstEnd: java.time.LocalDate,
    val firstIncome: Double,
    val secondStart: java.time.LocalDate,
    val secondEnd: java.time.LocalDate,
    val secondIncome: Double
) {
    val totalIncome: Double get() = firstIncome + secondIncome
}

internal object AnalyticsBudgetCalculator {
    private data class IncomePeriod(
        val start: java.time.LocalDate,
        val end: java.time.LocalDate,
        val earnedAmount: Double
    ) {
        val dayCount: Long
            get() = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L
        val dailyRate: Double
            get() = earnedAmount / dayCount.toDouble()
    }

    /**
     * Allocates each entered reporting-period income evenly over its inclusive
     * calendar dates, then sums only the dates intersecting the selected month.
     * Missing days in the past are never backfilled. Only uncovered future days
     * may be forecast, using the median daily rate of up to six completed periods.
     */
    fun calendarMonthIncome(
        periodIncomes: List<PeriodIncome>,
        month: java.time.YearMonth,
        asOf: java.time.LocalDate,
        forecastLookbackPeriods: Int = 6
    ): CalendarMonthIncomeEstimate {
        require(forecastLookbackPeriods >= 1)
        val normalized = periodIncomes.mapNotNull { row ->
            val start = runCatching { java.time.LocalDate.parse(row.start) }.getOrNull() ?: return@mapNotNull null
            val end = runCatching { java.time.LocalDate.parse(row.end) }.getOrNull() ?: return@mapNotNull null
            val earned = row.earnedAmount
            if (end.isBefore(start) || !earned.isFinite() || earned <= 0.0) return@mapNotNull null
            IncomePeriod(start, end, earned)
        }.groupBy { it.start to it.end }
            .map { (range, rows) -> IncomePeriod(range.first, range.second, rows.sumOf { it.earnedAmount }) }
            .sortedWith(compareBy<IncomePeriod> { it.start }.thenBy { it.end })

        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val coveredDates = mutableSetOf<java.time.LocalDate>()
        var enteredIncome = 0.0

        normalized.forEach { period ->
            val from = maxOf(monthStart, period.start)
            val to = minOf(monthEnd, period.end)
            if (to.isBefore(from)) return@forEach
            var date = from
            while (!date.isAfter(to)) {
                // A calendar day can contribute only once, even if corrupted or
                // legacy data contains overlapping period definitions.
                if (coveredDates.add(date)) enteredIncome += period.dailyRate
                date = date.plusDays(1)
            }
        }

        val recentDailyRates = normalized
            .filter { !it.end.isAfter(asOf) && it.earnedAmount > 0.0 }
            .sortedBy { it.end }
            .takeLast(forecastLookbackPeriods)
            .map { it.dailyRate }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
        // Require at least two completed periods so a single unusual payment
        // does not become the forecast baseline.
        val averageDailyIncome = if (recentDailyRates.size >= 2) {
            val middle = recentDailyRates.size / 2
            if (recentDailyRates.size % 2 == 0) {
                (recentDailyRates[middle - 1] + recentDailyRates[middle]) / 2.0
            } else recentDailyRates[middle]
        } else null

        var uncoveredPastDays = 0
        var uncoveredFutureDays = 0
        var date = monthStart
        while (!date.isAfter(monthEnd)) {
            if (date !in coveredDates) {
                if (date.isAfter(asOf)) uncoveredFutureDays++ else uncoveredPastDays++
            }
            date = date.plusDays(1)
        }
        val forecastDays = if (averageDailyIncome != null) uncoveredFutureDays else 0
        val forecastIncome = (averageDailyIncome ?: 0.0) * forecastDays
        return CalendarMonthIncomeEstimate(
            enteredIncome = enteredIncome,
            forecastIncome = forecastIncome,
            coveredDays = coveredDates.size,
            uncoveredPastDays = uncoveredPastDays,
            forecastDays = forecastDays,
            uncoveredFutureDays = uncoveredFutureDays,
            monthDays = month.lengthOfMonth(),
            averageDailyIncome = averageDailyIncome
        )
    }

    /**
     * Returns the unprorated earned income for the two reporting periods in the
     * selected reporting cycle. Carryover is excluded from earned income.
     */
    fun reportingCycleIncome(
        periodIncomes: List<PeriodIncome>,
        month: java.time.YearMonth,
        firstStartDay: Int,
        firstEndDay: Int
    ): ReportingCycleIncomeEstimate {
        val proposal = ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay)
        fun incomeFor(start: java.time.LocalDate, end: java.time.LocalDate): Double =
            periodIncomes.asSequence()
                .filter { it.start == start.toString() && it.end == end.toString() }
                .sumOf { if (it.earnedAmount.isFinite()) it.earnedAmount.coerceAtLeast(0.0) else 0.0 }
        return ReportingCycleIncomeEstimate(
            firstStart = proposal.first.start,
            firstEnd = proposal.first.end,
            firstIncome = incomeFor(proposal.first.start, proposal.first.end),
            secondStart = proposal.second.start,
            secondEnd = proposal.second.end,
            secondIncome = incomeFor(proposal.second.start, proposal.second.end)
        )
    }

    fun monthlyIncome(
        fixedMonthlyIncome: Double,
        periodIncomes: List<PeriodIncome>,
        month: java.time.YearMonth,
        useReportingPeriods: Boolean,
        asOf: java.time.LocalDate = java.time.LocalDate.now()
    ): Double {
        if (!useReportingPeriods) return fixedMonthlyIncome.coerceAtLeast(0.0)
        return calendarMonthIncome(periodIncomes, month, asOf).totalIncome
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
