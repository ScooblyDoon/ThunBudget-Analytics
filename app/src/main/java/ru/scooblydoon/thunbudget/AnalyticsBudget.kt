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
    val medianDailyIncome: Double?
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

internal data class ReportingCycleExpenseRange(
    val start: java.time.LocalDate,
    val end: java.time.LocalDate,
    val visibleEnd: java.time.LocalDate
) {
    val elapsedDays: Int
        get() = if (visibleEnd.isBefore(start)) 0
        else (java.time.temporal.ChronoUnit.DAYS.between(start, visibleEnd) + 1L).toInt()
}


internal data class ReportingCycleCategoryPlan(
    val averagePercentages: Map<String, Double>,
    val firstPeriodPercentages: Map<String, Double>,
    val secondPeriodPercentages: Map<String, Double>
)

/**
 * Uses the percentages that were actually assigned as the category plan in
 * each reporting period. Actual spending is deliberately not used to derive
 * a plan percentage. Missing historical snapshots fall back to the current
 * category setup, which is the safest migration path for older app versions.
 */
internal object ReportingPeriodCategoryPlanCalculator {
    private const val TRANSFER_CATEGORY = "Перенос средств"

    /**
     * Returns the percentages assigned to one exact reporting period.
     * A saved period snapshot takes precedence; missing category entries use
     * the supplied baseline shares.
     */
    private fun resolvePlan(
        currentShares: List<Pair<String, Double>>,
        stored: Map<String, Double>
    ): Map<String, Double> {
        val resolved = currentShares.map { (name, current) ->
            val storedValue = stored.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            val value = storedValue?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: current
            name to value
        }
        return BudgetAllocationCalculator.withReserve(resolved).associate { (name, percent) ->
            name to if (name.equals(TRANSFER_CATEGORY, ignoreCase = true)) 0.0 else percent
        }
    }

    fun periodPlan(
        currentShares: List<Pair<String, Double>>,
        savedPlans: Map<Pair<String, String>, Map<String, Double>>,
        start: java.time.LocalDate,
        end: java.time.LocalDate
    ): Map<String, Double> {
        val stored = savedPlans[start.toString() to end.toString()].orEmpty()
        return resolvePlan(currentShares, stored)
    }

    fun cyclePlan(
        currentShares: List<Pair<String, Double>>,
        savedPlans: Map<Pair<String, String>, Map<String, Double>>,
        firstStart: java.time.LocalDate,
        firstEnd: java.time.LocalDate,
        secondStart: java.time.LocalDate,
        secondEnd: java.time.LocalDate,
        firstIncome: Double,
        secondIncome: Double
    ): ReportingCycleCategoryPlan {
        val first = periodPlan(currentShares, savedPlans, firstStart, firstEnd)
        val second = periodPlan(currentShares, savedPlans, secondStart, secondEnd)
        val firstIncomeSafe = firstIncome.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val secondIncomeSafe = secondIncome.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val totalIncome = firstIncomeSafe + secondIncomeSafe
        val planNames = (first.keys + second.keys).distinct()
        val average = planNames.associateWith { name ->
            val fallback = currentShares.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second ?: 0.0
            val firstValue = first[name] ?: fallback
            val secondValue = second[name] ?: fallback
            val weightedPercent = if (totalIncome > 0.0) {
                (firstIncomeSafe * firstValue + secondIncomeSafe * secondValue) / totalIncome
            } else {
                (firstValue + secondValue) / 2.0
            }
            if (name.equals(TRANSFER_CATEGORY, true)) 0.0
            else weightedPercent.coerceIn(0.0, 100.0)
        }
        return ReportingCycleCategoryPlan(
            averagePercentages = average,
            firstPeriodPercentages = first,
            secondPeriodPercentages = second
        )
    }

