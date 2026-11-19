package ru.scooblydoon.thunbudget

import java.time.LocalDate

/** A normalized command produced by the local parser or the remote language model. */
enum class AssistantIntent(val wireValue: String, val isMutation: Boolean = false) {
    ADD_EXPENSE("add_expense", true),
    ADD_INCOME("add_income", true),
    QUERY_REMAINING("query_remaining"),
    QUERY_SPENDING("query_spending"),
    QUERY_CATEGORY_SPENDING("query_category_spending"),
    QUERY_OVERSPENDING("query_overspending"),
    FORECAST("forecast"),
    UPDATE_EXPENSE("update_expense", true),
    DELETE_EXPENSE("delete_expense", true),
    UNKNOWN("unknown");

    companion object {
        fun fromWireValue(value: String?): AssistantIntent =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: UNKNOWN
    }
}

data class AssistantCommand(
    val intent: AssistantIntent,
    val amount: Double? = null,
    val category: String? = null,
    val title: String? = null,
    val date: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    /** Human-readable selector only, e.g. "last_expense"; never a database ID from the model. */
    val target: String? = null,
    val confidence: Double = 0.0,
    val missingFields: List<String> = emptyList(),
    val requiresConfirmation: Boolean = intent.isMutation
)

sealed interface AssistantCommandValidation {
    data class Valid(val command: AssistantCommand) : AssistantCommandValidation
    data class NeedsClarification(val reason: String) : AssistantCommandValidation
    data class Rejected(val reason: String) : AssistantCommandValidation
}

object AssistantCommandValidator {
    private const val MIN_CONFIDENCE = 0.65
    private const val MAX_AMOUNT = 100_000_000.0
    private val datePattern = Regex("""\d{4}-\d{2}-\d{2}""")

    fun validate(
        input: AssistantCommand,
        availableCategories: List<String>,
        today: LocalDate = LocalDate.now()
    ): AssistantCommandValidation {
        if (!input.confidence.isFinite() || input.confidence !in 0.0..1.0) {
            return AssistantCommandValidation.Rejected("Некорректная уверенность интерпретации.")
        }
        if (input.intent == AssistantIntent.UNKNOWN) {
            return AssistantCommandValidation.NeedsClarification("Не удалось определить, что нужно сделать.")
        }
        if (input.confidence < MIN_CONFIDENCE) {
            return AssistantCommandValidation.NeedsClarification("Команда распознана неуверенно. Уточни формулировку.")
        }
        if (input.missingFields.isNotEmpty()) {
            return AssistantCommandValidation.NeedsClarification("Не хватает данных: ${input.missingFields.joinToString()}.")
        }

        val normalizedCategory = input.category?.trim()?.let { requested ->
            availableCategories.firstOrNull { it.equals(requested, ignoreCase = true) }
        }
        if (!input.category.isNullOrBlank() && normalizedCategory == null) {
            return AssistantCommandValidation.NeedsClarification("Категория «${input.category}» не найдена в бюджете.")
        }

        val amountRequired = input.intent in setOf(
            AssistantIntent.ADD_EXPENSE,
            AssistantIntent.ADD_INCOME,
            AssistantIntent.UPDATE_EXPENSE
        )
        if (amountRequired) {
            val amount = input.amount
            if (amount == null || !amount.isFinite() || amount <= 0.0 || amount > MAX_AMOUNT) {
                return AssistantCommandValidation.NeedsClarification("Укажи корректную сумму больше нуля.")
            }
        } else if (input.amount != null && (!input.amount.isFinite() || input.amount < 0.0 || input.amount > MAX_AMOUNT)) {
            return AssistantCommandValidation.Rejected("Сумма выходит за допустимые пределы.")
        }

        if (input.intent == AssistantIntent.ADD_EXPENSE && normalizedCategory == null) {
            return AssistantCommandValidation.NeedsClarification("Уточни категорию расхода.")
        }
        if (input.intent == AssistantIntent.QUERY_CATEGORY_SPENDING && normalizedCategory == null) {
            return AssistantCommandValidation.NeedsClarification("Уточни категорию, расходы по которой нужно показать.")
        }
        if (input.intent in setOf(AssistantIntent.UPDATE_EXPENSE, AssistantIntent.DELETE_EXPENSE)) {
            if (input.target !in setOf("last_expense", "latest_expense")) {
                return AssistantCommandValidation.NeedsClarification("Нужно уточнить, какую именно операцию изменить или удалить.")
            }
        }

        val normalizedDate = input.date?.let { raw ->
            if (!datePattern.matches(raw)) return AssistantCommandValidation.Rejected("Дата должна быть в формате ГГГГ-ММ-ДД.")
            val parsed = runCatching { LocalDate.parse(raw) }.getOrNull()
                ?: return AssistantCommandValidation.Rejected("Указана несуществующая дата.")
            if (parsed.isAfter(today.plusDays(366))) {
                return AssistantCommandValidation.Rejected("Дата операции слишком далеко в будущем.")
            }
            parsed.toString()
        }

        val start = input.periodStart?.let { parseDate(it) }
        val end = input.periodEnd?.let { parseDate(it) }
        if ((input.periodStart != null && start == null) || (input.periodEnd != null && end == null)) {
            return AssistantCommandValidation.Rejected("Некорректные границы периода.")
        }
        if (start != null && end != null && end.isBefore(start)) {
            return AssistantCommandValidation.Rejected("Дата окончания периода раньше даты начала.")
        }
        if ((input.periodStart == null) != (input.periodEnd == null)) {
            return AssistantCommandValidation.NeedsClarification("Укажи обе границы периода.")
        }

        val safeTitle = input.title?.trim()?.take(80)?.takeIf { it.isNotBlank() }
        val safeTarget = input.target?.trim()?.take(40)
        val command = input.copy(
            category = normalizedCategory,
            title = safeTitle,
            date = normalizedDate,
            periodStart = start?.toString(),
            periodEnd = end?.toString(),
            target = safeTarget,
            requiresConfirmation = input.intent.isMutation
        )
        return AssistantCommandValidation.Valid(command)
    }

    private fun parseDate(value: String): LocalDate? {
        if (!datePattern.matches(value)) return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
    }
}
