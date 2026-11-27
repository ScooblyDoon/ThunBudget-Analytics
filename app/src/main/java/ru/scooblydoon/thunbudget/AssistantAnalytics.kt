package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.DayOfWeek
import java.util.Locale

internal enum class SpendingBucket { DAY, WEEK, MONTH, QUARTER, YEAR }

internal data class SpendingAverage(
    val total: Double,
    val bucketCount: Long,
    val average: Double,
    val category: String? = null
)

internal object AssistantAnalytics {
    private const val TRANSFER_CATEGORY = "Перенос средств"

    /** General spending analytics excludes the internal reporting-period carryover row. */
    private fun spendingRows(expenses: List<Expense>): List<Expense> =
        expenses.filterNot { it.category.equals(TRANSFER_CATEGORY, ignoreCase = true) }

    fun topCategoryByFrequency(expenses: List<Expense>): Pair<String, Int>? =
        spendingRows(expenses).groupingBy { it.category }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.let { it.key to it.value }

    fun topCategoryByAmount(expenses: List<Expense>): Pair<String, Double>? =
        spendingRows(expenses).groupBy { it.category }
            .mapValues { (_, rows) -> rows.sumOf { it.amount.coerceAtLeast(0.0) } }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .firstOrNull()?.let { it.key to it.value }

    fun topSpendingWeekday(expenses: List<Expense>): Pair<DayOfWeek, Double>? =
        spendingRows(expenses).mapNotNull { expense ->
            val day = runCatching { LocalDate.parse(expense.date).dayOfWeek }.getOrNull() ?: return@mapNotNull null
            day to expense.amount.coerceAtLeast(0.0)
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<DayOfWeek, Double>> { it.value }.thenBy { it.key.value })
            .firstOrNull()?.let { it.key to it.value }

    fun topTitleByFrequency(expenses: List<Expense>): Pair<String, Int>? =
        spendingRows(expenses).groupingBy { it.title.trim().ifBlank { "Без названия" } }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.let { it.key to it.value }

    fun largest(expenses: List<Expense>): Expense? = spendingRows(expenses).maxWithOrNull(
        compareBy<Expense> { it.amount }.thenBy { it.date }.thenBy { it.id }
    )

    fun smallest(expenses: List<Expense>): Expense? = spendingRows(expenses).filter { it.amount > 0.0 }.minWithOrNull(
        compareBy<Expense> { it.amount }.thenBy { it.date }.thenBy { it.id }
    )

    fun average(
        expenses: List<Expense>,
        bucket: SpendingBucket,
        category: String? = null
    ): SpendingAverage? {
        val rows = spendingRows(expenses).filter { it.amount > 0.0 && (category == null || it.category.equals(category, true)) }
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

    fun topSpendingDay(expenses: List<Expense>): Pair<LocalDate, Double>? =
        spendingRows(expenses).mapNotNull { expense ->
            val date = runCatching { LocalDate.parse(expense.date) }.getOrNull() ?: return@mapNotNull null
            date to expense.amount.coerceAtLeast(0.0)
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, amounts) -> amounts.sum() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<LocalDate, Double>> { it.value }.thenBy { it.key })
            .firstOrNull()?.let { it.key to it.value }

    data class SavingRecommendation(
        val category: String,
        val spent: Double,
        val planned: Double,
        val suggestedReduction: Double,
        val reason: String
    )

    fun savingRecommendations(
        expenses: List<Expense>,
        categoryBudgets: Map<String, Double>,
        remainingBudget: Double,
        daysUntilPayday: Long
    ): List<SavingRecommendation> {
        if (expenses.isEmpty()) return emptyList()
        val protected = setOf("жилье", "жильё", "жкх", "ипотека", "аренда", "лекарства", "здоровье", "накопления")
        val totals = spendingRows(expenses).groupBy { it.category }
            .mapValues { (_, rows) -> rows.sumOf { it.amount.coerceAtLeast(0.0) } }
        val deficit = if (remainingBudget < 0.0) -remainingBudget else 0.0
        val candidates = totals.mapNotNull { (category, spent) ->
            if (protected.any { category.lowercase(Locale.ROOT).contains(it) } || spent <= 0.0) return@mapNotNull null
            val planned = categoryBudgets[category] ?: 0.0
            val over = (spent - planned).coerceAtLeast(0.0)
            val target = when {
                deficit > 0.0 -> minOf(spent, maxOf(over, deficit / 2.0))
                planned > 0.0 && over > 0.0 -> over
                else -> spent * 0.10
            }.coerceAtMost(spent)
            val reason = when {
                over > 0.0 -> "Превышен план категории на ${over.toLong()} ₽"
                deficit > 0.0 -> "Поможет закрыть нехватку бюджета до зарплаты"
                else -> "Можно временно сократить необязательные покупки на 10%"
            }
            SavingRecommendation(category, spent, planned, target, reason)
        }.sortedWith(
            compareByDescending<SavingRecommendation> { if (it.planned > 0.0) (it.spent - it.planned).coerceAtLeast(0.0) else 0.0 }
                .thenByDescending { it.spent }
        )
        if (candidates.isEmpty()) return emptyList()
        var needed = deficit
        return candidates.map { candidate ->
            val reduction = if (deficit > 0.0) minOf(candidate.suggestedReduction, needed).also { needed -= it }
                else candidate.suggestedReduction
            candidate.copy(suggestedReduction = reduction)
        }.filter { it.suggestedReduction > 0.0 }.take(4)
    }

    fun monthlyTotals(expenses: List<Expense>): List<Pair<String, Double>> =
        spendingRows(expenses).mapNotNull { e ->
            runCatching { YearMonth.from(LocalDate.parse(e.date)).toString() to e.amount }.getOrNull()
        }.groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (month, amounts) ->
                val parsed = YearMonth.parse(month)
                parsed.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale("ru", "RU"))
                    .replaceFirstChar { it.titlecase(java.util.Locale("ru", "RU")) } + " " + parsed.year to amounts.sum()
            }

    fun categoryTotals(expenses: List<Expense>): List<Pair<String, Double>> =
        spendingRows(expenses).groupBy { it.category }.map { (category, rows) -> category to rows.sumOf { it.amount } }
            .sortedByDescending { it.second }
}
