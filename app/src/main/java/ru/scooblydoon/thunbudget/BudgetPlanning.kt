package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Daily allowance for the selected reporting period.
 * The remaining budget is spread evenly over today and all remaining calendar days.
 */
data class DailyLimitSummary(
    val amountPerDay: Double,
    val daysRemaining: Long
)

fun calculateDailyLimit(
    remainingBudget: Double,
    periodStart: LocalDate,
    periodEnd: LocalDate,
    today: LocalDate
): DailyLimitSummary {
    if (periodEnd.isBefore(today) || periodEnd.isBefore(periodStart)) {
        return DailyLimitSummary(0.0, 0L)
    }
    val firstRemainingDay = maxOf(today, periodStart)
    val days = ChronoUnit.DAYS.between(firstRemainingDay, periodEnd) + 1L
    if (days <= 0L) return DailyLimitSummary(0.0, 0L)
    return DailyLimitSummary(
        amountPerDay = remainingBudget.coerceAtLeast(0.0) / days.toDouble(),
        daysRemaining = days
    )
}
