package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

internal enum class SpendingBucket { DAY, WEEK, MONTH, QUARTER, YEAR }

internal data class SpendingAverage(
    val total: Double,
    val bucketCount: Long,
    val average: Double,
    val category: String? = null
)

internal object AssistantAnalytics {
    fun topCategoryByFrequency(expenses: List<Expense>): Pair<String, Int>? =
        expenses.groupingBy { it.category }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.let { it.key to it.value }

    fun topTitleByFrequency(expenses: List<Expense>): Pair<String, Int>? =
        expenses.groupingBy { it.title.trim().ifBlank { "Без названия" } }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.let { it.key to it.value }

    fun largest(expenses: List<Expense>): Expense? = expenses.maxWithOrNull(
        compareBy<Expense> { it.amount }.thenBy { it.date }.thenBy { it.id }
    )

    fun smallest(expenses: List<Expense>): Expense? = expenses.filter { it.amount > 0.0 }.minWithOrNull(
        compareBy<Expense> { it.amount }.thenBy { it.date }.thenBy { it.id }
    )

    fun average(
        expenses: List<Expense>,
        bucket: SpendingBucket,
        category: String? = null
    ): SpendingAverage? {
        val rows = expenses.filter { it.amount > 0.0 && (category == null || it.category.equals(category, true)) }
            .mapNotNull { e -> runCatching { LocalDate.parse(e.date) to e.amount }.getOrNull() }
        if (rows.isEmpty()) return null
        val first = rows.minOf { it.first }
        val last = rows.maxOf { it.first }
        val count = when (bucket) {
            SpendingBucket.DAY -> ChronoUnit.DAYS.between(first, last) + 1
            SpendingBucket.WEEK -> ChronoUnit.WEEKS.between(first.with(java.time.DayOfWeek.MONDAY), last.with(java.time.DayOfWeek.MONDAY)) + 1
            SpendingBucket.MONTH -> ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(last)) + 1
            SpendingBucket.QUARTER -> {
                val a = first.year * 4L + (first.monthValue - 1) / 3
                val b = last.year * 4L + (last.monthValue - 1) / 3
                b - a + 1
            }
            SpendingBucket.YEAR -> (last.year - first.year + 1).toLong()
        }.coerceAtLeast(1)
        val total = rows.sumOf { it.second }
        return SpendingAverage(total, count, total / count, category)
    }

    fun monthlyTotals(expenses: List<Expense>): List<Pair<String, Double>> =
        expenses.mapNotNull { e ->
            runCatching { YearMonth.from(LocalDate.parse(e.date)).toString() to e.amount }.getOrNull()
        }.groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (month, amounts) ->
                val parsed = YearMonth.parse(month)
                parsed.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("ru", "RU"))
                    .replaceFirstChar { it.titlecase(java.util.Locale("ru", "RU")) } + " " + parsed.year to amounts.sum()
            }

    fun categoryTotals(expenses: List<Expense>): List<Pair<String, Double>> =
        expenses.groupBy { it.category }.map { (category, rows) -> category to rows.sumOf { it.amount } }
            .sortedByDescending { it.second }
}
