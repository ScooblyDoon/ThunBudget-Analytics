package ru.scooblydoon.thunbudget

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/**
 * Pure Kotlin parser for locally received T-Bank notification payloads.
 *
 * It deliberately does not persist data or mutate the budget ledger. A parsed
 * event is only a candidate for a user review/import flow.
 */
object TBankNotificationParser {
    const val T_BANK_PACKAGE = "com.idamob.tinkoff.android"

    enum class OperationType {
        PURCHASE, TRANSFER_OUT, TRANSFER_IN, OWN_ACCOUNT_TRANSFER,
        CASH_WITHDRAWAL, CASH_DEPOSIT, REFUND, REVERSAL, CASHBACK,
        SUBSCRIPTION, BALANCE_NOTICE, UNKNOWN
    }

    enum class Flow { EXPENSE, INCOME, INTERNAL, NONE, REVIEW }

    enum class ReviewReason {
        AMBIGUOUS_DIRECTION, AMBIGUOUS_OPERATION, AMBIGUOUS_AMOUNT,
        POSSIBLE_OWN_ACCOUNT_TRANSFER, REFUND_WITHOUT_ORIGINAL
    }

    data class Input(
        val packageName: String,
        val title: String? = null,
        val text: String? = null,
        val bigText: String? = null,
        /** Notification post time, not necessarily the bank transaction date. */
        val postTimeMillis: Long,
        val notificationKey: String? = null,
        val bankReferenceId: String? = null,
        val maskedAccount: String? = null
    )

    data class ParsedEvent(
        val operationType: OperationType,
        val flow: Flow,
        /** Positive absolute amount in kopecks; null if not safely parsed. */
        val amountKopecks: Long?,
        val currency: String?,
        val merchant: String?,
        val category: String?,
        val observedAtMillis: Long,
        val notificationKey: String?,
        val bankReferenceId: String?,
        val maskedAccount: String?,
        val reviewReasons: List<ReviewReason>,
        val confidence: Double,
        /** Only populated from a stable bank/notification identifier. */
        val safeDeduplicationKey: String?
    ) {
        val requiresReview: Boolean get() = flow == Flow.REVIEW || reviewReasons.isNotEmpty()
    }

    fun parse(input: Input): ParsedEvent? {
        if (input.packageName != T_BANK_PACKAGE) return null

        val raw = listOfNotNull(input.title, input.text, input.bigText)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
        if (raw.isBlank()) return null

        val normalized = normalize(raw)
        val amount = parseAmountKopecks(raw)
        val currency = if (Regex("""(?i)(₽|руб(?:\.|лей|ля)?|RUB)""").containsMatchIn(raw)) "RUB" else null
        val merchant = extractMerchant(raw, normalized)
        val type = classify(normalized)
        val reasons = mutableListOf<ReviewReason>()

        var flow = when (type) {
            OperationType.PURCHASE, OperationType.TRANSFER_OUT,
            OperationType.CASH_WITHDRAWAL, OperationType.SUBSCRIPTION -> Flow.EXPENSE
            OperationType.TRANSFER_IN, OperationType.CASH_DEPOSIT,
            OperationType.CASHBACK -> Flow.INCOME
            OperationType.OWN_ACCOUNT_TRANSFER -> Flow.INTERNAL
            OperationType.REFUND, OperationType.REVERSAL -> Flow.REVIEW
            OperationType.BALANCE_NOTICE -> Flow.NONE
            OperationType.UNKNOWN -> Flow.REVIEW
        }

        if (type == OperationType.REFUND && amount == null) reasons += ReviewReason.AMBIGUOUS_AMOUNT
        if (type == OperationType.REFUND) reasons += ReviewReason.REFUND_WITHOUT_ORIGINAL
        if (type == OperationType.REVERSAL) reasons += ReviewReason.AMBIGUOUS_OPERATION
        if (type == OperationType.UNKNOWN) reasons += ReviewReason.AMBIGUOUS_OPERATION
        if (type == OperationType.OWN_ACCOUNT_TRANSFER) reasons += ReviewReason.POSSIBLE_OWN_ACCOUNT_TRANSFER

        if (amount == null && type !in setOf(OperationType.BALANCE_NOTICE, OperationType.UNKNOWN)) {
            flow = Flow.REVIEW
            reasons += ReviewReason.AMBIGUOUS_AMOUNT
        }
        if (type == OperationType.UNKNOWN && hasAmountButNoDirection(normalized)) {
            reasons += ReviewReason.AMBIGUOUS_DIRECTION
        }

        val category = when {
            flow != Flow.EXPENSE -> null
            type == OperationType.TRANSFER_OUT -> "Другое"
            else -> resolveExpenseCategory(merchant, normalized)
        }
        val stableId = input.bankReferenceId?.takeIf { it.isNotBlank() }
            ?: input.notificationKey?.takeIf { it.isNotBlank() }
        val dedupeKey = stableId?.let { "tbank:${input.packageName}:${it}" }

        val confidence = when {
            type == OperationType.UNKNOWN -> 0.0
            type in setOf(OperationType.REFUND, OperationType.REVERSAL, OperationType.OWN_ACCOUNT_TRANSFER) -> 0.65
            amount == null && type != OperationType.BALANCE_NOTICE -> 0.45
            merchant != null || type == OperationType.BALANCE_NOTICE -> 0.92
            else -> 0.82
        }

        return ParsedEvent(
            operationType = type,
            flow = flow,
            amountKopecks = amount,
            currency = currency,
            merchant = merchant,
            category = category,
            observedAtMillis = input.postTimeMillis,
            notificationKey = input.notificationKey,
            bankReferenceId = input.bankReferenceId,
            maskedAccount = input.maskedAccount,
            reviewReasons = reasons.distinct(),
            confidence = confidence,
            safeDeduplicationKey = dedupeKey
        )
    }

