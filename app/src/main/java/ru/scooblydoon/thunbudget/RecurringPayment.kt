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
