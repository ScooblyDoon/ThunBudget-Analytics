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

        val category = findVoiceCategory(
            phrase,
            availableCategories.map { BudgetCategory(it, 0.0) }
        )?.name

        val parsedDate = parseVoiceDate(phrase, today)
        val mentionsYesterday = Regex("""\b(вчера|за вчера)\b""").containsMatchIn(normalized)
        val mentionsToday = Regex("""\b(сегодня|за сегодня)\b""").containsMatchIn(normalized)
        val date = parsedDate ?: today

        val month = when {
            normalized.contains("прошлом месяце") || normalized.contains("за прошлый месяц") ->
                YearMonth.from(today).minusMonths(1)
            normalized.contains("в этом месяце") || normalized.contains("за этот месяц") ||
                normalized.contains("за месяц") -> YearMonth.from(today)
            else -> null
        }
        val periodBounds = when {
            month != null -> month.atDay(1) to (if (month == YearMonth.from(today)) today else month.atEndOfMonth())
            parsedDate != null -> date to date
            normalized.contains("за неделю") || normalized.contains("за последние 7 дней") -> today.minusDays(6) to today
            else -> null
        }
        val periodStart = periodBounds?.first?.toString()
        val periodEnd = periodBounds?.second?.toString()

        if (listOf("удали последний расход", "удалить последний расход", "удали последнюю операцию", "удалить последнюю операцию", "отмени последнюю операцию").any { normalized.contains(it) }) {
            return command(
                AssistantIntent.DELETE_EXPENSE,
                target = "last_expense",
                confidence = 0.9
            )
        }

        if (listOf("измени последний расход", "изменить последний расход", "поменяй последний расход", "исправь последний расход", "измени последнюю операцию").any { normalized.contains(it) }) {
            val replacementAmount = parseSpokenAmount(phrase)
            return command(
                AssistantIntent.UPDATE_EXPENSE,
                amount = replacementAmount,
                target = "last_expense",
                confidence = if (replacementAmount != null) 0.9 else 0.72,
                missingFields = if (replacementAmount == null) listOf("новая сумма") else emptyList()
            )
        }

        if (listOf("сколько осталось", "остаток", "доступно в бюджете", "до зарплаты").any { normalized.contains(it) }) {
            return command(AssistantIntent.QUERY_REMAINING, confidence = 0.92)
        }

        if (listOf("превыш", "превыс", "перерасход", "где вышел за бюджет", "какие категории выше бюджета")
                .any { normalized.contains(it) }) {
            return command(AssistantIntent.QUERY_OVERSPENDING, periodStart = periodStart, periodEnd = periodEnd, confidence = 0.9)
        }

        if (listOf("если буду тратить", "если тратить", "в день", "прогноз", "сколько останется к").any { normalized.contains(it) }) {
            val dailyAmount = parseSpokenAmount(phrase)
            return command(
                AssistantIntent.FORECAST,
                amount = dailyAmount,
                periodStart = periodStart,
                periodEnd = periodEnd,
                confidence = if (dailyAmount != null) 0.88 else 0.72,
                missingFields = if (dailyAmount == null) listOf("сумма расходов в день") else emptyList()
            )
        }

        if (category != null && listOf("сколько потратил", "сколько я потратил", "сколько потратила", "сколько я потратила", "расходы", "потрачено").any { normalized.contains(it) }) {
            return command(
                AssistantIntent.QUERY_CATEGORY_SPENDING,
                category = category,
                periodStart = periodStart,
                periodEnd = periodEnd,
                confidence = 0.9
            )
        }

        if (listOf("сколько потратил", "сколько я потратил", "сколько потратила", "сколько я потратила", "общие расходы", "сумма расходов", "потрачено", "покажи расходы", "все расходы", "расходы за").any { normalized.contains(it) }) {
            return command(
                AssistantIntent.QUERY_SPENDING,
                periodStart = periodStart,
                periodEnd = periodEnd,
                confidence = 0.88
            )
        }

        val isIncome = listOf("доход", "зарплата", "получил", "получила", "поступление", "зачислили", "мне перевели")
            .any { normalized.contains(it) }
        val amount = parseSpokenAmount(phrase)

        if (isIncome && listOf("добавь", "добавить", "запиши", "внеси", "получил", "получила", "мне перевели").any { normalized.contains(it) }) {
            return command(
                AssistantIntent.ADD_INCOME,
                amount = amount,
                title = "Голосовой доход",
                date = date.toString(),
                confidence = if (amount != null) 0.9 else 0.7,
                missingFields = if (amount == null) listOf("сумма дохода") else emptyList()
            )
        }

        val hasExpenseVerb = listOf("добавь", "добавить", "запиши", "записать", "потратил", "потратила", "расход", "внеси", "внести", "купил", "купила", "оплатил", "оплатила")
            .any { normalized.contains(it) }
        // Offline speech recognition can omit the command verb entirely while retaining
        // the amount and category (e.g. "500 руб продукты 17 ноября"). Treat that
        // unambiguous amount + known category combination as an expense request.
        val hasRecognizedExpensePayload = amount != null && category != null && !isIncome
        if (hasExpenseVerb || hasRecognizedExpensePayload) {
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
        target: String? = null,
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
        target = target,
        confidence = confidence,
        missingFields = missingFields
    )
}
