package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth

/**
 * Calendar-aware calculation for a two-period reporting cycle.
 *
 * The first period is configured by day-of-month boundaries. The second period
 * fills the remaining calendar days up to the day before the next first period.
 */
data class ReportingDateRange(
    val start: LocalDate,
    val end: LocalDate
) {
    init {
        require(!end.isBefore(start)) { "Окончание периода не может быть раньше начала." }
    }
}

data class ReportingPeriodProposal(
    val first: ReportingDateRange,
    val second: ReportingDateRange
)

object ReportingPeriodCalculator {
    /**
     * Calculates both intervals for [month]. Boundaries are clamped to the
     * actual month length (28/29/30/31 days), so no invalid date is produced.
     *
     * Example: firstStartDay=9, firstEndDay=24 -> 9..24 and 25..8 next month.
     * Example: firstStartDay=1, firstEndDay=12 -> 1..12 and 13..last day.
     */
    fun propose(
        month: YearMonth,
        firstStartDay: Int,
        firstEndDay: Int
    ): ReportingPeriodProposal {
        require(firstStartDay in 1..31) { "День начала должен быть от 1 до 31." }
        require(firstEndDay in 1..31) { "День окончания должен быть от 1 до 31." }

        val firstStart = month.atDay(firstStartDay.coerceAtMost(month.lengthOfMonth()))
        val firstEnd = month.atDay(firstEndDay.coerceAtMost(month.lengthOfMonth()))
        require(!firstEnd.isBefore(firstStart)) {
            "Окончание первого периода должно быть не раньше его начала."
        }

        val secondStart = firstEnd.plusDays(1)
        val nextFirstStart = month.plusMonths(1)
            .atDay(firstStartDay.coerceAtMost(month.plusMonths(1).lengthOfMonth()))
        val secondEnd = nextFirstStart.minusDays(1)

        return ReportingPeriodProposal(
            first = ReportingDateRange(firstStart, firstEnd),
            second = ReportingDateRange(secondStart, secondEnd)
        )
    }

    /**
     * Returns the configured reporting period containing [date].
     * Days before the first period's start belong to the previous month's second period.
     */
    fun periodFor(
        date: LocalDate,
        firstStartDay: Int,
        firstEndDay: Int
    ): ReportingDateRange {
        val month = YearMonth.from(date)
        val current = propose(month, firstStartDay, firstEndDay)
        return when {
            !date.isBefore(current.first.start) && !date.isAfter(current.first.end) -> current.first
            date.isAfter(current.first.end) -> current.second
            else -> propose(month.minusMonths(1), firstStartDay, firstEndDay).second
        }
    }

    /** Builds a proposal for the month containing the supplied first-period start. */
    fun propose(firstStart: LocalDate, firstEnd: LocalDate): ReportingPeriodProposal {
        require(!firstEnd.isBefore(firstStart))
        require(YearMonth.from(firstStart) == YearMonth.from(firstEnd)) {
            "Первый период для автоматического расчёта должен находиться в одном месяце."
        }
        return propose(
            YearMonth.from(firstStart),
            firstStart.dayOfMonth,
            firstEnd.dayOfMonth
        )
    }
}
