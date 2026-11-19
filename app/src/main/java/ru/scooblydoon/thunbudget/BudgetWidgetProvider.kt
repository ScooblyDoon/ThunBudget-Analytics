package ru.scooblydoon.thunbudget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

class BudgetWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, BudgetWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("thunbudget", Context.MODE_PRIVATE)
            val today = LocalDate.now()
            val baseMonth = if (today.dayOfMonth <= 8) today.minusMonths(1) else today
            val defaultStart = if (today.dayOfMonth <= 8 || today.dayOfMonth >= 24)
                baseMonth.withDayOfMonth(24) else baseMonth.withDayOfMonth(9)
            val defaultEnd = when {
                today.dayOfMonth <= 8 -> today.withDayOfMonth(8)
                today.dayOfMonth >= 24 -> today.plusMonths(1).withDayOfMonth(8)
                else -> baseMonth.withDayOfMonth(23)
            }
            val start = runCatching { LocalDate.parse(prefs.getString("period_start", null)) }.getOrNull() ?: defaultStart
            val end = runCatching { LocalDate.parse(prefs.getString("period_end", null)) }.getOrNull() ?: defaultEnd

            val periodIncome = runCatching {
                val rows = JSONArray(prefs.getString("period_incomes", "[]") ?: "[]")
                (0 until rows.length()).map { rows.getJSONObject(it) }
                    .firstOrNull { it.optString("start") == start.toString() && it.optString("end") == end.toString() }
                    ?.optDouble("amount", 0.0) ?: 0.0
            }.getOrDefault(0.0)
            val displayedIncome = periodIncome.takeIf { it > 0.0 }
                ?: (prefs.getString("income", "100000")?.toDoubleOrNull() ?: 0.0)

            val spent = runCatching {
                val rows = JSONArray(prefs.getString("expenses", "[]") ?: "[]")
                (0 until rows.length()).sumOf { index ->
                    val row = rows.getJSONObject(index)
                    val date = row.optString("date")
                    if (date >= start.toString() && date <= end.toString()) row.optDouble("amount", 0.0) else 0.0
                }
            }.getOrDefault(0.0)

            val percentTotal = runCatching {
                val rows = JSONArray(prefs.getString("categories", null) ?: defaultCategoriesJson())
                (0 until rows.length()).sumOf { rows.getJSONObject(it).optDouble("percent", 0.0) }
            }.getOrDefault(100.0)

            val planned = if (periodIncome > 0.0) {
                periodIncome * percentTotal / 100.0
            } else {
                var day = start
                var total = 0.0
                while (!day.isAfter(end)) {
                    total += displayedIncome * percentTotal / 100.0 / day.lengthOfMonth()
                    day = day.plusDays(1)
                }
                total
            }
            val remaining = (planned - spent).coerceAtLeast(0.0)
            val daysLeft = if (end.isBefore(today) || end.isBefore(start)) 0L
                else ChronoUnit.DAYS.between(maxOf(today, start), end) + 1L
            val average = if (daysLeft > 0) remaining / daysLeft else 0.0

            val views = RemoteViews(context.packageName, R.layout.widget_budget)
            views.setTextViewText(R.id.widget_period, "Период: ${start.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"))} – ${end.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM"))}")
            views.setTextViewText(R.id.widget_expenses, formatMoney(spent))
            views.setTextViewText(R.id.widget_income, formatMoney(displayedIncome))
            views.setTextViewText(R.id.widget_daily, formatMoney(average))
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                val pending = PendingIntent.getActivity(context, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_root, pending)
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun formatMoney(value: Double): String =
            String.format(Locale("ru", "RU"), "%,.0f ₽", value)

        private fun defaultCategoriesJson() =
            """[{"name":"Продукты","percent":25},{"name":"Транспорт","percent":10},{"name":"Жильё и ЖКХ","percent":20},{"name":"Кафе и развлечения","percent":10},{"name":"Покупки","percent":10},{"name":"Здоровье","percent":5},{"name":"Накопления","percent":10},{"name":"Прочее","percent":10}]"""
    }
}