    /**
     * Calendar-month category budget: each known income period contributes
     * only its calendar-day overlap with the selected month, multiplied by
     * the saved plan percentage for that exact period. Missing future days
     * may receive the separate income forecast at the average available plan
     * percentage; missing past days are never filled.
     */
    private data class MonthlyPeriodIncomeInput(
        val start: java.time.LocalDate,
        val end: java.time.LocalDate,
        val regularAmount: Double,
        val additionalAmount: Double
    ) {
        val dayCount: Long
            get() = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L
    }

    fun monthlyPlan(
        currentShares: List<Pair<String, Double>>,
        periodIncomes: List<PeriodIncome>,
        savedPlans: Map<Pair<String, String>, Map<String, Double>>,
        month: java.time.YearMonth,
        forecastIncome: Double = 0.0,
        firstStartDay: Int = 9,
        firstEndDay: Int = 23,
        incomeEntries: List<IncomeEntry> = emptyList()
    ): Map<String, Double> {
        val basePlan = resolvePlan(currentShares, emptyMap())
        val result = basePlan.keys.associateWith { 0.0 }.toMutableMap()
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val coveredRegularDates = mutableSetOf<java.time.LocalDate>()
        val coveredFallbackAdditionalDates = mutableSetOf<java.time.LocalDate>()
        val expectedRanges = (-1L..1L).flatMap { offset ->
            val period = ReportingPeriodCalculator.propose(month.plusMonths(offset), firstStartDay, firstEndDay)
            listOf(period.first, period.second)
        }.mapTo(mutableSetOf()) { it.start.toString() to it.end.toString() }

        val parsedPeriods = periodIncomes.mapNotNull { row ->
            val start = runCatching { java.time.LocalDate.parse(row.start) }.getOrNull() ?: return@mapNotNull null
            val end = runCatching { java.time.LocalDate.parse(row.end) }.getOrNull() ?: return@mapNotNull null
            val earned = row.earnedAmount
            if (end.isBefore(start) || !earned.isFinite() || earned < 0.0) return@mapNotNull null
            val additional = row.additionalAmount.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(earned) ?: 0.0
            val regular = (earned - additional).coerceAtLeast(0.0)
            if (regular <= 0.0 && additional <= 0.0) return@mapNotNull null
            MonthlyPeriodIncomeInput(start, end, regular, additional)
        }
        val hasCanonicalRows = parsedPeriods.any {
            (it.start.toString() to it.end.toString()) in expectedRanges
        }
        val validPeriods = (if (hasCanonicalRows) {
            parsedPeriods.filter { (it.start.toString() to it.end.toString()) in expectedRanges }
        } else parsedPeriods)
            .groupBy { it.start to it.end }
            .map { (range, rows) ->
                MonthlyPeriodIncomeInput(
                    range.first,
                    range.second,
                    rows.sumOf { it.regularAmount },
                    rows.sumOf { it.additionalAmount }
                )
            }
            .sortedBy { it.start }

        val additionalEntriesByPeriod = incomeEntries
            .filter { it.isAdditional && !it.isCarryover }
            .groupBy { it.periodStart to it.periodEnd }

        validPeriods.forEach { period ->
            val overlapStart = maxOf(period.start, monthStart)
            val overlapEnd = minOf(period.end, monthEnd)
            if (overlapEnd.isBefore(overlapStart)) return@forEach
            val dayCount = period.dayCount
            if (dayCount <= 0L) return@forEach
            val saved = savedPlans[period.start.toString() to period.end.toString()].orEmpty()
            val planForPeriod = resolvePlan(currentShares, saved)

            // Regular income entered for a reporting period is allocated evenly
            // over its calendar dates, then apportioned by that period's saved plan.
            if (period.regularAmount > 0.0) {
                val dailyRegularIncome = period.regularAmount / dayCount.toDouble()
                var date = overlapStart
                while (!date.isAfter(overlapEnd)) {
                    if (coveredRegularDates.add(date)) {
                        planForPeriod.forEach { (name, percent) ->
                            if (!name.equals(TRANSFER_CATEGORY, true)) {
                                result[name] = (result[name] ?: 0.0) +
                                    dailyRegularIncome * percent / 100.0
                            }
                        }
                    }
                    date = date.plusDays(1)
                }
            }

            // A dated one-off receipt belongs to the calendar month in which it
            // actually arrives; it must not be spread into an adjacent month.
            val detailedAdditional = additionalEntriesByPeriod[
                period.start.toString() to period.end.toString()
            ].orEmpty()
            val detailedAdditionalAmount = detailedAdditional.sumOf { it.amount.coerceAtLeast(0.0) }
            detailedAdditional.forEach entryLoop@{ entry ->
                val date = runCatching { java.time.LocalDate.parse(entry.date) }.getOrNull()
                    ?: return@entryLoop
                if (date.isBefore(period.start) || date.isAfter(period.end) ||
                    date.isBefore(monthStart) || date.isAfter(monthEnd)
                ) return@entryLoop
                planForPeriod.forEach { (name, percent) ->
                    if (!name.equals(TRANSFER_CATEGORY, true)) {
                        result[name] = (result[name] ?: 0.0) +
                            entry.amount.coerceAtLeast(0.0) * percent / 100.0
                    }
                }
            }

            // Older backups can contain the aggregate additional-income amount
            // without its individual dated ledger rows. Preserve those amounts
            // using the legacy day-proration rule until the detailed rows exist.
            val fallbackAdditional = (period.additionalAmount - detailedAdditionalAmount).coerceAtLeast(0.0)
            if (fallbackAdditional > 0.0) {
                val dailyAdditional = fallbackAdditional / dayCount.toDouble()
                var date = overlapStart
                while (!date.isAfter(overlapEnd)) {
                    if (coveredFallbackAdditionalDates.add(date)) {
                        planForPeriod.forEach { (name, percent) ->
                            if (!name.equals(TRANSFER_CATEGORY, true)) {
                                result[name] = (result[name] ?: 0.0) +
                                    dailyAdditional * percent / 100.0
                            }
                        }
                    }
                    date = date.plusDays(1)
                }
            }
        }

        // Forecast is already limited to uncovered future calendar days by
        // CalendarMonthIncomeEstimate. Use an equal-weight average of the
        // saved plan shares for the reporting periods intersecting this month.
        if (forecastIncome.isFinite() && forecastIncome > 0.0) {
            val relevantPlans = validPeriods.filter { period ->
                !period.end.isBefore(monthStart) && !period.start.isAfter(monthEnd)
            }.map { period ->
                val saved = savedPlans[period.start.toString() to period.end.toString()].orEmpty()
                resolvePlan(currentShares, saved)
            }
            val forecastShares = basePlan.keys.associateWith { name ->
                if (relevantPlans.isNotEmpty()) {
                    relevantPlans.map { it[name] ?: basePlan[name] ?: 0.0 }.average()
                } else {
                    basePlan[name] ?: 0.0
                }
            }
            forecastShares.forEach { (name, percent) ->
                if (!name.equals(TRANSFER_CATEGORY, true)) {
                    result[name] = (result[name] ?: 0.0) +
                        forecastIncome * percent / 100.0
                }
            }
        }
        return result
    }
}

