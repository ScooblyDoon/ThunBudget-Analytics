package ru.scooblydoon.thunbudget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class RecurringPayment(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val amount: Double,
    val category: String,
    val dayOfMonth: Int,
    val active: Boolean = true,
    val completedMonths: Set<String> = emptySet()
) {
    init {
        require(title.isNotBlank())
        require(amount.isFinite() && amount > 0.0)
        require(category.isNotBlank())
        require(dayOfMonth in 1..31)
    }

    fun dueDate(month: YearMonth): LocalDate = month.atDay(dayOfMonth.coerceAtMost(month.lengthOfMonth()))
    fun isCompleted(month: YearMonth): Boolean = month.toString() in completedMonths

    fun canConfirm(month: YearMonth, today: LocalDate): Boolean =
        active && !dueDate(month).isAfter(today) && !isCompleted(month)
}

class RecurringPaymentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("thunbudget", Context.MODE_PRIVATE)
    private val key = "recurring_payments_v1"

    fun load(): List<RecurringPayment> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val completed = row.optJSONArray("completedMonths") ?: JSONArray()
            RecurringPayment(
                id = row.getString("id"),
                title = row.getString("title"),
                amount = row.getDouble("amount"),
                category = row.getString("category"),
                dayOfMonth = row.getInt("dayOfMonth"),
                active = row.optBoolean("active", true),
                completedMonths = (0 until completed.length()).map { completed.getString(it) }.toSet()
            )
        }
    }.getOrDefault(emptyList())

    fun save(items: List<RecurringPayment>) {
        require(items.map { it.id }.distinct().size == items.size)
        val array = JSONArray()
        items.forEach { item ->
            val completed = JSONArray()
            item.completedMonths.sorted().forEach(completed::put)
            array.put(JSONObject()
                .put("id", item.id).put("title", item.title).put("amount", item.amount)
                .put("category", item.category).put("dayOfMonth", item.dayOfMonth)
                .put("active", item.active).put("completedMonths", completed))
        }
        check(prefs.edit().putString(key, array.toString()).commit()) { "Не удалось сохранить регулярные платежи." }
    }

    fun pendingFor(date: LocalDate): List<RecurringPayment> = load().filter {
        it.active && it.dueDate(YearMonth.from(date)) <= date && !it.isCompleted(YearMonth.from(date))
    }

    fun markCompleted(id: String, month: YearMonth): Boolean {
        val rows = load()
        val target = rows.firstOrNull { it.id == id } ?: return false
        save(rows.map { if (it.id == id) it.copy(completedMonths = it.completedMonths + month.toString()) else it })
        return true
    }
}

internal data class RecurringPaymentReminder(
    val payment: RecurringPayment,
    val dueDate: LocalDate,
    val isDayBefore: Boolean
)

/**
 * Returns only the two intended reminder moments: the calendar day before
 * a payment and the payment date itself. Overdue payments are deliberately excluded.
 */
internal fun recurringPaymentRemindersFor(
    payments: List<RecurringPayment>,
    today: LocalDate
): List<RecurringPaymentReminder> {
    val tomorrow = today.plusDays(1)
    return payments.asSequence()
        .filter { it.active }
        .flatMap { payment ->
            sequenceOf(today, tomorrow).mapNotNull { targetDate ->
                val month = YearMonth.from(targetDate)
                val dueDate = payment.dueDate(month)
                if (dueDate != targetDate || payment.isCompleted(month)) {
                    null
                } else {
                    RecurringPaymentReminder(payment, dueDate, isDayBefore = targetDate == tomorrow)
                }
            }
        }
        .sortedWith(compareBy<RecurringPaymentReminder> { it.dueDate }.thenBy { it.payment.title })
        .toList()
}