    private fun classify(s: String): OperationType = when {
        containsAny(s, "кэшбэк", "кешбэк", "cashback", "начислен бонус", "бонусы за покуп") -> OperationType.CASHBACK
        containsAny(s, "возврат", "возвращено", "вернули", "refund") -> OperationType.REFUND
        containsAny(s, "отмен", "аннулирован", "операция отклонена", "реверс", "reversal") -> OperationType.REVERSAL
        containsAny(s, "между своими счетами", "перевод между своими", "перевод себе", "с собственного счета на свой") -> OperationType.OWN_ACCOUNT_TRANSFER
        containsAny(s, "снятие наличных", "выдача наличных", "cash withdrawal") -> OperationType.CASH_WITHDRAWAL
        containsAny(s, "внесение наличных", "пополнение наличными", "cash deposit") -> OperationType.CASH_DEPOSIT
        containsAny(s, "подписка", "subscription", "ежемесячное списание") -> OperationType.SUBSCRIPTION
        containsAny(s, "баланс", "остаток", "доступно на счете", "доступно на карте") &&
            !containsAny(s, "покупка", "оплата", "списание", "перевод") -> OperationType.BALANCE_NOTICE
        containsAny(s, "поступление", "зачисление", "вам перевели", "перевод от", "входящий перевод", "получен перевод") -> OperationType.TRANSFER_IN
        containsAny(s, "перевод", "перевели", "перечисление") &&
            containsAny(s, "кому:", "получатель", "перевод на", "отправлен", "списано") -> OperationType.TRANSFER_OUT
        containsAny(s, "покупка", "оплата", "оплачено", "списание", "платеж", "платёж", "purchase", "payment") -> OperationType.PURCHASE
        else -> OperationType.UNKNOWN
    }

    private fun resolveExpenseCategory(merchant: String?, normalized: String): String {
        val m = normalize(merchant.orEmpty())
        val combined = "$m $normalized"
        return when {
            containsAny(combined, "аптека", "аптеки", "фарма", "лекарств") -> "Здоровье"
            containsAny(combined, "газпромнефть", "азс", "заправ", "бензин", "автосервис", "автомой", "шиномонтаж") -> "Транспорт"
            containsAny(combined, "кула бар", "kula bar", "перцы", "genatcvale", "торговый павильон", "ресторан", "кафе", "бар", "фастфуд") -> "Кафе и развлечения"
            containsAny(combined, "магнит", "русский разгуляйка", "imperiya", "пятероч", "пятёроч", "супермаркет", "продукт") -> "Продукты"
            containsAny(combined, "ук перспектива", "сибирская теплосбытовая", "ук жск", "гис жкх", "жкх", "коммуналь") -> "Жильё и ЖКХ"
            containsAny(combined, "связь", "мобильн", "оператор", "телефон", "интернет") -> "Жильё и ЖКХ"
            containsAny(combined, "цифров", "наличные", "красот", "салон", "перевод", "переводы", "получатель") -> "Личные расходы"
            else -> "Другое"
        }
    }

    private fun extractMerchant(raw: String, normalized: String): String? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val candidates = lines.flatMap { line ->
            listOf(
                Regex("""(?i)(?:покупка|оплата|оплачено|перевод на|перевод от|списание)\s*[:—-]?\s*(.+?)(?=\s+(?:\d[\d\s.,]*\s*(?:₽|руб)|карта\s*\*|\*\d{4})|$)""").find(line)?.groupValues?.get(1),
                Regex("""(?i)(?:в|у)\s+([A-Za-zА-Яа-яЁё0-9 .&'-]{3,40})\s*$""").find(line)?.groupValues?.get(1)
            )
        }.filterNotNull().map { it.trim(' ', '.', ',', ':', '—', '-') }
            .filter { it.length >= 2 && !it.matches(Regex("""(?i)(\d[\d\s.,]*\s*(₽|руб.*)|карта.*)""")) }
        return candidates.firstOrNull()?.take(80)
    }

    private fun parseAmountKopecks(raw: String): Long? {
        val matches = Regex(
            """(?<![\p{L}\d])(?:−|-)?\s*(\d{1,3}(?:[\s\u00A0]\d{3})+|\d+)(?:[.,](\d{1,2}))?\s*(?:₽|руб(?:\.|лей|ля)?|RUB)""",
            RegexOption.IGNORE_CASE
        ).findAll(raw).toList()
        if (matches.size != 1) return null
        val match = matches.single()
        val whole = match.groupValues[1].replace(Regex("""[\s\u00A0]"""), "")
        val fraction = match.groupValues[2].ifBlank { "0" }.padEnd(2, '0')
        return runCatching {
            BigDecimal(whole).add(BigDecimal("0.$fraction"))
                .movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
        }.getOrNull()
    }

    private fun hasAmountButNoDirection(s: String): Boolean =
        containsAny(s, "руб", "₽", "rub") && !containsAny(s, "покупка", "оплата", "списание", "перевод", "поступление", "зачисление", "возврат")

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale("ru", "RU")).replace('ё', 'е'), Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), " ").trim()

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any { normalize(value).contains(normalize(it)) }
}