internal object AnalyticsBudgetCalculator {
    /**
     * Shared expense cutoff for calendar-month actuals. The current month is
     * counted only through today; completed months include the full month.
     */
    fun calendarMonthExpenseEnd(
        month: java.time.YearMonth,
        today: java.time.LocalDate
    ): java.time.LocalDate =
        if (month == java.time.YearMonth.from(today)) minOf(month.atEndOfMonth(), today)
        else month.atEndOfMonth()

    /**
     * Carryover between reporting periods is an internal budget allocation, not
     * a cash outflow. Savings/reserve categories remain ordinary outflows under
     * ThunBudget's available-card-balance model.
     */
    fun cashExpensesInRange(
        expenses: List<Expense>,
        start: java.time.LocalDate,
        end: java.time.LocalDate
    ): List<Expense> {
        if (end.isBefore(start)) return emptyList()
        return expenses.filter { expense ->
            !expense.category.equals("Перенос средств", ignoreCase = true) &&
                expense.date >= start.toString() && expense.date <= end.toString()
        }
    }

    fun cashExpenseTotalInRange(
        expenses: List<Expense>,
        start: java.time.LocalDate,
        end: java.time.LocalDate
    ): Double = cashExpensesInRange(expenses, start, end).sumOf { it.amount.coerceAtLeast(0.0) }

