package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * First offline interpretation layer. It converts common Russian requests into
 * the same validated command contract used by a future remote model.
 */
object AssistantLocalParser {
    fun parse(
        phrase: String,
        availableCategories: List<String>,
        today: LocalDate = LocalDate.now()
    ): AssistantCommand? {
        val normalized = phrase.lowercase(Locale("ru", "RU"))
            .replace('ё', 'е')
            .replace(Regex("""[!?.,;:]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isBlank()) return null

        val category = availableCategories
            .sortedByDescending { it.length }
            .firstOrNull { normalized.contains(it.lowercase(Locale("ru", "RU")).replace('ё', 'е')) }

        val date = when {
            Regex("""\b(вчера|за вчера)\b""").containsMatchIn(normalized) -> today.minusDays(1)
            Regex("""\b(сегодня|за сегодня)\b""").containsMatchIn(normalized) -> today
            else -> today
        }

        val month = when {
            normalized.contains("прошлом месяце") || normalized.contains("за прошлый месяц") ->
                YearMonth.from(today).minusMonths(1)
            normalized.contains("в этом месяце") || normalized.contains("за этот месяц") ||
                normalized.contains("за месяц") -> YearMonth.from(today)
            else -> null
        }
        val periodStart = month?.atDay(1)?.toString()
        val periodEnd = month?.let { if (it == YearMonth.from(today)) today else it.atEndOfMonth() }?.toString()

        if (listOf("сколько осталось", "остаток", "доступно в бюджете", "до зарплаты").any(normalized::contains)) {
            return command(AssistantIntent.QUERY_REMAINING, confidence = 0.92)
        }

        if (listOf("превыш", "перерасход", "где вышел за бюджет", "какие категории выше бюджета")
                .any(normalized::contains)) {
            return command(AssistantIntent.QUERY_OVERSPENDING, periodStart = periodStart, periodEnd = periodEnd, confidence = 0.9)
        }

        if (listOf("если буду тратить", "если тратить", "в день", "прогноз", "сколько останется к").any(normalized::contains)) {
            val dailyAmount = parseSpokenAmount(normalized)
            return command(
                AssistantIntent.FORECAST,
                amount = dailyAmount,
                periodStart = periodStart,
                periodEnd = periodEnd,
                confidence = if (dailyAmount != null) 0.88 else 0.72,
                missingFields = if (dailyAmount == null) listOf("сумма расходов в день") else emptyList()
            )
        }

        val isIncome = listOf("доход", "зарплата", "получил", "получила", "поступление", "зачислили", "мне перевели")
            .any(normalized::contains)
        val amount = parseSpokenAmount(normalized)

        if (isIncome && listOf("добавь", "добавить", "запиши", "внеси", "получил", "получила", "мне перевели").any(normalized::contains)) {
            return command(
                AssistantIntent.ADD_INCOME,
                amount = amount,
                title = "Голосовой доход",
                date = date.toString(),
                confidence = if (amount != null) 0.9 else 0.7,
                missingFields = if (amount == null) listOf("сумма дохода") else emptyList()
            )
        }

        val hasExpenseVerb = listOf("добавь", "добавить", "запиши", "записать", "потратил", "потратила", "расход", "купил", "купила", "оплатил", "оплатила")
            .any(normalized::contains)
        if (hasExpenseVerb) {
            return command(
                AssistantIntent.ADD_EXPENSE,
                amount = amount,
                category = category,
                title = category,
                date = date.toString(),
                confidence = if (amount != null && category != null) 0.92 else 0.72,
                missingFields = buildList {
                    if (amount == null) add("сумма расхода")
                    if (category == null) add("категория")
                }
            )
        }

        if (category != null && listOf("сколько потратил", "сколько потратила", "расходы", "потрачено").any(normalized::contains)) {
            return command(
                AssistantIntent.QUERY_CATEGORY_SPENDING,
                category = category,
                periodStart = periodStart,
                periodEnd = periodEnd,
                confidence = 0.9
            )
        }

        if (listOf("сколько потратил", "сколько потратила", "общие расходы", "сумма расходов", "потрачено").any(normalized::contains)) {
            return command(
                AssistantIntent.QUERY_SPENDING,
                periodStart = periodStart,
                periodEnd = periodEnd,
                confidence = 0.88
            )
        }

        return null
    }

    private fun command(
        intent: AssistantIntent,
        amount: Double? = null,
        category: String? = null,
        title: String? = null,
        date: String? = null,
        periodStart: String? = null,
        periodEnd: String? = null,
        confidence: Double,
        missingFields: List<String> = emptyList()
    ) = AssistantCommand(
        intent = intent,
        amount = amount,
        category = category,
        title = title,
        date = date,
        periodStart = periodStart,
        periodEnd = periodEnd,
        confidence = confidence,
        missingFields = missingFields
    )
}