    fun calendarMonthCashExpenses(
        expenses: List<Expense>,
        month: java.time.YearMonth,
        today: java.time.LocalDate
    ): List<Expense> {
        val start = month.atDay(1)
        val end = calendarMonthExpenseEnd(month, today)
        return cashExpensesInRange(expenses, start, end)
    }

    fun calendarMonthCashExpenseTotal(
        expenses: List<Expense>,
        month: java.time.YearMonth,
        today: java.time.LocalDate
    ): Double = calendarMonthCashExpenses(expenses, month, today).sumOf { it.amount.coerceAtLeast(0.0) }

    /** Reporting month = first reporting-period start through second period end. */
    fun reportingCycleExpenseRange(
        month: java.time.YearMonth,
        today: java.time.LocalDate,
        firstStartDay: Int = 9,
        firstEndDay: Int = 23
    ): ReportingCycleExpenseRange {
        val proposal = ReportingPeriodCalculator.propose(month, firstStartDay, firstEndDay)
        val start = proposal.first.start
        val end = proposal.second.end
        val visibleEnd = when {
            start.isAfter(today) -> start.minusDays(1)
            end.isBefore(today) -> end
            else -> today
        }
        return ReportingCycleExpenseRange(start, end, visibleEnd)
    }

    fun reportingCycleCashExpenses(
        expenses: List<Expense>,
        month: java.time.YearMonth,
        today: java.time.LocalDate,
        firstStartDay: Int = 9,
        firstEndDay: Int = 23
    ): List<Expense> {
        val range = reportingCycleExpenseRange(month, today, firstStartDay, firstEndDay)
        return cashExpensesInRange(expenses, range.start, range.visibleEnd)
    }

    fun reportingCycleCashExpenseTotal(
        expenses: List<Expense>,
        month: java.time.YearMonth,
        today: java.time.LocalDate,
        firstStartDay: Int = 9,
        firstEndDay: Int = 23
    ): Double = reportingCycleCashExpenses(
        expenses, month, today, firstStartDay, firstEndDay
    ).sumOf { it.amount.coerceAtLeast(0.0) }


    private data class IncomePeriod(
        val start: java.time.LocalDate,
        val end: java.time.LocalDate,
        /** All entered non-carryover income allocated to the reporting period. */
        val earnedAmount: Double,
        /** Stable/base earnings only; additional one-off receipts are excluded from forecasting. */
        val regularEarnedAmount: Double,
        /** Additional receipts; detailed ledger rows preserve their exact calendar date. */
        val additionalAmount: Double
    ) {
        val dayCount: Long
            get() = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1L
        val dailyRate: Double
            get() = earnedAmount / dayCount.toDouble()
        val regularDailyRate: Double
            get() = regularEarnedAmount / dayCount.toDouble()
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
        forecastLookbackPeriods: Int = 6,
        firstStartDay: Int = 9,
        firstEndDay: Int = 23,
        incomeEntries: List<IncomeEntry> = emptyList()
    ): CalendarMonthIncomeEstimate {
        require(forecastLookbackPeriods >= 1)
        val expectedRanges = (-4L..1L).flatMap { offset ->
            val period = ReportingPeriodCalculator.propose(month.plusMonths(offset), firstStartDay, firstEndDay)
            listOf(period.first, period.second)
        }.mapTo(mutableSetOf()) { it.start.toString() to it.end.toString() }

        val parsedPeriods = periodIncomes.mapNotNull { row ->
            val start = runCatching { java.time.LocalDate.parse(row.start) }.getOrNull() ?: return@mapNotNull null
            val end = runCatching { java.time.LocalDate.parse(row.end) }.getOrNull() ?: return@mapNotNull null
            val earned = row.earnedAmount
            if (end.isBefore(start) || !earned.isFinite() || earned < 0.0) return@mapNotNull null
            val additional = row.additionalAmount.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(earned) ?: 0.0
            val regular = (earned - additional).coerceAtLeast(0.0)
            if (regular <= 0.0 && additional <= 0.0) return@mapNotNull null
            IncomePeriod(start, end, earned, regular, additional)
        }

        // Prefer periods matching the configured reporting cycle. This prevents
        // old overlapping monthly aggregates from filling gaps in the current model.
        val hasCanonicalRows = parsedPeriods.any {
            (it.start.toString() to it.end.toString()) in expectedRanges
        }
        val normalized = (if (hasCanonicalRows) {
            parsedPeriods.filter { (it.start.toString() to it.end.toString()) in expectedRanges }
        } else parsedPeriods)
            .groupBy { it.start to it.end }
            .map { (range, rows) -> IncomePeriod(
                range.first,
                range.second,
                rows.sumOf { it.earnedAmount },
                rows.sumOf { it.regularEarnedAmount },
                rows.sumOf { it.additionalAmount }
            ) }
            .sortedWith(compareBy<IncomePeriod> { it.start }.thenBy { it.end })

        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        val coveredRegularDates = mutableSetOf<java.time.LocalDate>()
        val coveredFallbackAdditionalDates = mutableSetOf<java.time.LocalDate>()
        var enteredIncome = 0.0
        val additionalEntriesByPeriod = incomeEntries
            .filter { it.isAdditional && !it.isCarryover }
            .groupBy { it.periodStart to it.periodEnd }

        normalized.forEach { period ->
            val from = maxOf(monthStart, period.start)
            val to = minOf(monthEnd, period.end)
            if (to.isBefore(from)) return@forEach

            // Base income is a reporting-period amount, so it is allocated over
            // that period's calendar days when a month boundary splits the period.
            if (period.regularEarnedAmount > 0.0) {
                var date = from
                while (!date.isAfter(to)) {
                    if (coveredRegularDates.add(date)) enteredIncome += period.regularDailyRate
                    date = date.plusDays(1)
                }
            }

            // Additional income (including returned savings) is a dated cash
            // receipt. Attribute it to the month of its actual entry date.
            val detailedAdditional = additionalEntriesByPeriod[
                period.start.toString() to period.end.toString()
            ].orEmpty()
            val detailedAdditionalAmount = detailedAdditional.sumOf { it.amount.coerceAtLeast(0.0) }
            detailedAdditional.forEach entryLoop@{ entry ->
                val date = runCatching { java.time.LocalDate.parse(entry.date) }.getOrNull()
                    ?: return@entryLoop
                if (!date.isBefore(period.start) && !date.isAfter(period.end) &&
                    !date.isBefore(monthStart) && !date.isAfter(monthEnd)
                ) {
                    enteredIncome += entry.amount.coerceAtLeast(0.0)
                }
            }

            // Retain aggregate-only older backups. Once dated ledger rows exist,
            // only an unrepresented remainder is prorated as legacy data.
            val fallbackAdditional = (period.additionalAmount - detailedAdditionalAmount).coerceAtLeast(0.0)
            if (fallbackAdditional > 0.0) {
                val dailyAdditional = fallbackAdditional / period.dayCount.toDouble()
                var date = from
                while (!date.isAfter(to)) {
                    if (coveredFallbackAdditionalDates.add(date)) enteredIncome += dailyAdditional
                    date = date.plusDays(1)
                }
            }
        }

        // Only regular income periods train the recurring-income forecast.
        // Additional receipts never inflate the baseline.
        val recentDailyRates = normalized
            .filter { !it.end.isAfter(asOf) && it.regularEarnedAmount > 0.0 }
            .sortedBy { it.end }
            .takeLast(forecastLookbackPeriods)
            .map { it.regularDailyRate }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
        val medianDailyIncome = if (recentDailyRates.size >= 2) {
            val middle = recentDailyRates.size / 2
            if (recentDailyRates.size % 2 == 0) {
                (recentDailyRates[middle - 1] + recentDailyRates[middle]) / 2.0
            } else recentDailyRates[middle]
        } else null

        // Additional one-off receipts do not mark an entire reporting period
        // as covered by regular income; only a real base-income period does.
        var uncoveredPastDays = 0
        var uncoveredFutureDays = 0
        var date = monthStart
        while (!date.isAfter(monthEnd)) {
            if (date !in coveredRegularDates) {
                if (date.isAfter(asOf)) uncoveredFutureDays++ else uncoveredPastDays++
            }
            date = date.plusDays(1)
        }
        val forecastDays = if (medianDailyIncome != null) uncoveredFutureDays else 0
        val forecastIncome = (medianDailyIncome ?: 0.0) * forecastDays
        return CalendarMonthIncomeEstimate(
            enteredIncome = enteredIncome,
            forecastIncome = forecastIncome,
            coveredDays = coveredRegularDates.size,
            uncoveredPastDays = uncoveredPastDays,
            forecastDays = forecastDays,
            uncoveredFutureDays = uncoveredFutureDays,
            monthDays = month.lengthOfMonth(),
            medianDailyIncome = medianDailyIncome
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
        asOf: java.time.LocalDate = java.time.LocalDate.now(),
        firstStartDay: Int = 9,
        firstEndDay: Int = 23,
        incomeEntries: List<IncomeEntry> = emptyList()
    ): Double {
        if (!useReportingPeriods) return fixedMonthlyIncome.coerceAtLeast(0.0)
        return calendarMonthIncome(
            periodIncomes, month, asOf,
            firstStartDay = firstStartDay,
            firstEndDay = firstEndDay,
            incomeEntries = incomeEntries
        ).totalIncome
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

    /**
     * Calculates a category budget for an exact reporting period. Overview
     * and Analytics call this same function so their income and proration
     * formulas cannot drift apart.
     */
    fun periodCategoryBudget(
        periodIncome: Double,
        monthlyIncomeFallback: Double,
        categoryPercent: Double,
        start: java.time.LocalDate,
        end: java.time.LocalDate
    ): Double {
        if (!categoryPercent.isFinite() || categoryPercent < 0.0 || end.isBefore(start)) return 0.0
        val validPeriodIncome = periodIncome.takeIf { it.isFinite() && it > 0.0 }
        return if (validPeriodIncome != null) {
            validPeriodIncome * categoryPercent / 100.0
        } else {
            proratedPeriodBudget(monthlyIncomeFallback, categoryPercent, start, end)
        }
    }

    /**
     * Applies monthly income to each inclusive calendar day, respecting the
     * length of each month when a reporting period crosses a month boundary.
     */
    fun proratedPeriodBudget(
        monthlyIncome: Double,
        categoryPercent: Double,
        start: java.time.LocalDate,
        end: java.time.LocalDate
    ): Double {
        if (!monthlyIncome.isFinite() || monthlyIncome <= 0.0 ||
            !categoryPercent.isFinite() || categoryPercent < 0.0 || end.isBefore(start)
        ) return 0.0

        val monthlyCategoryBudget = monthlyIncome * categoryPercent / 100.0
        var day = start
        var total = 0.0
        while (!day.isAfter(end)) {
            total += monthlyCategoryBudget / day.lengthOfMonth().toDouble()
            day = day.plusDays(1)
        }
        return total
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
