package ru.scooblydoon.thunbudget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class PeriodIncome(val start: String, val end: String, val amount: Double)

internal class BudgetStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("thunbudget", Context.MODE_PRIVATE)
    private val incomeLedger = IncomeLedgerRepository(prefs)

    init {
        incomeLedger.migrateLegacyIfNeeded()
        // Never replace the last good backup with data that fails integrity checks.
        if (!isCurrentDataValid() && hasAutomaticBackup()) restoreAutomaticBackup()
    }

    fun externalBackupFolder(): String? = prefs.getString(ExternalBackupSettings.URI_KEY, null)
    fun externalBackupLastSuccess(): String? = prefs.getString(ExternalBackupSettings.LAST_SUCCESS_KEY, null)
    fun externalBackupLastError(): String? = prefs.getString(ExternalBackupSettings.LAST_ERROR_KEY, null)

    fun setExternalBackupFolder(uri: String?) {
        val editor = prefs.edit()
        if (uri.isNullOrBlank()) editor.remove(ExternalBackupSettings.URI_KEY).remove(ExternalBackupSettings.LAST_SUCCESS_KEY).remove(ExternalBackupSettings.LAST_ERROR_KEY)
        else editor.putString(ExternalBackupSettings.URI_KEY, uri).remove(ExternalBackupSettings.LAST_SUCCESS_KEY).remove(ExternalBackupSettings.LAST_ERROR_KEY)
        check(editor.commit()) { "Не удалось сохранить настройки внешнего резервирования." }
        if (!uri.isNullOrBlank()) ExternalBackupSettings.schedule(appContext)
    }

    fun validatedBackupSnapshot(): String? = exportBackup().takeIf(::isBackupSnapshotValid)

    fun loadDarkTheme(): Boolean = prefs.getBoolean("dark_theme", false)
    fun saveDarkTheme(enabled: Boolean) { prefs.edit().putBoolean("dark_theme", enabled).apply() }
    fun loadDashboardOrder(): List<String> {
        val defaults = listOf("period", "budget", "income", "categories")
        val saved = runCatching {
            val array = JSONArray(prefs.getString("dashboard_order", "[]") ?: "[]")
            (0 until array.length()).map { array.getString(it) }.filter { it in defaults }.distinct()
        }.getOrDefault(emptyList())
        return saved + defaults.filterNot { it in saved }
    }
    fun saveDashboardOrder(order: List<String>) {
        val allowed = setOf("period", "budget", "income", "categories")
        require(order.size == allowed.size && order.toSet() == allowed)
        val array = JSONArray()
        order.forEach { array.put(it) }
        check(prefs.edit().putString("dashboard_order", array.toString()).commit())
        saveAutomaticBackup()
    }
    fun loadNavigationOrder(): List<Int> {
        val defaults = (0..5).toList()
        val saved = runCatching {
            val array = JSONArray(prefs.getString("navigation_order", "[]") ?: "[]")
            (0 until array.length()).map { array.getInt(it) }.filter { it in defaults }.distinct()
        }.getOrDefault(emptyList())
        return saved + defaults.filterNot { it in saved }
    }
    fun saveNavigationOrder(order: List<Int>) {
        require(order.size == 6 && order.toSet() == (0..5).toSet())
        val array = JSONArray()
        order.forEach { array.put(it) }
        check(prefs.edit().putString("navigation_order", array.toString()).commit())
        saveAutomaticBackup()
    }

    fun loadIncomeEntries(): List<IncomeEntry> = runCatching { incomeLedger.load() }.getOrDefault(emptyList())

    fun loadPeriodIncomes(): List<PeriodIncome> {
        if (incomeLedger.migrateLegacyIfNeeded()) {
            val entries = runCatching { incomeLedger.load() }.getOrNull()
            if (entries != null) return entries.groupBy { it.periodStart to it.periodEnd }
                .map { (period, rows) -> PeriodIncome(period.first, period.second, rows.sumOf { it.amount }) }
                .sortedBy { it.start }
        }
        return runCatching {
            val a = JSONArray(prefs.getString("period_incomes", "[]") ?: "[]")
            (0 until a.length()).map { val o=a.getJSONObject(it); PeriodIncome(o.getString("start"),o.getString("end"),o.getDouble("amount")) }
        }.getOrDefault(emptyList())
    }

    private fun writeLegacyIncomeMirror() {
        val a = JSONArray()
        loadPeriodIncomes().forEach { a.put(JSONObject().put("start",it.start).put("end",it.end).put("amount",it.amount)) }
        prefs.edit().putString("period_incomes",a.toString()).commit()
    }

    fun addIncomeEntry(title: String, amount: Double, date: String, start: String, end: String) {
        incomeLedger.add(title, amount, LocalDate.parse(date), LocalDate.parse(start), LocalDate.parse(end))
        writeLegacyIncomeMirror()
        saveAutomaticBackup()
    }

    fun setPeriodIncomeTotal(start: String, end: String, amount: Double) {
        require(amount.isFinite() && amount >= 0.0)
        val retained = incomeLedger.load().filterNot { it.periodStart == start && it.periodEnd == end }
        val replacement = if (amount > 0.0) retained + IncomeEntry(
            id = "manual-$start-$end", title = "Доход за период", amount = amount,
            date = end, periodStart = start, periodEnd = end
        ) else retained
        check(incomeLedger.save(replacement)) { "Не удалось обновить доход за период." }
        writeLegacyIncomeMirror()
        saveAutomaticBackup()
    }

    fun updateIncomeEntry(entry: IncomeEntry) {
        check(incomeLedger.update(entry)) { "Не удалось изменить доход." }
        writeLegacyIncomeMirror()
        saveAutomaticBackup()
    }

    fun deleteIncomeEntry(id: String) {
        check(incomeLedger.delete(id)) { "Не удалось удалить доход." }
        writeLegacyIncomeMirror()
        saveAutomaticBackup()
    }

    fun savePeriodIncomes(items: List<PeriodIncome>) {
        val a=JSONArray(); items.forEach { a.put(JSONObject().put("start",it.start).put("end",it.end).put("amount",it.amount)) }
        prefs.edit().putString("period_incomes",a.toString()).commit()
        saveAutomaticBackup()
    }
    fun loadIncome() = prefs.getString("income", "100000")!!.toDoubleOrNull() ?: 100000.0
    fun saveIncome(value: Double) { prefs.edit().putString("income", value.toString()).commit(); saveAutomaticBackup() }
    fun loadSavingsGoal() = prefs.getString("savings", "10")!!.toDoubleOrNull() ?: 10.0
    fun saveSavingsGoal(value: Double) { prefs.edit().putString("savings", value.toString()).commit(); saveAutomaticBackup() }
    fun loadPeriodStart() = prefs.getString("period_start", null)
    fun loadPeriodEnd() = prefs.getString("period_end", null)
    fun savePeriod(start:String,end:String) { prefs.edit().putString("period_start",start).putString("period_end",end).commit(); saveAutomaticBackup() }
    fun loadFirstPeriodStartDay(): Int = (prefs.getString("reporting_first_start_day", "9")?.toIntOrNull() ?: 9).coerceIn(1, 31)
    fun loadFirstPeriodEndDay(): Int = (prefs.getString("reporting_first_end_day", "23")?.toIntOrNull() ?: 23).coerceIn(1, 31)
    fun saveReportingPeriodDays(startDay: Int, endDay: Int) {
        require(startDay in 1..31 && endDay in startDay..31)
        check(prefs.edit().putString("reporting_first_start_day", startDay.toString()).putString("reporting_first_end_day", endDay.toString()).commit())
        saveAutomaticBackup()
    }
    fun loadCategories(): List<BudgetCategory> {
        val raw = prefs.getString("categories", null) ?: return listOf(
            BudgetCategory("Продукты", 25.0), BudgetCategory("Транспорт", 10.0),
            BudgetCategory("Жильё и ЖКХ", 20.0), BudgetCategory("Кафе и развлечения", 10.0),
            BudgetCategory("Покупки", 10.0), BudgetCategory("Здоровье", 5.0),
            BudgetCategory("Накопления", 10.0), BudgetCategory("Прочее", 10.0)
        )
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { val o=a.getJSONObject(it); BudgetCategory(o.getString("name"),o.getDouble("percent")) }
        }.getOrDefault(emptyList())
    }
    fun loadAutoAllocationEnabled(): Boolean = prefs.getBoolean("analytics_auto_allocation", false)
    fun saveAutoAllocationEnabled(enabled: Boolean) { prefs.edit().putBoolean("analytics_auto_allocation", enabled).commit() }
    fun loadManualAllocationSnapshot(): List<BudgetCategory>? = runCatching {
        val raw = prefs.getString("analytics_manual_allocation_snapshot", null) ?: return null
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            BudgetCategory(row.getString("name"), row.getDouble("percent"))
        }
    }.getOrNull()
    fun saveManualAllocationSnapshot(items: List<BudgetCategory>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("name", it.name).put("percent", it.percent)) }
        check(prefs.edit().putString("analytics_manual_allocation_snapshot", array.toString()).commit())
    }
    fun saveCategories(items: List<BudgetCategory>) {
        val normalizedPairs = BudgetAllocationCalculator.withReserve(items.map { it.name to it.percent })
        val normalized = normalizedPairs.map { BudgetCategory(it.first, it.second) }
        persistCategories(normalized)
    }

    /**
     * Restores a previously saved manual allocation verbatim. Unlike saveCategories(),
     * this must not recalculate the reserve: the snapshot is the source of truth.
     */
    fun restoreCategoriesExactly(items: List<BudgetCategory>) {
        require(items.map { it.name.lowercase(Locale.ROOT) }.distinct().size == items.size) {
            "В снимке ручных долей есть повторяющиеся категории."
        }
        require(items.all { it.name.isNotBlank() && it.percent.isFinite() && it.percent >= 0.0 }) {
            "Снимок ручных долей содержит некорректные значения."
        }
        persistCategories(items)
    }

    private fun persistCategories(items: List<BudgetCategory>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("name", it.name).put("percent", it.percent)) }
        check(prefs.edit().putString("categories", a.toString()).commit()) {
            "Не удалось сохранить доли категорий."
        }
        saveAutomaticBackup()
    }
    fun loadRecurringPayments(): List<RecurringPayment> = RecurringPaymentStore(appContext).load()
    fun saveRecurringPayments(items: List<RecurringPayment>) { RecurringPaymentStore(appContext).save(items); saveAutomaticBackup() }

    fun loadExpenses(): List<Expense> {
        val raw=prefs.getString("expenses","[]") ?: "[]"
        return runCatching {
            val a=JSONArray(raw); (0 until a.length()).map { val o=a.getJSONObject(it); Expense(o.getLong("id"),o.getString("title"),o.getString("category"),o.getDouble("amount"),o.getString("date")) }.sortedByDescending { it.date }
        }.getOrDefault(emptyList())
    }
    fun saveExpenses(items: List<Expense>) {
        require(items.map { it.id }.distinct().size == items.size) { "Повторяется идентификатор расхода." }
        require(items.all { it.title.isNotBlank() && it.category.isNotBlank() && it.amount.isFinite() && it.amount > 0.0 && runCatching { LocalDate.parse(it.date) }.isSuccess }) {
            "Обнаружены некорректные данные расхода."
        }
        val a=JSONArray(); items.forEach { a.put(JSONObject().put("id",it.id).put("title",it.title).put("category",it.category).put("amount",it.amount).put("date",it.date)) }
        check(prefs.edit().putString("expenses",a.toString()).commit()) { "Не удалось сохранить расходы." }
        saveAutomaticBackup()
    }

    fun prepareUndoSnapshot() {
        val keys = listOf("expenses", "categories", "period_incomes", "income_entries", "income", "savings", "period_start", "period_end", "recurring_payments_v1")
        val snapshot = JSONObject()
        keys.forEach { key ->
            val value = prefs.getString(key, null)
            if (value != null) snapshot.put(key, value)
            else snapshot.put(key, JSONObject.NULL)
        }
        prefs.edit().putString("_undo_snapshot", snapshot.toString()).commit()
    }

    fun hasUndoSnapshot(): Boolean = !prefs.getString("_undo_snapshot", null).isNullOrBlank()

    fun restoreUndoSnapshot(): Boolean = runCatching {
        val raw = prefs.getString("_undo_snapshot", null) ?: return false
        val snapshot = JSONObject(raw)
        val editor = prefs.edit()
        val keys = snapshot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = snapshot.get(key)
            if (value == JSONObject.NULL) editor.remove(key)
            else {
                require(value is String) { "Некорректная запись отмены." }
                editor.putString(key, value)
            }
        }
        editor.remove("_undo_snapshot")
        require(editor.commit()) { "Не удалось восстановить предыдущее состояние." }
        saveAutomaticBackup()
        true
    }.getOrDefault(false)

    fun resetAllData(): Boolean = runCatching {
        val rawFolder = prefs.getString(ExternalBackupSettings.URI_KEY, null)
        if (!rawFolder.isNullOrBlank()) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(rawFolder),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        check(prefs.edit().clear().commit()) { "Не удалось полностью очистить локальные данные." }
        saveAutomaticBackup()
        BudgetWidgetProvider.refreshAll(appContext)
        true
    }.getOrDefault(false)

    fun exportBackup(): String {
        val data = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith("_auto_backup") && key != "_pre_restore_backup" && key != "_undo_snapshot" && key != ExternalBackupSettings.URI_KEY && key != ExternalBackupSettings.LAST_SUCCESS_KEY && key != ExternalBackupSettings.LAST_ERROR_KEY) {
                when (value) {
                    is String -> data.put(key, value)
                    is Boolean -> data.put(key, value)
                }
            }
        }
        if (!data.has("expenses")) data.put("expenses", "[]")
        if (!data.has("income")) data.put("income", "100000")
        if (!data.has("savings")) data.put("savings", "10")
        if (!data.has("period_incomes")) data.put("period_incomes", "[]")
        if (!data.has("categories")) {
            val defaults = JSONArray()
            loadCategories().forEach { defaults.put(JSONObject().put("name", it.name).put("percent", it.percent)) }
            data.put("categories", defaults.toString())
        }
        return JSONObject()
            .put("app", "ThunBudget")
            .put("schemaVersion", 1)
            .put("createdAt", java.time.Instant.now().toString())
            .put("data", data)
            .toString(2)
    }

    fun restoreBackup(json: String): Boolean = runCatching {
        val root = JSONObject(json)
        require(root.getString("app") == "ThunBudget") { "Это не резервная копия ThunBudget." }
        require(root.getInt("schemaVersion") == 1) { "Версия резервной копии не поддерживается." }
        val data = root.getJSONObject("data")
        require(isBackupSnapshotValid(JSONObject().put("app", "ThunBudget").put("schemaVersion", 1).put("data", data).toString())) {
            "В резервной копии обнаружены некорректные или противоречивые данные."
        }
        require(data.has("expenses") && data.has("categories") && data.has("income") && data.has("savings")) {
            "В резервной копии отсутствуют обязательные данные."
        }
        val expenseArray = JSONArray(data.getString("expenses"))
        for (i in 0 until expenseArray.length()) {
            val item = expenseArray.getJSONObject(i)
            require(item.getString("title").isNotBlank() && item.getString("category").isNotBlank())
            require(item.getDouble("amount").isFinite() && item.getDouble("amount") > 0.0)
            LocalDate.parse(item.getString("date"))
        }
        val categoryArray = JSONArray(data.getString("categories"))
        require(categoryArray.length() > 0) { "В резервной копии нет категорий." }
        for (i in 0 until categoryArray.length()) {
            val item = categoryArray.getJSONObject(i)
            require(item.getString("name").isNotBlank())
            require(item.getDouble("percent").isFinite() && item.getDouble("percent") >= 0.0)
        }
        val incomeArray = JSONArray(data.optString("period_incomes", "[]"))
        for (i in 0 until incomeArray.length()) {
            val item = incomeArray.getJSONObject(i)
            val start = LocalDate.parse(item.getString("start"))
            val end = LocalDate.parse(item.getString("end"))
            require(!end.isBefore(start) && item.getDouble("amount").isFinite() && item.getDouble("amount") >= 0.0)
        }
        val income = data.getString("income").toDoubleOrNull()
        val savings = data.getString("savings").toDoubleOrNull()
        require(income != null && income.isFinite() && income > 0.0 && savings != null && savings.isFinite() && savings in 0.0..100.0) {
            "В резервной копии обнаружены некорректные настройки бюджета."
        }
        // Снимок текущих данных сохраняется до замены, чтобы можно было отменить ошибочное восстановление.
        val safetyBackup = exportBackup()
        val retainedAutoBackups = prefs.all
            .filterKeys { it == "_auto_backup" || Regex("""^_auto_backup_[1-5]$""").matches(it) }
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
        val externalFolder = prefs.getString(ExternalBackupSettings.URI_KEY, null)
        val externalLastSuccess = prefs.getString(ExternalBackupSettings.LAST_SUCCESS_KEY, null)
        val externalLastError = prefs.getString(ExternalBackupSettings.LAST_ERROR_KEY, null)
        val editor = prefs.edit().clear().putString("_pre_restore_backup", safetyBackup)
        retainedAutoBackups.forEach { (key, value) -> editor.putString(key, value) }
        if (!externalFolder.isNullOrBlank()) editor.putString(ExternalBackupSettings.URI_KEY, externalFolder)
        if (!externalLastSuccess.isNullOrBlank()) editor.putString(ExternalBackupSettings.LAST_SUCCESS_KEY, externalLastSuccess)
        if (!externalLastError.isNullOrBlank()) editor.putString(ExternalBackupSettings.LAST_ERROR_KEY, externalLastError)
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(key in setOf("expenses", "categories", "income", "savings", "period_start", "period_end", "period_incomes", "income_entries", "income_ledger_migration_v1", "dark_theme", "recurring_payments_v1")) {
                "В резервной копии обнаружено неизвестное поле."
            }
            val value = data.get(key)
            when (value) {
                is String -> if (key != "_pre_restore_backup") editor.putString(key, value)
                is Boolean -> if (key == "dark_theme" || key == IncomeLedgerRepository.MIGRATION_KEY) editor.putBoolean(key, value) else error("Некорректный формат данных резервной копии.")
                else -> error("Некорректный формат данных резервной копии.")
            }
        }
        require(editor.commit()) { "Не удалось записать восстановленные данные." }
        saveAutomaticBackup()
        true
    }.getOrDefault(false)

    private fun isBackupSnapshotValid(snapshot: String?): Boolean = runCatching {
        require(!snapshot.isNullOrBlank())
        val root = JSONObject(snapshot)
        require(root.optString("app") == "ThunBudget" && root.optInt("schemaVersion") == 1)
        val data = root.getJSONObject("data")
        val expenses = JSONArray(data.getString("expenses"))
        val expenseIds = mutableSetOf<Long>()
        for (i in 0 until expenses.length()) {
            val row = expenses.getJSONObject(i)
            require(expenseIds.add(row.getLong("id"))) { "Повторяется идентификатор расхода." }
            require(row.getString("title").isNotBlank() && row.getString("category").isNotBlank())
            require(row.getDouble("amount").isFinite() && row.getDouble("amount") > 0.0)
            LocalDate.parse(row.getString("date"))
        }
        val categories = JSONArray(data.getString("categories"))
        require(categories.length() > 0)
        val categoryNames = mutableSetOf<String>()
        var categoryPercentTotal = 0.0
        for (i in 0 until categories.length()) {
            val row = categories.getJSONObject(i)
            require(row.getString("name").isNotBlank() && categoryNames.add(row.getString("name").trim().lowercase()))
            val percent = row.getDouble("percent")
            require(percent.isFinite() && percent >= 0.0)
            categoryPercentTotal += percent
        }
        require(categoryPercentTotal <= 100.0)
        val recurringPayments = JSONArray(data.optString("recurring_payments_v1", "[]"))
        val recurringIds = mutableSetOf<String>()
        for (i in 0 until recurringPayments.length()) {
            val row = recurringPayments.getJSONObject(i)
            require(row.getString("id").isNotBlank() && recurringIds.add(row.getString("id")))
            require(row.getString("title").isNotBlank() && row.getString("category").isNotBlank())
            require(row.getDouble("amount").isFinite() && row.getDouble("amount") > 0.0)
            require(row.getInt("dayOfMonth") in 1..31)
            val completed = row.optJSONArray("completedMonths") ?: JSONArray()
            for (j in 0 until completed.length()) require(runCatching { YearMonth.parse(completed.getString(j)) }.isSuccess)
        }
        val periodIncomes = JSONArray(data.optString("period_incomes", "[]"))
        for (i in 0 until periodIncomes.length()) {
            val row = periodIncomes.getJSONObject(i)
            val start = LocalDate.parse(row.getString("start"))
            val end = LocalDate.parse(row.getString("end"))
            require(!end.isBefore(start) && row.getDouble("amount").isFinite() && row.getDouble("amount") >= 0.0)
        }
        val incomeEntries = JSONArray(data.optString("income_entries", "[]"))
        val incomeIds = mutableSetOf<String>()
        for (i in 0 until incomeEntries.length()) {
            val row = incomeEntries.getJSONObject(i)
            require(row.getString("id").isNotBlank() && incomeIds.add(row.getString("id")))
            require(row.getString("title").isNotBlank())
            require(row.getDouble("amount").isFinite() && row.getDouble("amount") > 0.0)
            val date = LocalDate.parse(row.getString("date"))
            val start = LocalDate.parse(row.getString("periodStart"))
            val end = LocalDate.parse(row.getString("periodEnd"))
            require(!end.isBefore(start) && !date.isBefore(start) && !date.isAfter(end))
        }
        val monthlyIncome = data.getString("income").toDoubleOrNull()
        val savings = data.getString("savings").toDoubleOrNull()
        require(monthlyIncome != null && monthlyIncome.isFinite() && monthlyIncome > 0.0)
        require(savings != null && savings.isFinite() && savings in 0.0..100.0)
        val configuredStart = data.optString("period_start")
        val configuredEnd = data.optString("period_end")
        if (configuredStart.isNotBlank() || configuredEnd.isNotBlank()) {
            val start = LocalDate.parse(configuredStart)
            val end = LocalDate.parse(configuredEnd)
            require(!end.isBefore(start))
        }
        true
    }.getOrDefault(false)

    private fun isCurrentDataValid(): Boolean = isBackupSnapshotValid(exportBackup())

    fun saveAutomaticBackup(scheduleExternal: Boolean = true) {
        val snapshot = exportBackup()
        if (!isBackupSnapshotValid(snapshot)) return
        val previous = prefs.getString("_auto_backup", null)
        val editor = prefs.edit()
        for (slot in 5 downTo 1) {
            val candidate = if (slot == 1) previous else prefs.getString("_auto_backup_${slot - 1}", null)
            if (isBackupSnapshotValid(candidate)) editor.putString("_auto_backup_${slot}", candidate)
        }
        editor.putString("_auto_backup", snapshot)
            .putString("_auto_backup_date", LocalDate.now().toString())
        val internalSaved = editor.commit()
        // External backup is scheduled independently so an internal storage failure
        // does not suppress the second protection layer.
        if (scheduleExternal && !externalBackupFolder().isNullOrBlank()) runCatching { ExternalBackupSettings.schedule(appContext) }
        check(internalSaved) { "Не удалось сохранить автоматическую резервную копию." }
        BudgetWidgetProvider.refreshAll(appContext)
    }

    fun automaticBackupCount(): Int =
        (listOf("_auto_backup") + (1..5).map { "_auto_backup_${it}" })
            .count { isBackupSnapshotValid(prefs.getString(it, null)) }

    fun hasAutomaticBackup(): Boolean = automaticBackupCount() > 0

    fun restoreAutomaticBackup(): Boolean {
        val snapshot = (listOf("_auto_backup") + (1..5).map { "_auto_backup_${it}" })
            .asSequence()
            .mapNotNull { prefs.getString(it, null) }
            .firstOrNull { isBackupSnapshotValid(it) } ?: return false
        return restoreBackup(snapshot)
    }

    fun hasPreviousRestoreBackup(): Boolean = !prefs.getString("_pre_restore_backup", null).isNullOrBlank()

    fun restorePreviousBackup(): Boolean {
        val previous = prefs.getString("_pre_restore_backup", null) ?: return false
        return restoreBackup(previous)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BudgetStore(applicationContext).saveAutomaticBackup(scheduleExternal = false)
        RecurringPaymentNotifications.schedule(applicationContext)
        setContent { ThunBudgetApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThunBudgetApp() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Use a compact layout on narrow, short, or enlarged-text screens.
    // A standard-width Pixel keeps the existing floating-action layout.
    val compactLayout = configuration.screenWidthDp < 400 ||
        configuration.screenHeightDp < 700 || density.fontScale >= 1.12f
    val store= remember { BudgetStore(context) }
    var income by remember { mutableDoubleStateOf(store.loadIncome()) }
    var savingsGoal by remember { mutableDoubleStateOf(store.loadSavingsGoal()) }
    var categories by remember { mutableStateOf(store.loadCategories()) }
    var expenses by remember { mutableStateOf(store.loadExpenses()) }
    var recurringPayments by remember { mutableStateOf(store.loadRecurringPayments()) }
    var periodIncomes by remember { mutableStateOf(store.loadPeriodIncomes()) }
    var incomeEntries by remember { mutableStateOf(store.loadIncomeEntries()) }
    var selectedIncomeEntry by remember { mutableStateOf<IncomeEntry?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var voiceDraft by remember { mutableStateOf<VoiceExpenseDraft?>(null) }
    var voiceAmount by remember { mutableStateOf("") }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    var showAssistantDialog by remember { mutableStateOf(false) }
    var assistantTextInput by remember { mutableStateOf("") }
    var assistantChartData by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var assistantChartTitle by remember { mutableStateOf("Аналитика расходов") }
    var voiceEditCandidates by remember { mutableStateOf<List<Expense>>(emptyList()) }
    var voiceSelectedExpenseId by remember { mutableStateOf<Long?>(null) }
    var voiceEditExpanded by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    val offlineSpeechRecognizer = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else null
    }
    DisposableEffect(offlineSpeechRecognizer) {
        onDispose { offlineSpeechRecognizer?.destroy() }
    }
    var ttsReady by remember { mutableStateOf(false) }
    val speechOutput = remember(context) {
        TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }
    LaunchedEffect(ttsReady) {
        if (ttsReady) speechOutput.language = Locale("ru", "RU")
    }
    LaunchedEffect(voiceMessage, ttsReady) {
        val response = voiceMessage
        if (ttsReady && !response.isNullOrBlank()) {
            speechOutput.speak(assistantSpeechText(response), TextToSpeech.QUEUE_FLUSH, null, "thunbudget_assistant_response")
        }
    }
    DisposableEffect(speechOutput) {
        onDispose { speechOutput.stop(); speechOutput.shutdown() }
    }
    var canUndo by remember { mutableStateOf(store.hasUndoSnapshot()) }
    var showUndoConfirmation by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var darkTheme by remember { mutableStateOf(store.loadDarkTheme()) }
    var showPeriodEditor by remember { mutableStateOf(false) }
    var showPeriodIncomeEditor by remember { mutableStateOf(false) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showAllocationDialog by remember { mutableStateOf(false) }
    var showDynamicAllocationDialog by remember { mutableStateOf(false) }
    var analyticsIncomeMode by rememberSaveable { mutableIntStateOf(0) }
    var autoAllocationEnabled by remember { mutableStateOf(store.loadAutoAllocationEnabled()) }
    var manualAllocationSnapshot by remember { mutableStateOf(store.loadManualAllocationSnapshot() ?: categories) }
    var editingCategory by remember { mutableStateOf<BudgetCategory?>(null) }
    var tab by remember { mutableIntStateOf(if ((context as? Activity)?.intent?.getBooleanExtra("open_recurring_payments", false) == true) 5 else 0) }
    var navigationOrder by remember { mutableStateOf(store.loadNavigationOrder()) }
    var dashboardOrder by remember { mutableStateOf(store.loadDashboardOrder()) }
    var showPeriodDetails by rememberUiExpanded(context, "overview_period_details", true)
    var showDashboardArrangeDialog by remember { mutableStateOf(false) }
    var firstPeriodStartDay by remember { mutableIntStateOf(store.loadFirstPeriodStartDay()) }
    var firstPeriodEndDay by remember { mutableIntStateOf(store.loadFirstPeriodEndDay()) }
    var showIncomeEntries by rememberUiExpanded(context, "income_entries", true)
    var showMonthCategories by rememberUiExpanded(context, "month_categories")
    var showPeriodCategories by rememberUiExpanded(context, "period_categories")
    var showYearCategories by rememberUiExpanded(context, "year_categories")
    var showAdvice by rememberUiExpanded(context, "analytics_advice")
    var showSavingsInfo by rememberUiExpanded(context, "savings_info")
    var showPlanChart by rememberUiExpanded(context, "chart_plan")
    var showPieChart by rememberUiExpanded(context, "chart_pie")
    var showCategoryCharts by rememberUiExpanded(context, "chart_categories")
    var showAnnualCategoryCharts by rememberUiExpanded(context, "annual_category_charts")
    var selectedChartCategoryNames by rememberSaveable { mutableStateOf("") }
    var showHistoryFilters by rememberUiExpanded(context, "history_filters")
    var quickActionsExpanded by rememberUiExpanded(context, "quick_actions_panel", true)
    val today = LocalDate.now()
    val baseMonth = YearMonth.from(if (today.dayOfMonth < firstPeriodStartDay) today.minusMonths(1) else today)
    val firstPeriodStart = baseMonth.atDay(firstPeriodStartDay.coerceAtMost(baseMonth.lengthOfMonth()))
    val firstPeriodEnd = baseMonth.atDay(firstPeriodEndDay.coerceAtMost(baseMonth.lengthOfMonth()))
    val secondPeriodStart = firstPeriodEnd.plusDays(1)
    val nextFirstMonth = baseMonth.plusMonths(1)
    val secondPeriodEnd = nextFirstMonth.atDay(firstPeriodStartDay.coerceAtMost(nextFirstMonth.lengthOfMonth())).minusDays(1)
    val isSecondPeriodToday = today.dayOfMonth < firstPeriodStartDay || today.dayOfMonth > firstPeriodEndDay
    var selectedPeriod by remember { mutableIntStateOf(if (store.loadPeriodStart()!=null) -1 else if (isSecondPeriodToday) 1 else 0) }
    var periodStart by remember { mutableStateOf(store.loadPeriodStart() ?: (if (isSecondPeriodToday) (if(today.dayOfMonth < firstPeriodStartDay) baseMonth.atDay(firstPeriodEndDay.coerceAtMost(baseMonth.lengthOfMonth())).plusDays(1) else secondPeriodStart) else firstPeriodStart).toString()) }
    var periodEnd by remember { mutableStateOf(store.loadPeriodEnd() ?: (if (isSecondPeriodToday) secondPeriodEnd else firstPeriodEnd).toString()) }
    var selectedExpense by remember { mutableStateOf<Expense?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var addFromCategory by remember { mutableStateOf<String?>(null) }
    var historySort by remember { mutableStateOf("Дата") }
    var historyAscending by remember { mutableStateOf(false) }
    var historyQuery by remember { mutableStateOf("") }
    var historyCategoryFilter by remember { mutableStateOf("Все категории") }
    var historyYearFilter by remember { mutableStateOf("Все годы") }
    var historyMonthNumberFilter by remember { mutableStateOf("Все месяцы") }
    var historyDayFilter by remember { mutableStateOf("Все числа") }
    var historyFromDate by remember { mutableStateOf("") }
    var historyToDate by remember { mutableStateOf("") }
    var amountLimit by remember { mutableFloatStateOf(100000f) }
    var analyticsMonth by remember { mutableStateOf(YearMonth.now()) }
    var annualChartYear by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var selectedAnnualChartCategoryNames by rememberSaveable { mutableStateOf("") }
    var analyticsPeriodMonth by remember { mutableStateOf(if (today.dayOfMonth <= 8) YearMonth.now().minusMonths(1) else YearMonth.now()) }
    var analyticsPeriodType by remember { mutableIntStateOf(if (today.dayOfMonth >= 24 || today.dayOfMonth <= 8) 1 else 0) }
    val formatter= remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }
    val periodIncome = periodIncomes.firstOrNull { it.start==periodStart && it.end==periodEnd }?.amount ?: 0.0
    val overviewMonth = YearMonth.from(LocalDate.parse(periodEnd))
    val monthPeriodIncome = periodIncomes.filter { runCatching { YearMonth.from(LocalDate.parse(it.end))==overviewMonth }.getOrDefault(false) }.sumOf { it.amount }
    val displayedMonthlyIncome = if(monthPeriodIncome>0.0) monthPeriodIncome else income
    val analyticsMonthIncome = AnalyticsBudgetCalculator.monthlyIncome(income, periodIncomes, analyticsMonth, analyticsIncomeMode == 1)
    val totalSpent=expenses.filter { it.date >= periodStart && it.date <= periodEnd }.sumOf { it.amount }
    val planned=if(periodIncome>0.0) periodIncome*categories.sumOf { it.percent }/100.0 else plannedForPeriod(income, categories.sumOf { it.percent }, periodStart, periodEnd)
    val remaining=planned-totalSpent
    val dailyLimit = calculateDailyLimit(remaining, LocalDate.parse(periodStart), LocalDate.parse(periodEnd), today)
    val dailyDaysRemaining = dailyLimit.daysRemaining
    val dailySpendingLimit = dailyLimit.amountPerDay
    val spentToday = expenses.filter { it.date == today.toString() }.sumOf { it.amount }
    val monthExpenses=expenses.filter { it.date.startsWith(LocalDate.now().toString().take(7)) }
    val monthTotal=monthExpenses.sumOf { it.amount }

    LaunchedEffect(autoAllocationEnabled, expenses, periodStart, periodEnd, income, periodIncomes, manualAllocationSnapshot) {
        if (autoAllocationEnabled) {
            val actuals = expenses.filter { it.date >= periodStart && it.date <= periodEnd }
                .groupBy { it.category }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
            val proposal = AnalyticsBudgetCalculator.dynamicProposal(
                manualAllocationSnapshot.filterNot { it.name.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) }.map { it.name to it.percent },
                actuals
            )
            val updatedRegular = manualAllocationSnapshot.filterNot { it.name.equals(BudgetAllocationCalculator.RESERVE_CATEGORY, true) }
                .map { category -> category.copy(percent = proposal.percentages[category.name] ?: category.percent) }
            val updated = BudgetAllocationCalculator.withReserve(updatedRegular.map { it.name to it.percent }).map { BudgetCategory(it.first, it.second) }
            if (updated != categories) { categories = updated; store.saveCategories(updated) }
        }
    }
    fun handleAssistantPhrase(phrase: String) {
        val normalizedPhrase = phrase.lowercase(Locale("ru", "RU")).replace('ё', 'е')
        if (listOf("что я могу сказать", "какие команды", "помощь", "справка").any { normalizedPhrase.contains(it) }) {
            voiceMessage = "Я умею добавлять доходы и расходы, искать операции, считать остаток до зарплаты, находить самый затратный день и день недели, сравнивать категории по сумме и частоте, показывать крупнейшие и средние траты, строить графики и давать рекомендации по экономии. Например: «В какой день было больше всего трат за месяц?», «Какая категория съедает больше всего денег?», «Что урезать, чтобы хватило до зарплаты?», «Сколько потратил на транспорт за неделю?»."
            return
        }
        val parsed = AssistantLocalParser.parse(phrase, categories.map { it.name }, today)
        if (parsed == null) {
            voiceMessage = "Пока не удалось понять запрос. Попробуй: «Сколько осталось до зарплаты?», «Сколько потратил на продукты в этом месяце?» или «Вчера потратил две тысячи на продукты»."
            return
        }
        when (val validation = AssistantCommandValidator.validate(parsed, categories.map { it.name }, today)) {
            is AssistantCommandValidation.NeedsClarification -> {
                voiceMessage = validation.reason
                return
            }
            is AssistantCommandValidation.Rejected -> {
                voiceMessage = validation.reason
                return
            }
            is AssistantCommandValidation.Valid -> {
                val command = validation.command
                val from = command.periodStart ?: periodStart
                val to = command.periodEnd ?: periodEnd
                val rangeExpenses = expenses.filter { it.date >= from && it.date <= to }
                val spentInRange = rangeExpenses.sumOf { it.amount }
                val rangeIncome = if (from == periodStart && to == periodEnd && periodIncome > 0.0) {
                    periodIncome
                } else {
                    val targetMonth = runCatching { YearMonth.from(LocalDate.parse(to)) }.getOrNull()
                    targetMonth?.let { month ->
                        periodIncomes.filter { runCatching { YearMonth.from(LocalDate.parse(it.end)) == month }.getOrDefault(false) }
                            .sumOf { it.amount }
                    }?.takeIf { it > 0.0 } ?: income
                }
                val plannedRange = if (from == periodStart && to == periodEnd) planned
                    else categories.sumOf { plannedForPeriod(rangeIncome, it.percent, from, to) }
                val remainingRange = plannedRange - spentInRange
                val dateLabel = "${LocalDate.parse(from).format(formatter)} — ${LocalDate.parse(to).format(formatter)}"
                val queryRows = if (command.periodStart != null && command.periodEnd != null) expenses.filter { it.date >= from && it.date <= to } else expenses
                val categoryRows = command.category?.let { name -> queryRows.filter { it.category.equals(name, true) } } ?: queryRows

                when (command.intent) {
                    AssistantIntent.QUERY_REMAINING -> {
                        val days = if (today.isAfter(LocalDate.parse(periodEnd))) 0L
                            else java.time.temporal.ChronoUnit.DAYS.between(maxOf(today, LocalDate.parse(periodStart)), LocalDate.parse(periodEnd)) + 1
                        val average = if (days > 0) remaining.coerceAtLeast(0.0) / days else 0.0
                        voiceMessage = "За отчётный период осталось ${money(remaining)}.\nСредний доступный расход: ${money(average)} в день.\nПериод: ${LocalDate.parse(periodStart).format(formatter)} — ${LocalDate.parse(periodEnd).format(formatter)}."
                    }
                    AssistantIntent.QUERY_SPENDING -> {
                        voiceMessage = "Расходы за период $dateLabel: ${money(spentInRange)}.\nОпераций: ${rangeExpenses.size}."
                    }
                    AssistantIntent.QUERY_CATEGORY_SPENDING -> {
                        val rows = rangeExpenses.filter { it.category == command.category }
                        voiceMessage = "На категорию «${command.category}» потрачено ${money(rows.sumOf { it.amount })} за период $dateLabel.\nОпераций: ${rows.size}."
                    }
                    AssistantIntent.QUERY_TOP_SPENDING_DAY -> {
                        if (normalizedPhrase.contains("день недели") || normalizedPhrase.contains("по каким дням недели")) {
                            val top = AssistantAnalytics.topSpendingWeekday(queryRows)
                            val dayName = top?.first?.getDisplayName(java.time.format.TextStyle.FULL, Locale("ru", "RU"))
                                ?.replaceFirstChar { it.titlecase(Locale("ru", "RU")) }
                            voiceMessage = if (top == null) "За выбранный период расходов нет."
                                else "Больше всего потрачено по ${dayName?.lowercase(Locale("ru", "RU"))}: ${money(top.second)} за выбранный период."
                        } else {
                            val top = AssistantAnalytics.topSpendingDay(queryRows)
                            val dayName = top?.first?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.FULL, Locale("ru", "RU"))
                                ?.replaceFirstChar { it.titlecase(Locale("ru", "RU")) }
                            voiceMessage = if (top == null) "За выбранный период расходов нет."
                                else "Самый затратный день — ${top.first.format(formatter)} ($dayName). Потрачено: ${money(top.second)}."
                        }
                    }
                    AssistantIntent.QUERY_SAVING_RECOMMENDATIONS -> {
                        val categoryBudgets = categories.associate { category ->
                            val cap = if (from == periodStart && to == periodEnd && periodIncome > 0.0)
                                periodIncome * category.percent / 100.0
                            else plannedForPeriod(rangeIncome, category.percent, from, to)
                            category.name to cap
                        }
                        val startDate = maxOf(today, LocalDate.parse(from))
                        val endDate = LocalDate.parse(to)
                        val days = if (today.isAfter(endDate)) 0L
                            else java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1L
                        val monthCount = if (startDate.isAfter(endDate)) -1L
                            else java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(startDate), YearMonth.from(endDate))
                        val pendingPayments = if (monthCount < 0L) emptyList() else recurringPayments.filter { it.active }.flatMap { payment ->
                            (0L..monthCount).mapNotNull { offset ->
                                val month = YearMonth.from(startDate).plusMonths(offset)
                                val due = payment.dueDate(month)
                                due.takeIf { !it.isBefore(startDate) && !it.isAfter(endDate) && !payment.isCompleted(month) }
                                    ?.let { payment to it }
                            }
                        }
                        val pendingTotal = pendingPayments.sumOf { it.first.amount }
                        val availableAfterBills = remainingRange - pendingTotal
                        val daily = if (days > 0) availableAfterBills.coerceAtLeast(0.0) / days else 0.0
                        val recommendations = AssistantAnalytics.savingRecommendations(
                            rangeExpenses, categoryBudgets, availableAfterBills, days
                        )
                        val header = "До конца выбранного периода осталось $days дн. Бюджетный остаток: ${money(remainingRange)}." +
                            if (pendingTotal > 0.0) " Неотмеченные регулярные платежи: ${money(pendingTotal)}." else ""
                        voiceMessage = if (rangeExpenses.isEmpty()) {
                            "Пока нет расходов за выбранный период. $header Добавь операции, и я смогу предложить конкретные способы экономии."
                        } else if (recommendations.isEmpty()) {
                            "$header Данных для сокращения расходов недостаточно. Проверь регулярные платежи и запланированные покупки."
                        } else {
                            header + (if (availableAfterBills < 0.0) "\nС учётом регулярных платежей есть дефицит бюджета. Предлагаю временно сократить:" else "\nЧтобы сохранить запас до зарплаты, можно сократить:") +
                                recommendations.joinToString("\n") { "• ${it.category}: уменьшить траты примерно на ${money(it.suggestedReduction)}. Сейчас потрачено ${money(it.spent)}. ${it.reason}." } +
                                "\nОценочный дневной лимит после регулярных платежей: ${money(daily)}. Сокращения — рекомендации, а не автоматические изменения бюджета."
                        }
                    }
                    AssistantIntent.QUERY_TOP_SPENDING_CATEGORY -> {
                        val top = AssistantAnalytics.topCategoryByAmount(queryRows)
                        voiceMessage = if (top == null) "За выбранный период расходов нет."
                            else "Больше всего денег ушло на категорию «${top.first}»: ${money(top.second)}. Это самая затратная категория по общей сумме."
                    }
                    AssistantIntent.QUERY_TOP_CATEGORY_FREQUENCY -> {
                        val top = AssistantAnalytics.topCategoryByFrequency(queryRows)
                        voiceMessage = if (top == null) "За выбранный период операций нет." else "Самая частая категория: «${top.first}». Количество операций: ${top.second}."
                    }
                    AssistantIntent.QUERY_TOP_TITLE_FREQUENCY -> {
                        val top = AssistantAnalytics.topTitleByFrequency(queryRows)
                        voiceMessage = if (top == null) "За выбранный период операций нет." else "Самое частое название расхода: «${top.first}». Количество операций: ${top.second}."
                    }
                    AssistantIntent.QUERY_MAX_EXPENSE, AssistantIntent.QUERY_MIN_EXPENSE -> {
                        val selected = if (command.intent == AssistantIntent.QUERY_MAX_EXPENSE) AssistantAnalytics.largest(categoryRows) else AssistantAnalytics.smallest(categoryRows)
                        val label = if (command.intent == AssistantIntent.QUERY_MAX_EXPENSE) "Самый большой" else "Самый маленький"
                        voiceMessage = if (selected == null) "Подходящих расходов не найдено." else "$label расход: «${selected.title}» — ${money(selected.amount)}. Категория: ${selected.category}. Дата: ${LocalDate.parse(selected.date).format(formatter)}."
                    }
                    AssistantIntent.QUERY_AVERAGE_SPENDING -> {
                        val bucket = when { normalizedPhrase.contains("недел") -> SpendingBucket.WEEK; normalizedPhrase.contains("квартал") -> SpendingBucket.QUARTER; normalizedPhrase.contains("месяц") || normalizedPhrase.contains("месяч") -> SpendingBucket.MONTH; normalizedPhrase.contains("год") -> SpendingBucket.YEAR; normalizedPhrase.contains("день") -> SpendingBucket.DAY; else -> SpendingBucket.MONTH }
                        val average = AssistantAnalytics.average(queryRows, bucket, command.category)
                        val bucketName = when (bucket) { SpendingBucket.DAY -> "день"; SpendingBucket.WEEK -> "неделю"; SpendingBucket.MONTH -> "месяц"; SpendingBucket.QUARTER -> "квартал"; SpendingBucket.YEAR -> "год" }
                        voiceMessage = if (average == null) "Недостаточно расходов для расчёта среднего." else "Средний расход за $bucketName: ${money(average.average)}. Всего: ${money(average.total)} за ${average.bucketCount} период(ов)." + (command.category?.let { " Категория: $it." } ?: " По всем категориям.")
                    }
                    AssistantIntent.QUERY_CHART -> {
                        val monthly = normalizedPhrase.contains("по месяц") || normalizedPhrase.contains("динамик") || normalizedPhrase.contains("тренд")
                        val values = if (monthly) AssistantAnalytics.monthlyTotals(categoryRows) else AssistantAnalytics.categoryTotals(categoryRows).take(10)
                        assistantChartTitle = if (monthly) "Динамика расходов по месяцам" else if (command.category == null) "Расходы по категориям" else "Расходы: ${command.category}"
                        assistantChartData = values
                        voiceMessage = if (values.isEmpty()) "Нет данных для построения графика." else "Построен график: ${values.size} показателей. Общая сумма: ${money(values.sumOf { it.second })}."
                    }
                    AssistantIntent.QUERY_OVERSPENDING -> {
                        val exceeded = categories.mapNotNull { category ->
                            val cap = if (from == periodStart && to == periodEnd) {
                                if (periodIncome > 0.0) periodIncome * category.percent / 100.0
                                else plannedForPeriod(income, category.percent, from, to)
                            } else plannedForPeriod(rangeIncome, category.percent, from, to)
                            val actual = rangeExpenses.filter { it.category == category.name }.sumOf { it.amount }
                            if (actual > cap) "${category.name}: превышение ${money(actual - cap)}" else null
                        }
                        voiceMessage = if (exceeded.isEmpty()) "За период $dateLabel превышений бюджета по категориям нет."
                            else "Превышения бюджета за период $dateLabel:\n" + exceeded.joinToString("\n")                    }
                    AssistantIntent.FORECAST -> {
                        val daily = command.amount ?: 0.0
                        val endDate = LocalDate.parse(to)
                        val days = if (today.isAfter(endDate)) 0L
                            else java.time.temporal.ChronoUnit.DAYS.between(maxOf(today, LocalDate.parse(from)), endDate) + 1
                        val projected = remainingRange - daily * days
                        voiceMessage = "Если тратить по ${money(daily)} в день ещё $days дн., к концу периода останется ${money(projected)}.\nСейчас доступно: ${money(remainingRange)}."
                    }
                    AssistantIntent.UPDATE_EXPENSE, AssistantIntent.DELETE_EXPENSE -> {
                        val target = expenses.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id }).firstOrNull()
                        if (target == null) {
                            voiceMessage = "Пока нет расходов, которые можно изменить или удалить."
                        } else {
                            voiceEditCandidates = expenses.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
                            voiceSelectedExpenseId = target.id
                            val deleting = command.intent == AssistantIntent.DELETE_EXPENSE
                            voiceDraft = VoiceExpenseDraft(
                                title = target.title,
                                category = target.category,
                                amount = command.amount ?: target.amount,
                                date = target.date,
                                sourcePhrase = phrase,
                                isEdit = true,
                                isDelete = deleting
                            )
                            voiceAmount = (command.amount ?: target.amount).toLong()
                                .takeIf { it.toDouble() == (command.amount ?: target.amount) }?.toString()
                                ?: (command.amount ?: target.amount).toString()
                        }
                    }
                    AssistantIntent.ADD_EXPENSE -> {
                        val amount = command.amount ?: return
                        voiceDraft = VoiceExpenseDraft(
                            title = command.title ?: command.category ?: "Расход",
                            category = command.category ?: return,
                            amount = amount,
                            date = command.date ?: today.toString(),
                            sourcePhrase = phrase
                        )
                        voiceAmount = amount.toLong().takeIf { it.toDouble() == amount }?.toString() ?: amount.toString()
                        voiceEditCandidates = emptyList()
                        voiceSelectedExpenseId = null
                    }
                    AssistantIntent.ADD_INCOME -> {
                        val amount = command.amount ?: return
                        voiceDraft = VoiceExpenseDraft(
                            title = command.title ?: "Доход",
                            category = "Доход",
                            amount = amount,
                            date = command.date ?: today.toString(),
                            sourcePhrase = phrase,
                            isIncome = true
                        )
                        voiceAmount = amount.toLong().takeIf { it.toDouble() == amount }?.toString() ?: amount.toString()
                    }
                    else -> voiceMessage = "Эту команду пока не поддерживает локальный помощник. Она будет добавлена на следующем этапе."
                }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val voicePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            voiceMessage = "Для голосового ввода необходимо разрешить доступ к микрофону."
        } else if (offlineSpeechRecognizer == null) {
            voiceMessage = "На этом устройстве не найден локальный движок распознавания русской речи. Установи офлайн-пакет распознавания речи в настройках Android или используй ручной ввод. Интернет-распознавание приложение не запускает."
        } else {
            isListening = true
            startOfflineSpeechRecognition(context, offlineSpeechRecognizer, onResult = { alternatives ->
                isListening = false
                showAssistantDialog = false
                val interpreted = alternatives.sortedByDescending { voiceRecognitionScore(it, categories) }.firstNotNullOfOrNull { phrase ->
                    AssistantLocalParser.parse(phrase, categories.map { it.name }, today)?.let { phrase to it }
                }
                if (interpreted != null) {
                    handleAssistantPhrase(interpreted.first)
                } else {
                    val parsed = alternatives
                        .mapNotNull { phrase -> parseVoiceExpense(phrase, categories)?.let { draft -> phrase to draft } }
                        .maxByOrNull { (phrase, _) -> voiceRecognitionScore(phrase, categories) }
                        ?.let { (phrase, draft) -> draft.copy(sourcePhrase = phrase) }
                    if (parsed == null) {
                        voiceMessage = "Не удалось разобрать команду. Скажи сумму и назначение: «Добавь расход 1 500 рублей на продукты» или «Добавь доход 54 тысячи рублей». Распознано: ${alternatives.joinToString(" / ")}"
                    } else {
                        voiceDraft = parsed
                        voiceAmount = parsed.amount.toLong().takeIf { it.toDouble() == parsed.amount }?.toString()
                            ?: parsed.amount.toString()
                        if (parsed.isEdit) {
                            voiceEditCandidates = expenses.filter { it.category == parsed.category }.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
                            voiceSelectedExpenseId = voiceEditCandidates.firstOrNull()?.id
                            if (voiceEditCandidates.isEmpty()) {
                                voiceDraft = null
                                voiceMessage = "Не найдена сохранённая операция в категории «${parsed.category}». Укажи категорию, например: «Измени расход продукты на 1500 рублей»."
                            }
                        } else {
                            voiceEditCandidates = emptyList()
                            voiceSelectedExpenseId = null
                        }
                    }
                }
            }, onError = { code ->
                isListening = false
                voiceMessage = "Не удалось распознать речь (код $code). Попробуй ещё раз или проверь, установлен ли офлайн-пакет русского языка."
            })
        }
    }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher= rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) {
            backupMessage = runCatching {
                XlsxExporter.write(context, uri, expenses, categories, income, LocalDate.now().year, periodStart, periodEnd, periodIncome, periodIncomes.groupBy { it.end.take(7) }.mapValues { (_, entries) -> entries.sumOf { it.amount } })
                "Excel-отчёт успешно сохранён."
            }.getOrElse { "Не удалось сохранить Excel-отчёт: ${it.message ?: "ошибка формирования файла"}" }
        }
    }
    val excelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupMessage = runCatching {
                val imported = XlsxImporter.readExpenses({ selected -> context.contentResolver.openInputStream(selected) }, uri)
                val existing = expenses.map { "${it.date}| ${it.title.trim().lowercase(Locale.ROOT)}|${it.category.trim().lowercase(Locale.ROOT)}|${it.amount}" }.toSet()
                val fresh = imported.filterNot { "${it.date}| ${it.title.trim().lowercase(Locale.ROOT)}|${it.category.trim().lowercase(Locale.ROOT)}|${it.amount}" in existing }
                if (fresh.isNotEmpty()) {
                    store.prepareUndoSnapshot()
                    canUndo = true
                    val newCategoryNames = fresh.map { it.category }.distinct().filterNot { name -> categories.any { it.name.equals(name, true) } }
                    if (newCategoryNames.isNotEmpty()) {
                        categories = categories + newCategoryNames.map { BudgetCategory(it, 0.0) }
                        store.saveCategories(categories); categories=store.loadCategories()
                    }
                    expenses = (expenses + fresh).distinctBy { it.id }.sortedByDescending { it.date }
                    store.saveExpenses(expenses)
                }
                "Импортировано операций: ${fresh.size}. Пропущено повторов: ${imported.size - fresh.size}."
            }.getOrElse { "Не удалось импортировать Excel: ${it.message ?: "проверь формат файла"}" }
        }
    }
    var externalBackupFolder by remember { mutableStateOf(store.externalBackupFolder()) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    val externalBackupFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            backupMessage = runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val previousFolder = externalBackupFolder
                context.contentResolver.takePersistableUriPermission(uri, flags)
                store.setExternalBackupFolder(uri.toString())
                externalBackupFolder = uri.toString()
                if (!previousFolder.isNullOrBlank() && previousFolder != uri.toString()) {
                    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(previousFolder), flags) }
                }
                "Папка выбрана. Автокопия будет сохраняться через 10 минут после последнего изменения; хранится до 7 последних копий."
            }.getOrElse { "Не удалось подключить папку: ${it.message ?: "ошибка доступа"}" }
        }
    }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            backupMessage = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(store.exportBackup().toByteArray(Charsets.UTF_8)) }
                    ?: error("Не удалось открыть файл для записи.")
                "Резервная копия успешно сохранена."
            }.getOrElse { "Не удалось создать копию: ${it.message ?: "ошибка записи"}" }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupMessage = runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Не удалось прочитать выбранный файл.")
                check(store.restoreBackup(json)) { "Файл повреждён или имеет неподдерживаемый формат." }
                income = store.loadIncome()
                savingsGoal = store.loadSavingsGoal()
                categories = store.loadCategories()
                expenses = store.loadExpenses()
                recurringPayments = store.loadRecurringPayments()
                periodIncomes = store.loadPeriodIncomes()
                incomeEntries = store.loadIncomeEntries()
                periodStart = store.loadPeriodStart() ?: periodStart
                periodEnd = store.loadPeriodEnd() ?: periodEnd
                selectedPeriod = -1
                "Данные успешно восстановлены из резервной копии."
            }.getOrElse { "Не удалось восстановить данные: ${it.message ?: "ошибка чтения"}" }
        }
    }
    val appColors = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFB7A6FF), onPrimary = Color(0xFF21164A),
            secondary = Color(0xFF75D9CF), tertiary = Color(0xFFFFC979),
            background = Color(0xFF111321), surface = Color(0xFF1B1D31),
            surfaceVariant = Color(0xFF292B43), onSurface = Color(0xFFF4F2FF),
            onSurfaceVariant = Color(0xFFB8B6CC), onBackground = Color(0xFFF4F2FF)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF6556D9), onPrimary = Color.White,
            primaryContainer = Color(0xFFE8E4FF), onPrimaryContainer = Color(0xFF30266F),
            secondary = Color(0xFF36AFA8), onSecondary = Color.White,
            secondaryContainer = Color(0xFFE0F6F2), onSecondaryContainer = Color(0xFF174F4B),
            tertiary = Color(0xFF8B7AE8), background = Color(0xFFF6F5FC),
            surface = Color(0xFFFFFEFF), surfaceVariant = Color(0xFFF0EEFA),
            onSurface = Color(0xFF22213A), onSurfaceVariant = Color(0xFF77758F),
            onBackground = Color(0xFF22213A), error = Color(0xFFD95770)
        )
    }
    MaterialTheme(
        colorScheme = appColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(30.dp)
        )
    ) {
        val navLabelStyle = if (compactLayout) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar={ TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title={ Column { Text("ThunBudget",fontWeight=FontWeight.Bold); Text("Личный бюджет",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions={ Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(0.dp)) {
                    val actionTextStyle = if (compactLayout) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge
                    val actionPadding = if (compactLayout) 6.dp else 10.dp
                    TextButton(onClick={darkTheme = !darkTheme; store.saveDarkTheme(darkTheme)},contentPadding=PaddingValues(horizontal=actionPadding,vertical=0.dp)) {
                        Text(if (darkTheme) "☀" else "☾", style=actionTextStyle, maxLines=1)
                    }
                    TextButton(onClick={showAssistantDialog=true},contentPadding=PaddingValues(horizontal=actionPadding,vertical=0.dp)) {
                        Text("Помощник",style=actionTextStyle,maxLines=1,softWrap=false)
                    }
                    TextButton(onClick={showSettings=true},contentPadding=PaddingValues(horizontal=actionPadding,vertical=0.dp)) {
                        Text("Настройки",style=actionTextStyle,maxLines=1,softWrap=false)
                    }
                } }
            ) },
            floatingActionButton = {
                if (!compactLayout && tab != 2 && tab != 5) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (quickActionsExpanded) {
                            SmallFloatingActionButton(
                                onClick = { voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ) { Icon(Icons.Filled.Mic, contentDescription = "Голосовой ввод") }
                            SmallFloatingActionButton(
                                onClick = { showPeriodIncomeEditor = true },
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            ) { Text("₽+", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer) }
                            FloatingActionButton(onClick = { showAdd = true }) {
                                Text("+", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                quickActionsExpanded = !quickActionsExpanded
                                persistUiExpanded(context, "quick_actions_panel", quickActionsExpanded)
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(if (quickActionsExpanded) "›" else "‹", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            bottomBar = {
                Column {
                    // Dock actions only on compact layouts; regular-width phones retain the floating controls.
                    if (compactLayout && tab != 2 && tab != 5) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (quickActionsExpanded) {
                                SmallFloatingActionButton(
                                    onClick = { voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Icon(Icons.Filled.Mic, contentDescription = "Голосовой ввод")
                                }
                                Spacer(Modifier.width(8.dp))
                                SmallFloatingActionButton(
                                    onClick = { showPeriodIncomeEditor = true },
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text("₽+", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                }
                                Spacer(Modifier.width(8.dp))
                                SmallFloatingActionButton(
                                    onClick = { showAdd = true },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Text("+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            SmallFloatingActionButton(
                                onClick = {
                                    quickActionsExpanded = !quickActionsExpanded
                                    persistUiExpanded(context, "quick_actions_panel", quickActionsExpanded)
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                Text(
                                    if (quickActionsExpanded) "›" else "‹",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    // Keep NavigationBar last so its system navigation insets are at the
                    // bottom edge. The theme switch above it must not draw under system buttons.
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 8.dp
                    ) {
                        val navItems = listOf(
                            Triple(0, "⌂", "Обзор"),
                            Triple(1, "◒", "Анализ"),
                            Triple(2, "▥", "Графики"),
                            Triple(3, "▤", "За год"),
                            Triple(4, "≡", "История"),
                            Triple(5, "▣", "Платежи")
                        )
                        navigationOrder.forEach { destination ->
                            val item = navItems.first { it.first == destination }
                            NavigationBarItem(
                                modifier = Modifier.pointerInput(destination, navigationOrder) {
                                    var dragDistance = 0f
                                    val threshold = size.width * 0.45f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragDistance = 0f },
                                        onDragEnd = { },
                                        onDragCancel = { },
                                        onDrag = { change, dragAmount ->
                                            dragDistance += dragAmount.x
                                            if (kotlin.math.abs(dragDistance) >= threshold) {
                                                val currentIndex = navigationOrder.indexOf(destination)
                                                val direction = if (dragDistance < 0f) -1 else 1
                                                val targetIndex = (currentIndex + direction).coerceIn(0, navigationOrder.lastIndex)
                                                if (targetIndex != currentIndex) {
                                                    val reordered = navigationOrder.toMutableList()
                                                    val moved = reordered.removeAt(currentIndex)
                                                    reordered.add(targetIndex, moved)
                                                    navigationOrder = reordered
                                                    store.saveNavigationOrder(reordered)
                                                }
                                                dragDistance = 0f
                                            }
                                            change.consume()
                                        }
                                    )
                                },
                                selected = tab == destination,
                                onClick = { tab = destination },
                                icon = { Text(item.second, style = MaterialTheme.typography.titleLarge) },
                                label = { Text(item.third, style = navLabelStyle, maxLines = 1, softWrap = false) },
                                alwaysShowLabel = true
                            )
                        }
                    }
                }
            }
        ) { padding ->
            when(tab) {
                0 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=12.dp,bottom=if(compactLayout) 24.dp else 96.dp)) {
                    item { Text("Обзор",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    items(dashboardOrder,key={it}) { section ->
                        when(section) {
                            "period" -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = { showPeriodDetails = !showPeriodDetails; persistUiExpanded(context, "overview_period_details", showPeriodDetails) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (showPeriodDetails) "Отчётный период ▲" else "Отчётный период ▼") }
                                if (showPeriodDetails) {
                                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                        SegmentedButton(selected=selectedPeriod==0,onClick={selectedPeriod=0;periodStart=firstPeriodStart.toString();periodEnd=firstPeriodEnd.toString();store.savePeriod(periodStart,periodEnd)},shape=SegmentedButtonDefaults.itemShape(0,2)) { Text("$firstPeriodStartDay–$firstPeriodEndDay") }
                                        SegmentedButton(selected=selectedPeriod==1,onClick={selectedPeriod=1;periodStart=secondPeriodStart.toString();periodEnd=secondPeriodEnd.toString();store.savePeriod(periodStart,periodEnd)},shape=SegmentedButtonDefaults.itemShape(1,2)) { Text("${secondPeriodStart.dayOfMonth}–${secondPeriodEnd.dayOfMonth}") }
                                    }
                                    TextButton(onClick={showPeriodEditor=true},modifier=Modifier.fillMaxWidth()) { Text("Изменить отчётный период вручную") }
                                    Text("${LocalDate.parse(periodStart).format(formatter)} — ${LocalDate.parse(periodEnd).format(formatter)}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            "budget" -> Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primary),shape=RoundedCornerShape(26.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text("Доступно в бюджете",color=MaterialTheme.colorScheme.onPrimary.copy(alpha=.78f),style=MaterialTheme.typography.labelLarge)
                                        Text(money(remaining),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimary)
                                        Text("Потрачено ${money(totalSpent)} из ${money(planned)}",color=MaterialTheme.colorScheme.onPrimary)
                                        val periodEndDate=LocalDate.parse(periodEnd)
                                        val remainingDays=if(today.isAfter(periodEndDate)) 0L else java.time.temporal.ChronoUnit.DAYS.between(maxOf(today,LocalDate.parse(periodStart)),periodEndDate)+1
                                        val averageRemainingPerDay=if(remainingDays>0) remaining.coerceAtLeast(0.0)/remainingDays else 0.0
                                        Text("Средний остаток на день: ${money(averageRemainingPerDay)}",color=MaterialTheme.colorScheme.onPrimary.copy(alpha=.92f),style=MaterialTheme.typography.bodyMedium)
                                        LinearProgressIndicator(progress={if(planned>0)(totalSpent/planned).coerceIn(0.0,1.0).toFloat() else 0f},modifier=Modifier.fillMaxWidth().padding(top=4.dp),color=MaterialTheme.colorScheme.onPrimary,trackColor=MaterialTheme.colorScheme.onPrimary.copy(alpha=.22f))
                                    }
                                }
                                if(canUndo) OutlinedButton(onClick={showUndoConfirmation=true},modifier=Modifier.fillMaxWidth()) { Text("Отменить последнее действие") }
                            }
                            "income" -> Card(shape=RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        Text("Операции дохода за период",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                                        TextButton(onClick={showIncomeEntries=!showIncomeEntries;persistUiExpanded(context,"income_entries",showIncomeEntries)}) {
                                            Text(if(showIncomeEntries) "Скрыть ▲" else "Показать ▼")
                                        }
                                    }
                                    if(showIncomeEntries) {
                                        val visibleIncomeEntries=incomeEntries.filter{it.periodStart==periodStart&&it.periodEnd==periodEnd}.sortedByDescending{it.date}
                                        if(visibleIncomeEntries.isEmpty()) Text("Отдельных поступлений пока нет. Добавь доход голосом или через кнопку ₽+.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        else visibleIncomeEntries.forEach { entry ->
                                            Row(verticalAlignment=Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(entry.title,fontWeight=FontWeight.Medium)
                                                    Text("${entry.date}${if(entry.isLegacyAggregate) " · перенесено из старой версии" else ""}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Text(money(entry.amount),fontWeight=FontWeight.SemiBold)
                                                TextButton(onClick={selectedIncomeEntry=entry}) { Text("Изменить") }
                                            }
                                        }
                                    }
                                }
                            }
                            "categories" -> Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text("Бюджет по категориям",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                                    IconButton(onClick={showCategoryManager=true}) { Text("+",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary) }
                                }
                                categories.forEach { cat ->
                                    val spent=expenses.filter{it.category==cat.name&&it.date>=periodStart&&it.date<=periodEnd}.sumOf{it.amount}
                                    val cap=if(periodIncome>0.0) periodIncome*cat.percent/100.0 else plannedForPeriod(income,cat.percent,periodStart,periodEnd)
                                    Card(onClick={selectedCategory=cat.name},shape=RoundedCornerShape(16.dp)) {
                                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                            Row(verticalAlignment=Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(cat.name,fontWeight=FontWeight.SemiBold)
                                                    val free=BudgetAllocationCalculator.freePercent(cap,spent)
                                                    Text(free?.let{if(it>=0.0) "Свободный остаток: ${String.format(Locale("ru","RU"),"%.1f",it)}%" else "Превышение: ${String.format(Locale("ru","RU"),"%.1f",-it)}%"} ?: "Нет бюджета на период",style=MaterialTheme.typography.labelSmall,color=if((free?:0.0)<0.0) Color(0xFFC74444) else MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick={editingCategory=cat}) { Text("✎",color=MaterialTheme.colorScheme.primary) }
                                                Text("${cat.percent.toInt()}% · ${money(cap)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            LinearProgressIndicator(progress={if(cap>0)(spent/cap).coerceIn(0.0,1.0).toFloat() else 0f},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),color=if(spent>cap) Color(0xFFC74444) else Color(0xFF168A72))
                                            Row { Text("Факт: ${money(spent)}",style=MaterialTheme.typography.bodySmall); Spacer(Modifier.weight(1f)); Text(if(spent>cap) "Превышение: ${money(spent-cap)}" else "Осталось: ${money(cap-spent)}",style=MaterialTheme.typography.bodySmall,color=if(spent>cap) Color(0xFFC74444) else Color(0xFF168A72)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                    item { Text("Аналитика",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
                    item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick={showAllocationDialog=true},modifier=Modifier.fillMaxWidth()) { Text("Рассчитать доли по истории расходов") }; Button(onClick={showDynamicAllocationDialog=true},modifier=Modifier.fillMaxWidth()) { Text("Динамический прогноз по факту из «Обзора»") } } }
                    item { Text("Текущий отчётный период",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item {
                        val currentStart = LocalDate.parse(periodStart)
                        val currentEnd = LocalDate.parse(periodEnd)
                        val total = expenses.filter { it.date >= periodStart && it.date <= periodEnd }.sumOf { it.amount }
                        MetricCard("${currentStart.format(formatter)} — ${currentEnd.format(formatter)} · расходы",money(total),Modifier.fillMaxWidth())
                    }
                    item {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Автоматическое распределение", fontWeight=FontWeight.SemiBold)
                                        Text("Доли обновляются по фактическим расходам выбранного периода.", style=MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked=autoAllocationEnabled, onCheckedChange={ enabled ->
                                        if (enabled) {
                                            // Capture the complete manual configuration before auto mode changes it.
                                            val snapshot = categories.map { it.copy() }
                                            manualAllocationSnapshot = snapshot
                                            store.saveManualAllocationSnapshot(snapshot)
                                        } else if (autoAllocationEnabled) {
                                            // Restore the exact saved list, including its original reserve share.
                                            val restored = manualAllocationSnapshot.map { it.copy() }
                                            store.restoreCategoriesExactly(restored)
                                            categories = restored
                                        }
                                        autoAllocationEnabled = enabled
                                        store.saveAutoAllocationEnabled(enabled)
                                    })
                                }
                            }
                        }
                    }
                    item { Text("Источник месячного дохода для расчёта бюджета",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold); SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { SegmentedButton(selected=analyticsIncomeMode==0,onClick={analyticsIncomeMode=0},shape=SegmentedButtonDefaults.itemShape(0,2)) { Text("Месячный доход") }; SegmentedButton(selected=analyticsIncomeMode==1,onClick={analyticsIncomeMode=1},shape=SegmentedButtonDefaults.itemShape(1,2)) { Text("2 отчётных периода") } }; val selectedMonthIncome=AnalyticsBudgetCalculator.monthlyIncome(income,periodIncomes,analyticsMonth,analyticsIncomeMode==1); Text(if(analyticsIncomeMode==0) "Месячный доход из настроек: ${money(selectedMonthIncome)}" else "Доходы за периоды 24–8 и 9–23: ${money(selectedMonthIncome)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Text("Расходы за отчётный месяц",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={analyticsMonth=analyticsMonth.minusMonths(1)}) { Text("‹") }
                            Text(analyticsMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru","RU")).withLocale(Locale("ru","RU"))),modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                            TextButton(onClick={if(analyticsMonth<YearMonth.now()) analyticsMonth=analyticsMonth.plusMonths(1)}) { Text("›") }
                        }
                        val from=analyticsMonth.atDay(1).toString()
                        val to=minOf(analyticsMonth.atEndOfMonth(),if(analyticsMonth==YearMonth.now()) today else analyticsMonth.atEndOfMonth()).toString()
                        val monthTotal=expenses.filter { it.date>=from && it.date<=to }.sumOf { it.amount }
                        MetricCard("Итого за месяц",money(monthTotal),Modifier.fillMaxWidth())
                    }
                    item {
                        val previousMonth = analyticsMonth.minusMonths(1)
                        val currentFrom = analyticsMonth.atDay(1)
                        val currentTo = minOf(analyticsMonth.atEndOfMonth(), if (analyticsMonth == YearMonth.now()) today else analyticsMonth.atEndOfMonth())
                        val previousTo = if (analyticsMonth == YearMonth.now()) {
                            previousMonth.atDay(today.dayOfMonth.coerceAtMost(previousMonth.lengthOfMonth()))
                        } else previousMonth.atEndOfMonth()
                        val currentTotal = expenses.filter { it.date >= currentFrom.toString() && it.date <= currentTo.toString() }.sumOf { it.amount }
                        val previousTotal = expenses.filter { it.date >= previousMonth.atDay(1).toString() && it.date <= previousTo.toString() }.sumOf { it.amount }
                        val change = currentTotal - previousTotal
                        val changePercent = if (previousTotal > 0.0) change / previousTotal * 100.0 else 0.0
                        val elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(currentFrom, currentTo) + 1
                        val dailyAverage = if (elapsedDays > 0) currentTotal / elapsedDays else 0.0
                        val topCategory = categories.map { cat ->
                            cat.name to expenses.filter { it.category == cat.name && it.date >= currentFrom.toString() && it.date <= currentTo.toString() }.sumOf { it.amount }
                        }.maxByOrNull { it.second }
                        Card(shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Динамика расходов", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("К " + previousMonth.format(DateTimeFormatter.ofPattern("LLLL", Locale("ru", "RU"))) + ": " +
                                    (if (previousTotal <= 0.0) "нет данных для сравнения" else
                                        (if (change > 0.0) "рост " else if (change < 0.0) "снижение " else "без изменений ") +
                                        money(kotlin.math.abs(change)) + " (" +
                                        String.format(Locale("ru", "RU"), "%.1f", kotlin.math.abs(changePercent)) + "%)"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (change > 0.0) Color(0xFFC74444) else Color(0xFF168A72))
                                Text("Средний расход в день: " + money(dailyAverage), style = MaterialTheme.typography.bodyMedium)
                                Text("Крупнейшая категория: " +
                                    (topCategory?.takeIf { it.second > 0.0 }?.let { it.first + " · " + money(it.second) } ?: "нет расходов"),
                                    style = MaterialTheme.typography.bodyMedium)
                                if (analyticsMonth == YearMonth.now()) Text("Сравнение выполнено за одинаковое количество прошедших дней месяца.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item { TextButton(onClick={showMonthCategories=!showMonthCategories; persistUiExpanded(context,"month_categories",showMonthCategories)},modifier=Modifier.fillMaxWidth()) { Text(if(showMonthCategories) "Скрыть категории ▲" else "Показать категории ▼") } }
                    if(showMonthCategories) items(categories,key={ "month-"+it.name }) { cat ->
                        val from=analyticsMonth.atDay(1).toString()
                        val to=minOf(analyticsMonth.atEndOfMonth(),if(analyticsMonth==YearMonth.now()) today else analyticsMonth.atEndOfMonth()).toString()
                        val fact=expenses.filter { it.category==cat.name && it.date>=from && it.date<=to }.sumOf { it.amount }
                        val plan=plannedForPeriod(analyticsMonthIncome,cat.percent,from,to)
                        Card { Column(Modifier.padding(14.dp)) {
                            Row { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text(money(fact),fontWeight=FontWeight.Bold) }
                            LinearProgressIndicator(progress={if(plan>0)(fact/plan).coerceIn(0.0,1.0).toFloat() else 0f},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                            val metrics = AnalyticsBudgetCalculator.metrics(plan, fact)
                            Text("План: ${money(plan)} · "+if(fact>plan) "Превышение: ${money(fact-plan)}" else "Остаток: ${money(plan-fact)}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                            Text("Свободный остаток: ${metrics.remainingPercent?.let { String.format(Locale("ru","RU"), "%.1f", it) } ?: "—"}% · Повышение бюджета: ${metrics.requiredIncreasePercent?.let { String.format(Locale("ru","RU"), "%.1f", it) } ?: "—"}%",style=MaterialTheme.typography.labelSmall,color=if((metrics.remainingPercent ?: 0.0)<0.0) Color(0xFFC74444) else MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
                    item { Divider(); Text("Расходы за выбранный отчётный период",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected=analyticsPeriodType==0,onClick={analyticsPeriodType=0},label={Text("9–23")})
                            FilterChip(selected=analyticsPeriodType==1,onClick={analyticsPeriodType=1},label={Text("24–8")})
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            TextButton(onClick={analyticsPeriodMonth=analyticsPeriodMonth.minusMonths(1)}) { Text("‹") }
                            Text(analyticsPeriodMonth.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru","RU"))),modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                            TextButton(onClick={if(analyticsPeriodMonth<YearMonth.now()) analyticsPeriodMonth=analyticsPeriodMonth.plusMonths(1)}) { Text("›") }
                        }
                    }
                    item {
                        val pStart=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(9) else analyticsPeriodMonth.atDay(24)
                        val pEnd=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(23) else analyticsPeriodMonth.plusMonths(1).atDay(8)
                        val visibleEnd=minOf(pEnd,today)
                        val periodTotal=if(visibleEnd.isBefore(pStart)) 0.0 else expenses.filter { it.date>=pStart.toString() && it.date<=visibleEnd.toString() }.sumOf { it.amount }
                        MetricCard("${pStart.format(formatter)} — ${pEnd.format(formatter)} · потрачено",money(periodTotal),Modifier.fillMaxWidth())
                    }
                    item { TextButton(onClick={showPeriodCategories=!showPeriodCategories; persistUiExpanded(context,"period_categories",showPeriodCategories)},modifier=Modifier.fillMaxWidth()) { Text(if(showPeriodCategories) "Скрыть категории ▲" else "Показать категории ▼") } }
                    if(showPeriodCategories) items(categories,key={ "period-"+it.name }) { cat ->
                        val pStart=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(9) else analyticsPeriodMonth.atDay(24)
                        val pEnd=if(analyticsPeriodType==0) analyticsPeriodMonth.atDay(23) else analyticsPeriodMonth.plusMonths(1).atDay(8)
                        val visibleEnd=minOf(pEnd,today)
                        val fact=if(visibleEnd.isBefore(pStart)) 0.0 else expenses.filter { it.category==cat.name && it.date>=pStart.toString() && it.date<=visibleEnd.toString() }.sumOf { it.amount }
                        val pIncome=periodIncomes.firstOrNull { it.start==pStart.toString() && it.end==pEnd.toString() }?.amount ?: 0.0
                        val plan=if(pIncome>0.0) pIncome*cat.percent/100.0 else plannedForPeriod(analyticsMonthIncome,cat.percent,pStart.toString(),pEnd.toString())
                        Card { Column(Modifier.padding(14.dp)) {
                            Row { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text(money(fact),fontWeight=FontWeight.Bold) }
                            val metrics = AnalyticsBudgetCalculator.metrics(plan, fact)
                            Text("План: ${money(plan)} · "+if(fact>plan) "Превышение: ${money(fact-plan)}" else "Остаток бюджета: ${money(plan-fact)}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                            Text("Свободный остаток: ${metrics.remainingPercent?.let { String.format(Locale("ru","RU"), "%.1f", it) } ?: "—"}% · Повышение бюджета: ${metrics.requiredIncreasePercent?.let { String.format(Locale("ru","RU"), "%.1f", it) } ?: "—"}%",style=MaterialTheme.typography.labelSmall,color=if((metrics.remainingPercent ?: 0.0)<0.0) Color(0xFFC74444) else MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
                    item { Divider(); Text("Расходы за год",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold) }
                    item { MetricCard("Всего с начала года",money(expenses.filter { it.date>=LocalDate.of(today.year,1,1).toString() && it.date<=today.toString() }.sumOf { it.amount }),Modifier.fillMaxWidth()) }
                    item { TextButton(onClick={showYearCategories=!showYearCategories; persistUiExpanded(context,"year_categories",showYearCategories)},modifier=Modifier.fillMaxWidth()) { Text(if(showYearCategories) "Скрыть категории ▲" else "Показать категории ▼") } }
                    if(showYearCategories) items(categories,key={ "year-"+it.name }) { cat ->
                        val yearStart=LocalDate.of(today.year,1,1).toString()
                        val fact=expenses.filter { it.category==cat.name && it.date>=yearStart && it.date<=today.toString() }.sumOf { it.amount }
                        val plan=plannedForPeriod(income,cat.percent,yearStart,today.toString())
                        Card { Column(Modifier.padding(14.dp)) {
                            Row { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text(money(fact),fontWeight=FontWeight.Bold) }
                            Text("План: ${money(plan)} · "+if(fact>plan) "Превышение: ${money(fact-plan)}" else "Не выбрано: ${money(plan-fact)}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                        } }
                    }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Рекомендации по перераспределению",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); TextButton(onClick={showAdvice=!showAdvice; persistUiExpanded(context,"analytics_advice",showAdvice)}) { Text(if(showAdvice) "Скрыть" else "Показать") } } }
                    if(showAdvice) item {
                        val periodFacts = categories.associate { cat ->
                            cat.name to expenses.filter { it.category == cat.name && it.date >= periodStart && it.date <= periodEnd }.sumOf { it.amount }
                        }
                        val budgetPerPercent = if (periodIncome > 0.0) periodIncome / 100.0
                            else plannedForPeriod(income, 1.0, periodStart, periodEnd)
                        val redistribution = BudgetAllocationCalculator.redistribute(
                            categories.map { it.name to it.percent },
                            periodFacts,
                            budgetPerPercent
                        )
                        if (redistribution.transfers.isEmpty()) {
                            Text("Превышений, которые можно покрыть за счёт категорий с достаточным свободным остатком, нет.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Предлагаемое перераспределение бюджета:", fontWeight = FontWeight.SemiBold)
                                redistribution.transfers.forEach { transfer ->
                                    Text(
                                        "«${transfer.toCategory}»: +${money(transfer.amount)} из «${transfer.fromCategory}» (свободный остаток до переноса: ${String.format(Locale("ru","RU"), "%.1f", transfer.donorFreePercent)}%).",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    "Сначала используются категории с запасом от 20%, затем от 15% и от 10%. Категории с запасом 5% и менее не затрагиваются.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilledTonalButton(
                                    onClick = {
                                        store.prepareUndoSnapshot(); canUndo = true
                                        categories = categories.map { cat ->
                                            cat.copy(percent = redistribution.updatedPercents[cat.name] ?: cat.percent)
                                        }
                                        store.saveCategories(categories)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Применить перераспределение") }
                            }
                        }
                    }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { if(showSavingsInfo) Text("Цель накоплений: ${savingsGoal.toInt()}% от дохода — ${money(displayedMonthlyIncome*savingsGoal/100)}",style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f)); TextButton(onClick={showSavingsInfo=!showSavingsInfo; persistUiExpanded(context,"savings_info",showSavingsInfo)}) { Text(if(showSavingsInfo) "Скрыть" else "Показать") } } }
                }
                2 -> Box(Modifier.padding(padding).fillMaxSize()) {
                    ChartsScreen(
                        expenses=expenses,categories=categories,month=analyticsMonth,onMonthChange={analyticsMonth=it},
                        showPlan=showPlanChart,onShowPlanChange={showPlanChart=it; persistUiExpanded(context,"chart_plan",it)},showPie=showPieChart,onShowPieChange={showPieChart=it; persistUiExpanded(context,"chart_pie",it)},
                        selectedChartCategoryNames=selectedChartCategoryNames,onSelectedChartCategoryNamesChange={selectedChartCategoryNames=it},
                        showCategoryCharts=showCategoryCharts,onShowCategoryChartsChange={showCategoryCharts=it; persistUiExpanded(context,"chart_categories",it)},
                        fallbackIncome=income,periodIncomes=periodIncomes
                    )
                }
                3 -> Box(Modifier.padding(padding).fillMaxSize()) {
                    AnnualChartsScreen(expenses=expenses,categories=categories,year=annualChartYear,onYearChange={annualChartYear=it},selectedCategoryNames=selectedAnnualChartCategoryNames,onSelectedCategoryNamesChange={selectedAnnualChartCategoryNames=it},showCategoryCharts=showAnnualCategoryCharts,onShowCategoryChartsChange={showAnnualCategoryCharts=it; persistUiExpanded(context,"annual_category_charts",it)})
                }
                5 -> Box(Modifier.padding(padding).fillMaxSize()) {
                    RecurringPaymentsScreen(
                    payments = recurringPayments,
                    categories = categories,
                    onAdd = { payment ->
                        recurringPayments = recurringPayments + payment; store.saveRecurringPayments(recurringPayments)
                        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onUpdate = { payment ->
                        recurringPayments = recurringPayments.map { if (it.id == payment.id) payment else it }
                        store.saveRecurringPayments(recurringPayments)
                    },
                    onToggle = { payment -> recurringPayments = recurringPayments.map { if (it.id == payment.id) it.copy(active = !it.active) else it }; store.saveRecurringPayments(recurringPayments) },
                    onDelete = { payment -> recurringPayments = recurringPayments.filterNot { it.id == payment.id }; store.saveRecurringPayments(recurringPayments) },
                    onConfirm = { payment, paymentMonth ->
                        val due = payment.dueDate(paymentMonth)
                        store.prepareUndoSnapshot(); canUndo = true
                        expenses = (expenses + Expense(System.currentTimeMillis(), payment.title, payment.category, payment.amount, due.toString())).sortedByDescending { it.date }
                        store.saveExpenses(expenses)
                        recurringPayments = recurringPayments.map { if (it.id == payment.id) it.copy(completedMonths = it.completedMonths + paymentMonth.toString()) else it }
                        store.saveRecurringPayments(recurringPayments)
                    }
                    )
                }
                else -> LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item { Text("История операций",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
                    item { Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Фильтры операций",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        TextButton(onClick={showHistoryFilters=!showHistoryFilters; persistUiExpanded(context,"history_filters",showHistoryFilters)}) { Text(if(showHistoryFilters) "Скрыть ▲" else "Показать ▼") }
                    } }
                    if(showHistoryFilters) item {
                        var expandedCategory by rememberSaveable { mutableStateOf(false) }
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value=historyQuery,onValueChange={historyQuery=it},modifier=Modifier.fillMaxWidth(),label={Text("Поиск по названию или категории")},singleLine=true,leadingIcon={Text("⌕")})
                            ExposedDropdownMenuBox(expanded=expandedCategory,onExpandedChange={expandedCategory=it}) {
                                OutlinedTextField(value=historyCategoryFilter,onValueChange={},readOnly=true,modifier=Modifier.menuAnchor().fillMaxWidth(),label={Text("Категория")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expandedCategory)})
                                ExposedDropdownMenu(expanded=expandedCategory,onDismissRequest={expandedCategory=false}) {
                                    (listOf("Все категории")+categories.map { it.name }).distinct().forEach { option ->
                                        DropdownMenuItem(text={Text(option)},onClick={historyCategoryFilter=option;expandedCategory=false})
                                    }
                                }
                            }
                            val historyDates = expenses.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                            val historyYears = (historyDates.map { it.year }.distinct().sortedDescending().map { it.toString() } + LocalDate.now().year.toString()).distinct()
                            val historyMonths = listOf("Все месяцы") + (1..12).map { it.toString().padStart(2,'0') }
                            val historyDays = listOf("Все числа") + (1..31).map { it.toString().padStart(2,'0') }
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                                var expandedYear by rememberSaveable { mutableStateOf(false) }
                                ExposedDropdownMenuBox(expanded=expandedYear,onExpandedChange={expandedYear=it},modifier=Modifier.weight(1f)) {
                                    OutlinedTextField(value=historyYearFilter,onValueChange={},readOnly=true,modifier=Modifier.menuAnchor().fillMaxWidth(),label={Text("Год")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expandedYear)},singleLine=true)
                                    ExposedDropdownMenu(expanded=expandedYear,onDismissRequest={expandedYear=false}) {
                                        (listOf("Все годы")+historyYears).forEach { option -> DropdownMenuItem(text={Text(option)},onClick={historyYearFilter=option;expandedYear=false}) }
                                    }
                                }
                                var expandedMonth by rememberSaveable { mutableStateOf(false) }
                                ExposedDropdownMenuBox(expanded=expandedMonth,onExpandedChange={expandedMonth=it},modifier=Modifier.weight(1f)) {
                                    OutlinedTextField(value=historyMonthNumberFilter,onValueChange={},readOnly=true,modifier=Modifier.menuAnchor().fillMaxWidth(),label={Text("Месяц")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expandedMonth)},singleLine=true)
                                    ExposedDropdownMenu(expanded=expandedMonth,onDismissRequest={expandedMonth=false}) {
                                        historyMonths.forEach { option ->
                                            val label=if(option=="Все месяцы") option else runCatching { java.time.Month.of(option.toInt()).getDisplayName(java.time.format.TextStyle.FULL,Locale("ru","RU")).replaceFirstChar { it.uppercase(Locale("ru","RU")) } }.getOrDefault(option)
                                            DropdownMenuItem(text={Text(label)},onClick={historyMonthNumberFilter=option;expandedMonth=false})
                                        }
                                    }
                                }
                                var expandedDay by rememberSaveable { mutableStateOf(false) }
                                ExposedDropdownMenuBox(expanded=expandedDay,onExpandedChange={expandedDay=it},modifier=Modifier.weight(0.8f)) {
                                    OutlinedTextField(value=historyDayFilter,onValueChange={},readOnly=true,modifier=Modifier.menuAnchor().fillMaxWidth(),label={Text("Число")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expandedDay)},singleLine=true)
                                    ExposedDropdownMenu(expanded=expandedDay,onDismissRequest={expandedDay=false}) {
                                        historyDays.forEach { option -> DropdownMenuItem(text={Text(option)},onClick={historyDayFilter=option;expandedDay=false}) }
                                    }
                                }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value=historyFromDate,onValueChange={historyFromDate=it.filter(Char::isDigit).take(8)},modifier=Modifier.weight(1f),label={Text("С даты")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                                OutlinedTextField(value=historyToDate,onValueChange={historyToDate=it.filter(Char::isDigit).take(8)},modifier=Modifier.weight(1f),label={Text("По дату")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                            }
                            TextButton(onClick={historyQuery="";historyCategoryFilter="Все категории";historyYearFilter="Все годы";historyMonthNumberFilter="Все месяцы";historyDayFilter="Все числа";historyFromDate="";historyToDate="";amountLimit=expenses.maxOfOrNull { it.amount }?.toFloat()?.coerceAtLeast(100000f) ?: 100000f},modifier=Modifier.align(Alignment.End)) { Text("Сбросить фильтры") }
                        }
                    }
                    item {
                        var expandedSort by remember { mutableStateOf(false) }
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            ExposedDropdownMenuBox(expanded=expandedSort,onExpandedChange={expandedSort=it}) {
                                OutlinedTextField(value="Сортировка: $historySort",onValueChange={},readOnly=true,modifier=Modifier.menuAnchor().fillMaxWidth(),trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expandedSort)})
                                ExposedDropdownMenu(expanded=expandedSort,onDismissRequest={expandedSort=false}) {
                                    listOf("Название","Дата","Категория","Сумма").forEach { option -> DropdownMenuItem(text={Text(option)},onClick={historySort=option;expandedSort=false}) }
                                }
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Порядок",modifier=Modifier.weight(1f))
                                TextButton(onClick={historyAscending=!historyAscending}) { Text(if(historyAscending) "По возрастанию ↑" else "По убыванию ↓") }
                            }
                            Text("Показывать операции до ${money(amountLimit.toDouble())}",style=MaterialTheme.typography.bodySmall)
                            Slider(value=amountLimit,onValueChange={amountLimit=it},valueRange=0f..(expenses.maxOfOrNull { it.amount }?.toFloat()?.coerceAtLeast(100000f) ?: 100000f))
                        }
                    }
                    val normalizedQuery = historyQuery.trim().lowercase(Locale("ru","RU"))
                    val validFrom = parseUserDate(historyFromDate)?.toString()
                    val validTo = parseUserDate(historyToDate)?.toString()
                    val filteredExpenses=expenses.filter { expense ->
                        expense.amount<=amountLimit.toDouble() &&
                        (normalizedQuery.isBlank() || expense.title.lowercase(Locale("ru","RU")).contains(normalizedQuery) || expense.category.lowercase(Locale("ru","RU")).contains(normalizedQuery)) &&
                        (historyCategoryFilter=="Все категории" || expense.category==historyCategoryFilter) &&
                        (historyYearFilter=="Все годы" || expense.date.startsWith(historyYearFilter)) &&
                        (historyMonthNumberFilter=="Все месяцы" || expense.date.substring(5,7)==historyMonthNumberFilter) &&
                        (historyDayFilter=="Все числа" || expense.date.substring(8,10)==historyDayFilter) &&
                        (validFrom==null || expense.date>=validFrom) &&
                        (validTo==null || expense.date<=validTo)
                    }
                    if(filteredExpenses.isEmpty()) item {
                        Text(if(expenses.isEmpty()) "Пока нет расходов. Нажми +, чтобы добавить первую операцию." else "По заданным условиям операций не найдено.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val sortedExpenses=when(historySort) {
                        "Название" -> if(historyAscending) filteredExpenses.sortedBy { it.title.lowercase() } else filteredExpenses.sortedByDescending { it.title.lowercase() }
                        "Категория" -> if(historyAscending) filteredExpenses.sortedBy { it.category.lowercase() } else filteredExpenses.sortedByDescending { it.category.lowercase() }
                        "Сумма" -> if(historyAscending) filteredExpenses.sortedBy { it.amount } else filteredExpenses.sortedByDescending { it.amount }
                        else -> if(historyAscending) filteredExpenses.sortedBy { it.date } else filteredExpenses.sortedByDescending { it.date }
                    }
                    if(filteredExpenses.isNotEmpty()) item {
                        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.55f))) {
                            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text("Найдено: ${filteredExpenses.size}",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                                Text("Итого: ${money(filteredExpenses.sumOf { it.amount })}",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    items(sortedExpenses,key={it.id}) { e -> Card(onClick={ selectedExpense=e },shape=RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(e.title,fontWeight=FontWeight.SemiBold); Text("${e.category} · ${e.date}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }; Text("−${money(e.amount)}",fontWeight=FontWeight.Bold,color=Color(0xFFC74444)) } } }
                }
            }
        }
    }
    voiceDraft?.let { draft ->
        var voiceDate by remember(draft.date) { mutableStateOf(voiceDateDigits(draft.date)) }
        AlertDialog(
            onDismissRequest={voiceDraft=null},
            title={Text(when { draft.isCategoryRename -> "Проверь переименование категории"; draft.isCategoryAdd -> "Проверь новую категорию"; draft.isCategoryEdit -> "Проверь изменение категории"; draft.isIncomeEdit -> "Проверь изменение дохода"; draft.isDelete -> "Подтверди удаление операции"; draft.isEdit -> "Проверь изменение операции"; draft.isIncome -> "Проверь распознанный доход"; else -> "Проверь распознанный расход" })},
            text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                if (draft.isCategoryRename) {
                    Text("Категория: «${draft.category}» → «${draft.title}»",fontWeight=FontWeight.Bold)
                } else if (draft.isCategoryAdd || draft.isCategoryEdit) {
                    Text("${draft.category}: ${draft.amount.toInt()}%",fontWeight=FontWeight.Bold)                } else {
                    Text("Распознано: ${draft.sourcePhrase}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if (draft.isEdit) {
                        val candidates = voiceEditCandidates
                        val selected = candidates.firstOrNull { it.id == voiceSelectedExpenseId }
                        ExposedDropdownMenuBox(expanded=voiceEditExpanded,onExpandedChange={voiceEditExpanded=it}) {
                            OutlinedTextField(
                                value=selected?.let { "${it.title} · ${it.date} · ${money(it.amount)}" } ?: "Операция не выбрана",
                                onValueChange={},readOnly=true,modifier=Modifier.menuAnchor().fillMaxWidth(),
                                label={Text(if (draft.isDelete) "Какую операцию удалить" else "Какую операцию изменить")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(voiceEditExpanded)}
                            )
                            ExposedDropdownMenu(expanded=voiceEditExpanded,onDismissRequest={voiceEditExpanded=false}) {
                                candidates.forEach { candidate ->
                                    DropdownMenuItem(
                                        text={Text("${candidate.title} · ${candidate.date} · ${money(candidate.amount)}")},
                                        onClick={voiceSelectedExpenseId=candidate.id;voiceEditExpanded=false}
                                    )
                                }
                            }
                        }
                        Text("Текущая сумма: ${selected?.let { money(it.amount) } ?: "не выбрана"}",style=MaterialTheme.typography.bodySmall)
                    }
                    if (!draft.isDelete) OutlinedTextField(
                        value=voiceAmount,
                        onValueChange={ voiceAmount=it },
                        label={Text("Сумма, ₽")},
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                        singleLine=true
                    )
                    if (!draft.isDelete) OutlinedTextField(
                        value=voiceDate,
                        onValueChange={voiceDate=it.filter(Char::isDigit).take(8)},
                        label={Text("Дата операции (ДД.ММ.ГГГГ)")},
                        placeholder={Text("ДД.ММ.ГГГГ")},
                        visualTransformation=DateDigitsVisualTransformation,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                        singleLine=true
                    )
                    Text("Описание: ${draft.title}")
                    Text("Если распознавание пропустило слово «тысяч», исправь сумму здесь. Неверную сумму не сохраняй.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Проверь данные перед сохранением.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }},
            confirmButton={TextButton(
                enabled = (draft.isCategoryRename || draft.isCategoryAdd || draft.isCategoryEdit ||
                    (if (draft.isDelete) voiceEditCandidates.any { it.id == voiceSelectedExpenseId }
                    else ((voiceAmount.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true) && (!draft.isEdit || voiceEditCandidates.any { it.id == voiceSelectedExpenseId }))) &&
                    (draft.isCategoryRename || draft.isCategoryAdd || draft.isCategoryEdit || draft.isDelete || parseUserDate(voiceDate) != null)),
                onClick={
                val confirmedAmount = voiceAmount.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                val confirmedDate = parseUserDate(voiceDate)?.toString() ?: draft.date
                when {
                    draft.isCategoryRename -> {
                        val oldName = draft.category
                        val newName = draft.title.trim()
                        if (newName.isNotBlank() && categories.none { it.name.equals(newName, true) }) {
                            store.prepareUndoSnapshot(); canUndo=true
                            categories = categories.map { if (it.name == oldName) it.copy(name = newName) else it }
                            expenses = expenses.map { if (it.category == oldName) it.copy(category = newName) else it }
                            store.saveCategories(categories); store.saveExpenses(expenses)
                            voiceMessage = "Категория «$oldName» переименована в «$newName»."
                        } else {
                            voiceMessage = "Новое название пустое или такая категория уже существует."
                        }
                    }
                    draft.isIncomeEdit -> {
                        val amount = confirmedAmount ?: return@TextButton
                        store.prepareUndoSnapshot(); canUndo=true
                        val latestEntry = incomeEntries.filter { it.periodStart == periodStart && it.periodEnd == periodEnd }
                            .maxByOrNull { it.date }
                        if (latestEntry != null) {
                            store.updateIncomeEntry(latestEntry.copy(amount = amount))
                            voiceMessage = "Последняя запись дохода «${latestEntry.title}» изменена на ${money(amount)}."
                        } else {
                            store.setPeriodIncomeTotal(periodStart, periodEnd, amount)
                            voiceMessage = "Общая сумма дохода за период установлена: ${money(amount)}."
                        }
                        periodIncomes = store.loadPeriodIncomes()
                        incomeEntries = store.loadIncomeEntries()
                    }
                    draft.isDelete -> {
                        val targetId = voiceSelectedExpenseId ?: return@TextButton
                        val target = expenses.firstOrNull { it.id == targetId } ?: return@TextButton
                        store.prepareUndoSnapshot(); canUndo=true
                        expenses = expenses.filterNot { it.id == targetId }
                        store.saveExpenses(expenses)
                        voiceMessage = "Операция «${target.title}» от ${target.date} на ${money(target.amount)} удалена."
                    }
                    draft.isEdit -> {
                        val amount = confirmedAmount ?: return@TextButton
                        store.prepareUndoSnapshot(); canUndo=true
                        val targetId = voiceSelectedExpenseId ?: return@TextButton
                        val target = expenses.firstOrNull { it.id == targetId } ?: return@TextButton
                        expenses = expenses.map { if (it.id == targetId) it.copy(amount = amount, date = confirmedDate) else it }.sortedByDescending { it.date }
                        store.saveExpenses(expenses)
                        voiceMessage = "Операция «${target.title}» от ${target.date} изменена: ${money(target.amount)} → ${money(amount)}."
                    }
                    draft.isCategoryAdd -> {
                        store.prepareUndoSnapshot(); canUndo=true
                        if (categories.none { it.name.equals(draft.category,true) } && categories.sumOf { it.percent } + draft.amount <= 100.0) {
                            categories = categories + BudgetCategory(draft.category,draft.amount)
                            store.saveCategories(categories)
                        } else {
                            voiceMessage = "Категория уже существует или сумма долей превысит 100%."
                        }
                    }
                    draft.isCategoryEdit -> {
                        store.prepareUndoSnapshot(); canUndo=true
                        val old = categories.firstOrNull { it.name.equals(draft.category,true) }
                        if (old != null && categories.filterNot { it.name==old.name }.sumOf { it.percent } + draft.amount <= 100.0) {
                            categories = categories.map { if (it.name==old.name) it.copy(percent=draft.amount) else it }
                            store.saveCategories(categories)
                        } else {
                            voiceMessage = "Не удалось изменить долю: проверь категорию и сумму процентов (не более 100%)."
                        }
                    }
                    draft.isIncome -> {
                        val amount = confirmedAmount ?: return@TextButton
                        store.prepareUndoSnapshot(); canUndo=true
                        val incomeDate = LocalDate.parse(confirmedDate)
                        val incomePeriod = when {
                            incomeDate.dayOfMonth in 9..23 -> incomeDate.withDayOfMonth(9).toString() to incomeDate.withDayOfMonth(23).toString()
                            incomeDate.dayOfMonth >= 24 -> incomeDate.withDayOfMonth(24).toString() to incomeDate.plusMonths(1).withDayOfMonth(8).toString()
                            else -> incomeDate.minusMonths(1).withDayOfMonth(24).toString() to incomeDate.withDayOfMonth(8).toString()
                        }
                        store.addIncomeEntry(draft.title, amount, confirmedDate, incomePeriod.first, incomePeriod.second)
                        periodIncomes = store.loadPeriodIncomes()
                        incomeEntries = store.loadIncomeEntries()
                        voiceMessage = "Доход ${money(amount)} сохранён отдельной операцией за отчётный период. Общий доход за период: ${money(periodIncome + amount)}."
                    }
                    else -> {
                        val amount = confirmedAmount ?: return@TextButton
                        store.prepareUndoSnapshot(); canUndo=true
                        val updated=(listOf(Expense(System.currentTimeMillis(),draft.title,draft.category,amount,confirmedDate))+expenses).sortedByDescending { it.date }
                        expenses=updated; store.saveExpenses(expenses)
                        val cap=categories.firstOrNull { it.name==draft.category }?.let { plannedForPeriod(income,it.percent,periodStart,periodEnd) } ?: 0.0
                        val spent=updated.filter { it.category==draft.category && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount }
                        voiceMessage = "Расход ${money(amount)} в категории «${draft.category}» сохранён."
                        if(spent>cap) OverspendNotifier.notify(context,"Превышение бюджета","Категория «${draft.category}»: превышение ${money(spent-cap)} за выбранный период.")
                    }
                }
                voiceDraft=null
            }) { Text(when { draft.isDelete -> "Удалить операцию"; draft.isCategoryRename -> "Переименовать категорию"; draft.isCategoryAdd -> "Добавить категорию"; draft.isCategoryEdit -> "Изменить долю"; draft.isIncomeEdit -> "Изменить доход"; draft.isEdit -> "Сохранить изменения"; draft.isIncome -> "Сохранить доход"; else -> "Сохранить расход" }) }},
            dismissButton={TextButton(onClick={voiceDraft=null}) { Text("Отмена") }}
        )
    }
    voiceMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { voiceMessage = null },
            title = { Text("Помощник ThunBudget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message)
                    if (assistantChartData.isNotEmpty()) {
                        Text(assistantChartTitle, fontWeight = FontWeight.SemiBold)
                        val maxValue = assistantChartData.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                        assistantChartData.forEach { (label, value) ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                    Text(money(value), style = MaterialTheme.typography.labelSmall)
                                }
                                LinearProgressIndicator(progress = { (value / maxValue).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        if (ttsReady) speechOutput.speak(assistantSpeechText(message), TextToSpeech.QUEUE_FLUSH, null, "thunbudget_assistant_manual")
                    }, enabled = ttsReady) { Text("Повторить голосом") }
                    TextButton(onClick = { voiceMessage = null; assistantChartData = emptyList() }) { Text("Понятно") }
                }
            }
        )
    }
    if (showAllocationDialog) {
        AllocationProposalDialog(
            categories = categories,
            expenses = expenses,
            onDismiss = { showAllocationDialog = false },
            onSave = { updated ->
                store.prepareUndoSnapshot(); canUndo = true
                manualAllocationSnapshot = categories; store.saveManualAllocationSnapshot(categories)
                categories = updated
                store.saveCategories(categories)
                showAllocationDialog = false
            }
        )
    }
    if (showDynamicAllocationDialog) {
        DynamicAllocationDialog(
            categories = categories,
            expenses = expenses,
            periodStart = periodStart,
            periodEnd = periodEnd,
            onDismiss = { showDynamicAllocationDialog = false },
            onSave = { updated ->
                store.prepareUndoSnapshot()
                canUndo = true
                manualAllocationSnapshot = categories; store.saveManualAllocationSnapshot(categories)
                categories = updated
                store.saveCategories(categories)
                showDynamicAllocationDialog = false
            }
        )
    }
    if (showAssistantDialog) {
        AlertDialog(
            onDismissRequest = { showAssistantDialog = false },
            title = { Text("Помощник ThunBudget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = assistantTextInput,
                        onValueChange = { assistantTextInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Напиши команду или вопрос") },
                        placeholder = { Text("Например: сколько осталось до зарплаты?") },
                        minLines = 2,
                        maxLines = 4
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            enabled = !isListening,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isListening) "Слушаю…" else "Сказать голосом")
                        }
                    }
                    if (isListening) {
                        Text("Говори после звукового сигнала. Распознавание выполняется на устройстве.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(
                            "Помощник работает локально, без отправки финансовых данных в облако. Можно спросить об остатке, расходах, категориях или продиктовать новую операцию.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = assistantTextInput.isNotBlank(),
                    onClick = {
                        val phrase = assistantTextInput.trim()
                        assistantTextInput = ""
                        showAssistantDialog = false
                        handleAssistantPhrase(phrase)
                    }
                ) { Text("Отправить") }
            },
            dismissButton = { TextButton(onClick = { showAssistantDialog = false }) { Text("Закрыть") } }
        )
    }
    if(showPeriodIncomeEditor) PeriodIncomeDialog(periodStart,periodEnd,periodIncome,onDismiss={showPeriodIncomeEditor=false},onSave={amount->
        store.prepareUndoSnapshot(); canUndo=true
        store.setPeriodIncomeTotal(periodStart, periodEnd, amount)
        periodIncomes = store.loadPeriodIncomes()
        incomeEntries = store.loadIncomeEntries()
        showPeriodIncomeEditor=false
    })
    if(showPeriodEditor) PeriodEditorDialog(periodStart,periodEnd,onDismiss={showPeriodEditor=false},onSave={from,to->
        periodStart=from; periodEnd=to; store.savePeriod(from,to); selectedPeriod=-1; showPeriodEditor=false
    })
    if(showCategoryManager) CategoryManagerDialog(categories,onDismiss={showCategoryManager=false},onAdd={name,percent->
        if(categories.none{it.name.equals(name,true)}) {
            categories=categories+BudgetCategory(name,percent); store.saveCategories(categories)
        }
    },onDelete={cat->
        store.prepareUndoSnapshot(); canUndo=true
        categories=categories.filterNot{it.name==cat.name}; store.saveCategories(categories); showCategoryManager=false
    },onEdit={cat->editingCategory=cat;showCategoryManager=false})
    editingCategory?.let { oldCat ->
        CategoryEditDialog(oldCat,onDismiss={editingCategory=null},onSave={name,percent->
            if(categories.none{it.name.equals(name,true)&&it.name!=oldCat.name}) {
                store.prepareUndoSnapshot(); canUndo=true
                categories=categories.map{if(it.name==oldCat.name) it.copy(name=name,percent=percent) else it}
                expenses=expenses.map{if(it.category==oldCat.name) it.copy(category=name) else it}
                store.saveCategories(categories); store.saveExpenses(expenses)
            }
            editingCategory=null
        })
    }
    selectedCategory?.let { categoryName ->
        val cat=categories.firstOrNull { it.name==categoryName }
        var categorySort by remember(categoryName) { mutableStateOf("Дата") }
        var categoryAscending by remember(categoryName) { mutableStateOf(false) }
        val rawPeriod=expenses.filter { it.category==categoryName && it.date>=periodStart && it.date<=periodEnd }
        val inPeriod=when(categorySort) {
            "Название" -> if(categoryAscending) rawPeriod.sortedBy { it.title.lowercase() } else rawPeriod.sortedByDescending { it.title.lowercase() }
            "Сумма" -> if(categoryAscending) rawPeriod.sortedBy { it.amount } else rawPeriod.sortedByDescending { it.amount }
            else -> if(categoryAscending) rawPeriod.sortedBy { it.date } else rawPeriod.sortedByDescending { it.date }
        }
        AlertDialog(onDismissRequest={selectedCategory=null},title={Text(categoryName)},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=500.dp)) {
                Text("Операции за отчётный период",fontWeight=FontWeight.SemiBold)
                Text("${LocalDate.parse(periodStart).format(formatter)} — ${LocalDate.parse(periodEnd).format(formatter)}",style=MaterialTheme.typography.bodySmall)
                Text("Потрачено: ${money(inPeriod.sumOf { it.amount })} · Бюджет: ${money(cat?.let { if(periodIncome>0) periodIncome*it.percent/100.0 else plannedForPeriod(income,it.percent,periodStart,periodEnd) } ?: 0.0)}")
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    var sortExpanded by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick={sortExpanded=true},modifier=Modifier.fillMaxWidth()) { Text("Сортировка: $categorySort") }
                        DropdownMenu(expanded=sortExpanded,onDismissRequest={sortExpanded=false}) { listOf("Дата","Название","Сумма").forEach { option -> DropdownMenuItem(text={Text(option)},onClick={categorySort=option;sortExpanded=false}) } }
                    }
                    TextButton(onClick={categoryAscending=!categoryAscending}) { Text(if(categoryAscending) "↑" else "↓") }
                }
                if(inPeriod.isEmpty()) Text("За этот период операций пока нет.")
                else LazyColumn(Modifier.heightIn(max=280.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(inPeriod,key={it.id}) { expense ->
                        Card(onClick={selectedExpense=expense;selectedCategory=null}) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(expense.title,fontWeight=FontWeight.Medium)
                                        Text(expense.date,style=MaterialTheme.typography.bodySmall)
                                    }
                                    Text("−${money(expense.amount)}",fontWeight=FontWeight.SemiBold)
                                }
                                Text("Нажми, чтобы изменить или удалить",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },confirmButton={TextButton(onClick={addFromCategory=categoryName;selectedCategory=null;showAdd=true}) { Text("Добавить расход") }},dismissButton={TextButton(onClick={selectedCategory=null}) { Text("Закрыть") }})
    }
    if(showAdd) AddExpenseDialog(categories,initialCategory=addFromCategory,onDismiss={showAdd=false},onSave={title,category,amount,date->
        store.prepareUndoSnapshot(); canUndo=true
        val updated=(listOf(Expense(System.currentTimeMillis(),title,category,amount,date))+expenses).sortedByDescending { it.date }
        val spentAfter=updated.filter { it.category==category && it.date>=periodStart && it.date<=periodEnd }.sumOf { it.amount }
        val cap=categories.firstOrNull { it.name==category }?.let { plannedForPeriod(income,it.percent,periodStart,periodEnd) } ?: 0.0
        expenses=updated; store.saveExpenses(expenses); showAdd=false; addFromCategory=null
        if(spentAfter>cap) OverspendNotifier.notify(context, "Превышение бюджета", "Категория «$category»: превышение ${money(spentAfter-cap)} за выбранный период.")
    })
    selectedIncomeEntry?.let { selected ->
        IncomeEntryEditDialog(selected, onDismiss={selectedIncomeEntry=null}, onSave={amount->
            store.prepareUndoSnapshot(); canUndo=true
            store.updateIncomeEntry(selected.copy(amount=amount))
            incomeEntries=store.loadIncomeEntries()
            periodIncomes=store.loadPeriodIncomes()
            selectedIncomeEntry=null
        }, onDelete={
            store.prepareUndoSnapshot(); canUndo=true
            store.deleteIncomeEntry(selected.id)
            incomeEntries=store.loadIncomeEntries()
            periodIncomes=store.loadPeriodIncomes()
            selectedIncomeEntry=null
        })
    }
    selectedExpense?.let { selected ->
        ExpenseEditDialog(selected,categories,onDismiss={selectedExpense=null},onSave={edited->
            store.prepareUndoSnapshot(); canUndo=true
            expenses=expenses.map { if(it.id==selected.id) edited else it }.sortedByDescending { it.date }
            store.saveExpenses(expenses); selectedExpense=null
        },onDelete={
            store.prepareUndoSnapshot(); canUndo=true
            expenses=expenses.filterNot { it.id==selected.id }; store.saveExpenses(expenses); selectedExpense=null
        })
    }
    if(showSettings) SettingsDialog(income,savingsGoal,categories,periodStart,periodEnd,darkTheme=darkTheme,onThemeChange={ darkTheme=it; store.saveDarkTheme(it) },onRequestReset={showResetConfirmation=true},hasPreviousRestoreBackup=store.hasPreviousRestoreBackup(),hasAutomaticBackup=store.hasAutomaticBackup(),externalBackupFolder=externalBackupFolder,externalBackupLastSuccess=store.externalBackupLastSuccess(),externalBackupLastError=store.externalBackupLastError(),onChooseExternalFolder={ externalBackupFolderLauncher.launch(null) },onExportExcel={ exportLauncher.launch("thunbudget-${LocalDate.now()}.xlsx") },onImportExcel={ excelImportLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) },onClearExternalFolder={
        externalBackupFolder?.let { raw -> runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        store.setExternalBackupFolder(null)
        externalBackupFolder=null
    },onDismiss={showSettings=false},onArrangeDashboard={showSettings=false;showDashboardArrangeDialog=true},firstPeriodStartDay=firstPeriodStartDay,firstPeriodEndDay=firstPeriodEndDay,onSaveReportingDays={startDay,endDay->firstPeriodStartDay=startDay;firstPeriodEndDay=endDay;store.saveReportingPeriodDays(startDay,endDay);showSettings=false},onRestoreAutomatic={
        val restored=store.restoreAutomaticBackup()
        if(restored) {
            income=store.loadIncome(); savingsGoal=store.loadSavingsGoal(); categories=store.loadCategories()
            expenses=store.loadExpenses(); recurringPayments=store.loadRecurringPayments(); periodIncomes=store.loadPeriodIncomes(); incomeEntries=store.loadIncomeEntries()
            periodStart=store.loadPeriodStart() ?: periodStart; periodEnd=store.loadPeriodEnd() ?: periodEnd
            selectedPeriod=-1; backupMessage="Автоматическая резервная копия восстановлена."
        } else backupMessage="Не удалось восстановить автоматическую копию."
        showSettings=false
    },onBackup={
        backupExportLauncher.launch("ThunBudget-backup-${LocalDate.now()}.json")
    },onRestore={showSettings=false;showRestoreConfirmation=true},onRestorePrevious={
        val restored = store.restorePreviousBackup()
        if(restored) {
            income=store.loadIncome(); savingsGoal=store.loadSavingsGoal(); categories=store.loadCategories()
            expenses=store.loadExpenses(); periodIncomes=store.loadPeriodIncomes(); incomeEntries=store.loadIncomeEntries()
            periodStart=store.loadPeriodStart() ?: periodStart; periodEnd=store.loadPeriodEnd() ?: periodEnd
            selectedPeriod=-1
            backupMessage="Предыдущее состояние данных восстановлено."
        } else backupMessage="Не удалось восстановить предыдущую копию."
        showSettings=false
    },onSave={newIncome,newSavings,newCats,start,end,renameMap->
        if (renameMap.isNotEmpty()) {
            expenses = expenses.map { expense -> expense.copy(category = renameMap[expense.category] ?: expense.category) }
            store.saveExpenses(expenses)
        }
        income=newIncome; savingsGoal=newSavings; categories=newCats; periodStart=start; periodEnd=end; store.savePeriod(start,end)
        store.saveIncome(income); store.saveSavingsGoal(savingsGoal); store.saveCategories(categories); showSettings=false    })
    if(showResetConfirmation) AlertDialog(
        onDismissRequest={showResetConfirmation=false},
        title={Text("Полностью сбросить данные?")},
        text={Text("Все операции, доходы, категории, настройки бюджета и локальные резервные копии будут удалены без возможности отмены. Внешние экспортированные файлы не затрагиваются.")},
        confirmButton={
            TextButton(onClick={
                showResetConfirmation=false
                val reset = store.resetAllData()
                if (reset) {
                    income=store.loadIncome()
                    savingsGoal=store.loadSavingsGoal()
                    categories=store.loadCategories()
                    expenses=store.loadExpenses()
                    periodIncomes=store.loadPeriodIncomes()
                    incomeEntries=store.loadIncomeEntries()
                    periodStart=store.loadPeriodStart() ?: (if(today.dayOfMonth<=8 || today.dayOfMonth>=24) baseMonth.atDay(24) else baseMonth.atDay(9)).toString()
                    periodEnd=store.loadPeriodEnd() ?: (if(today.dayOfMonth<=8) today.withDayOfMonth(8) else if(today.dayOfMonth>=24) today.plusMonths(1).withDayOfMonth(8) else baseMonth.atDay(23)).toString()
                    selectedPeriod=if(today.dayOfMonth>=24 || today.dayOfMonth<=8) 1 else 0
                    selectedExpense=null
                    selectedCategory=null
                    historyQuery=""
                    historyCategoryFilter="Все категории"
                    historyYearFilter="Все годы";historyMonthNumberFilter="Все месяцы";historyDayFilter="Все числа"
                    historyFromDate=""
                    historyToDate=""
                    amountLimit=100000f
                    darkTheme=store.loadDarkTheme()
                    externalBackupFolder=null
                    tab=0
                    backupMessage="Все данные приложения сброшены. Можно начать новый бюджет."
                } else {
                    backupMessage="Не удалось выполнить полный сброс. Данные не были очищены полностью."
                }
                showSettings=false
            }) { Text("Да, удалить всё",color=Color(0xFFC62828)) }
        },
        dismissButton={TextButton(onClick={showResetConfirmation=false}) { Text("Отмена") }}
    )
    if(showRestoreConfirmation) AlertDialog(
        onDismissRequest={showRestoreConfirmation=false},
        title={Text("Восстановить резервную копию?")},
        text={Text("Текущие данные приложения будут заменены данными из выбранного файла. Перед продолжением убедись, что текущая версия бюджета сохранена отдельно.")},
        confirmButton={TextButton(onClick={showRestoreConfirmation=false;backupImportLauncher.launch(arrayOf("application/json","text/*"))}) { Text("Выбрать файл") }},
        dismissButton={TextButton(onClick={showRestoreConfirmation=false}) { Text("Отмена") }}
    )
    if(showUndoConfirmation) AlertDialog(
        onDismissRequest={showUndoConfirmation=false},
        title={Text("Отменить последнее действие?")},
        text={Text("Будут восстановлены расходы, категории и настройки бюджета до последнего поддерживаемого изменения.")},
        confirmButton={TextButton(onClick={
            showUndoConfirmation=false
            if (store.restoreUndoSnapshot()) {
                expenses=store.loadExpenses(); categories=store.loadCategories(); recurringPayments=store.loadRecurringPayments()
                periodIncomes=store.loadPeriodIncomes(); incomeEntries=store.loadIncomeEntries(); income=store.loadIncome()
                savingsGoal=store.loadSavingsGoal()
                periodStart=store.loadPeriodStart() ?: periodStart
                periodEnd=store.loadPeriodEnd() ?: periodEnd
                canUndo=false
                backupMessage="Последнее действие отменено."
            } else {
                canUndo=false
                backupMessage="Не удалось отменить последнее действие."
            }
        }) { Text("Отменить") }},
        dismissButton={TextButton(onClick={showUndoConfirmation=false}) { Text("Оставить") }}
    )
    backupMessage?.let { message ->
        AlertDialog(onDismissRequest={backupMessage=null},title={Text("Результат операции")},text={Text(message)},confirmButton={TextButton(onClick={backupMessage=null}) { Text("Понятно") }})
    }
    if(showDashboardArrangeDialog) {
        AlertDialog(
            onDismissRequest={showDashboardArrangeDialog=false},
            title={Text("Настроить окна обзора")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Меняй порядок блоков кнопками ↑ и ↓. Порядок сохранится на этом устройстве.")
                    dashboardOrder.forEachIndexed { index,key ->
                        val label=when(key) { "period"->"Отчётный период"; "budget"->"Доступный бюджет"; "income"->"Операции дохода"; else->"Бюджет по категориям" }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(label,modifier=Modifier.weight(1f))
                            IconButton(enabled=index>0,onClick={val next=dashboardOrder.toMutableList();val value=next.removeAt(index);next.add(index-1,value);dashboardOrder=next;store.saveDashboardOrder(next)}) { Text("↑") }
                            IconButton(enabled=index<dashboardOrder.lastIndex,onClick={val next=dashboardOrder.toMutableList();val value=next.removeAt(index);next.add(index+1,value);dashboardOrder=next;store.saveDashboardOrder(next)}) { Text("↓") }
                        }
                    }
                }
            },
            confirmButton={TextButton(onClick={showDashboardArrangeDialog=false}) { Text("Готово") }},
            dismissButton={TextButton(onClick={val defaults=listOf("period","budget","income","categories");dashboardOrder=defaults;store.saveDashboardOrder(defaults)}) { Text("Сбросить") }}
        )
    }
}

private fun parseSpokenAmountCore(normalized: String): Double? {
    // SpeechRecognizer may use narrow/non-breaking spaces in grouped numbers (e.g. «2 000»).
    // A dot followed by three digits is treated as a thousands separator («2.000»).
    val canonical = normalized.replace(Regex("""[\s\p{Z}]+"""), " ").trim()
    val digitMatch = Regex(
        """(?<!\d)(\d+(?:(?:[ \u00A0\u202F\u2009]|\.)\d{3})*(?:[,.]\d{1,2})?)\s*(тысяч[а-я]*|тыс\.?|миллион[а-я]*|млн\.?|миллиард[а-я]*|млрд\.?)?"""
    ).find(canonical)
    if (digitMatch != null) {
        val rawNumber = digitMatch.groupValues[1].replace(Regex("""[\s\p{Z}]+"""), "")
        val normalizedNumber = if (Regex("""^\d{1,3}(?:\.\d{3})+$""").matches(rawNumber)) {
            rawNumber.replace(".", "")
        } else rawNumber
        val number = normalizedNumber.replace(',', '.').toDoubleOrNull()
        if (number != null && number > 0.0) {
            val scale = when {
                digitMatch.groupValues[2].startsWith("миллиард") || digitMatch.groupValues[2].startsWith("млрд") -> 1_000_000_000.0
                digitMatch.groupValues[2].startsWith("миллион") || digitMatch.groupValues[2].startsWith("млн") -> 1_000_000.0
                digitMatch.groupValues[2].startsWith("тысяч") || digitMatch.groupValues[2].startsWith("тыс") -> 1_000.0
                else -> 1.0
            }
            return number * scale
        }
    }

    val words = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "одно" to 1, "одну" to 1,
        "одного" to 1, "одной" to 1, "два" to 2, "две" to 2, "двух" to 2,
        "три" to 3, "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7,
        "восемь" to 8, "девять" to 9, "десять" to 10, "одиннадцать" to 11,
        "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20,
        "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60,
        "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90,
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400,
        "пятьсот" to 500, "шестьсот" to 600, "семьсот" to 700,
        "восемьсот" to 800, "девятьсот" to 900
    )
    var current = 0L
    var total = 0.0
    var found = false
    for (raw in normalized.split(Regex("""[\s,]+"""))) {
        val token = raw.trim('.', ',', '!', '?', ':', ';')
        when {
            words.containsKey(token) -> {
                current += words.getValue(token)
                found = true
            }
            token.startsWith("тысяч") -> {
                total += (if (current == 0L) 1L else current) * 1_000.0
                current = 0
                found = true
            }
            token.startsWith("миллион") || token == "млн" || token == "млн." -> {
                total += (if (current == 0L) 1L else current) * 1_000_000.0
                current = 0
                found = true
            }
            token.startsWith("миллиард") || token == "млрд" || token == "млрд." -> {
                total += (if (current == 0L) 1L else current) * 1_000_000_000.0
                current = 0
                found = true
            }
            token in setOf("тысячу", "тысяча", "тысячи", "тысячей", "тысяче") -> {
                total += (if (current == 0L) 1L else current) * 1_000.0
                current = 0
                found = true
            }
            token in setOf("рубль", "рубля", "рублей", "руб", "руб.", "р", "р.") -> if (found) break
            found -> break
        }
    }
    val result = total + current
    return result.takeIf { found && it > 0.0 }
}

internal fun parseSpokenAmount(normalized: String): Double? {
    val withoutDate = normalized.replace(Regex("""\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b"""), " ")
    val kopecksMatch = Regex("""(?<!\d)(\d{1,2})\s*(?:коп(?:еек|ейки)?|коп\.)""").find(withoutDate.lowercase(Locale("ru", "RU")))
    val kopecks = kopecksMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
    val withoutKopecks = if (kopecksMatch != null) withoutDate.removeRange(kopecksMatch.range) else withoutDate
    val amount = parseSpokenAmountCore(withoutKopecks) ?: return null
    return amount + kopecks / 100.0
}

private fun voiceRecognitionScore(phrase: String, categories: List<BudgetCategory>): Int {
    val normalized = phrase.lowercase(Locale("ru", "RU")).replace('ё', 'е')
    var score = normalized.length.coerceAtMost(120)
    if (Regex("""\b(тысяч[а-я]*|тыс\.?|миллион[а-я]*|млн\.?|миллиард[а-я]*|млрд\.?)\b""").containsMatchIn(normalized)) score += 100
    if (Regex("""\d[ \u00A0]\d{3}""").containsMatchIn(normalized)) score += 80
    if (listOf("руб", "рублей", "рубля", "р.", "р ").any { normalized.contains(it) }) score += 20
    if (listOf("доход", "поступление", "зарплата", "получил", "получила", "добавь", "добавить", "расход", "потратил", "потратила", "купил", "купила", "оплатил", "оплатила").any { normalized.contains(it) }) score += 35
    if (categories.any { normalized.contains(it.name.lowercase(Locale("ru", "RU")).replace('ё', 'е')) }) score += 35
    return score
}

private fun parseVoiceExpense(spoken: String, categories: List<BudgetCategory>): VoiceExpenseDraft? {
    val normalized = spoken.lowercase(Locale("ru", "RU")).replace('ё', 'е').trim()
    val date = parseVoiceDate(normalized, LocalDate.now()) ?: LocalDate.now()

    val renameMatch = Regex("""(?:переименуй|переименовать|назови|назвать)\s+категори(?:ю|и)\s+(.+?)\s+(?:в|на)\s+(.+)$""").find(normalized)
    if (renameMatch != null) {
        val oldName = renameMatch.groupValues[1].trim()
        val newName = renameMatch.groupValues[2].trim().removeSuffix(".")
        val existing = categories.firstOrNull { it.name.equals(oldName, true) }
        if (existing != null && newName.isNotBlank()) {
            return VoiceExpenseDraft(
                title = newName.replaceFirstChar { it.uppercase(Locale("ru", "RU")) },
                category = existing.name,
                amount = 0.0,
                date = date.toString(),
                isCategoryRename = true
            )
        }
        return null
    }

    val isIncomeEdit = listOf("измени доход", "изменить доход", "поменяй доход", "поменять доход", "исправь доход", "отредактируй доход")
        .any { normalized.contains(it) }
    val amount = parseSpokenAmount(normalized) ?: return null
    if (isIncomeEdit) {
        return VoiceExpenseDraft(
            title = "Доход за отчётный период",
            category = "Доход",
            amount = amount,
            date = date.toString(),
            isIncomeEdit = true
        )
    }
    val categoryIntent = listOf("категори", "процент", "долю").any { normalized.contains(it) }
    val categoryAddIntent = categoryIntent &&
        listOf("добавь", "добавить", "создай", "новую").any { normalized.contains(it) }
    if (categoryAddIntent && amount <= 100.0) {
        val marker = Regex("""категори[яюи]?\s+(.+?)\s+(?=(?:\d|ноль|один|одна|одно|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать|двадцать|тридцать|сорок|пятьдесят|шестьдесят|семьдесят|восемьдесят|девяносто|сто|двести|триста|четыреста|пятьсот|шестьсот|семьсот|восемьсот|девятьсот))""").find(normalized)
        val name = marker?.groupValues?.get(1)?.trim()?.removePrefix("на ")?.trim()
        if (!name.isNullOrBlank()) return VoiceExpenseDraft(
            title = name.replaceFirstChar { it.uppercase(Locale("ru", "RU")) },
            category = name.replaceFirstChar { it.uppercase(Locale("ru", "RU")) },
            amount = amount, date = date.toString(), isCategoryAdd = true
        )
    }

    val categoryEditIntent = categoryIntent &&
        listOf("измени", "изменить", "поменяй", "поменять", "установи", "поставь", "скорректируй").any { normalized.contains(it) }
    if (categoryEditIntent && amount <= 100.0) {
        val matched = categories.sortedByDescending { it.name.length }.firstOrNull {
            normalized.contains(it.name.lowercase(Locale("ru", "RU")).replace('ё', 'е'))
        } ?: return null
        return VoiceExpenseDraft(matched.name, matched.name, amount, date.toString(), isCategoryEdit = true)
    }

    val isIncome = listOf(
        "доход", "поступление", "получил", "получила", "зарплата", "зачислили",
        "внеси доход", "добавить доход", "добавь доход", "мне перевели"
    ).any { normalized.contains(it) }
    val isEdit = listOf("измени расход", "изменить расход", "поменяй расход", "поменять расход", "отредактируй расход", "исправь расход").any { normalized.contains(it) }
    val hasExpenseCommand = listOf(
        "добавь", "добавить", "запиши", "записать", "потратил", "потратила",
        "расход", "внеси", "купил", "купила", "оплатил", "оплатила",
        "измени", "изменить", "поменяй", "поменять", "отредактируй", "исправь"
    ).any { normalized.contains(it) }
    if (!isIncome && !hasExpenseCommand) return null

    val matchedCategory = if (isIncome) null else findVoiceCategory(normalized, categories) ?: return null

    return VoiceExpenseDraft(
        title = if (isIncome) "Голосовой доход" else extractVoiceExpenseTitle(spoken, matchedCategory!!.name),
        category = if (isIncome) "Доход" else matchedCategory!!.name,
        amount = amount, date = date.toString(), isIncome = isIncome, isEdit = isEdit
    )


}

@Composable
private fun IncomeEntryEditDialog(entry: IncomeEntry, onDismiss:()->Unit, onSave:(Double)->Unit, onDelete:()->Unit) {
    var amount by remember(entry.id) { mutableStateOf(entry.amount.toString()) }
    val parsed=amount.replace(',','.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Изменить доход")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(entry.title, fontWeight=FontWeight.SemiBold)
                Text("Дата: ${entry.date} · ${entry.periodStart} — ${entry.periodEnd}", style=MaterialTheme.typography.bodySmall)
                if(entry.isLegacyAggregate) Text("Это перенесённая общая сумма старого периода, а не отдельное поступление.", style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=amount,onValueChange={amount=it},label={Text("Сумма, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
            }
        },
        confirmButton={TextButton(enabled=parsed!=null && parsed.isFinite() && parsed>0.0,onClick={onSave(parsed!!)}) { Text("Сохранить") }},
        dismissButton={Row {
            TextButton(onClick=onDelete) { Text("Удалить") }
            TextButton(onClick=onDismiss) { Text("Отмена") }
        }}
    )
}

@Composable
private fun PeriodIncomeDialog(start:String,end:String,current:Double,onDismiss:()->Unit,onSave:(Double)->Unit) {
    var amount by remember(start,end) { mutableStateOf(if(current>0) current.toString() else "") }
    val parsed=amount.replace(',','.').toDoubleOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Доход за отчётный период")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${LocalDate.parse(start).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} — ${LocalDate.parse(end).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=amount,onValueChange={amount=it},label={Text("Доход за период, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
            Text("Доходы за два отчётных периода автоматически суммируются в месячный доход.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },confirmButton={TextButton(onClick={if(parsed!=null&&parsed>=0) onSave(parsed)},enabled=parsed!=null&&parsed>=0){Text("Сохранить")}},dismissButton={
        Row { if(current>0) TextButton(onClick={onSave(0.0)}) { Text("Очистить") }; TextButton(onClick=onDismiss){Text("Отмена")} }
    })
}

@Composable
private fun ChartsScreen(
    expenses:List<Expense>,categories:List<BudgetCategory>,month:YearMonth,onMonthChange:(YearMonth)->Unit,
    showPlan:Boolean,onShowPlanChange:(Boolean)->Unit,showPie:Boolean,onShowPieChange:(Boolean)->Unit,
    selectedChartCategoryNames:String,onSelectedChartCategoryNamesChange:(String)->Unit,
    showCategoryCharts:Boolean,onShowCategoryChartsChange:(Boolean)->Unit,
    fallbackIncome:Double,periodIncomes:List<PeriodIncome>
) {
    val selectedChartCategories=selectedChartCategoryNames.split("|").filter { it.isNotBlank() }.toSet()
    val from=month.atDay(1).toString(); val to=month.atEndOfMonth().toString()
    val monthExpenses=expenses.filter { it.date>=from && it.date<=to }
    val total=monthExpenses.sumOf { it.amount }
    val monthIncome=periodIncomes.filter { runCatching { YearMonth.from(LocalDate.parse(it.end))==month }.getOrDefault(false) }.sumOf { it.amount }.let { if(it>0) it else fallbackIncome }
    val palette=listOf(Color(0xFF315CBA),Color(0xFF168A72),Color(0xFFF2A541),Color(0xFF9A6AD6),Color(0xFFE16C70),Color(0xFF5C9DB8),Color(0xFF8B9A45),Color(0xFF7A8290))
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { Text("Графики",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={onMonthChange(month.minusMonths(1))}) { Text("‹") }
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale("ru","RU"))),modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            TextButton(onClick={if(month<YearMonth.now()) onMonthChange(month.plusMonths(1))}) { Text("›") }
        } }
        item { MetricCard("Фактические расходы за месяц",money(total),Modifier.fillMaxWidth()) }
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Графики по категориям",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                TextButton(onClick={onShowCategoryChartsChange(!showCategoryCharts)}) {
                    Text(if(showCategoryCharts) "Свернуть ▲" else "Развернуть ▼")
                }
            }
        }
        if(showCategoryCharts) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Выберите категории, чтобы добавить или убрать их графики.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    categories.chunked(2).forEach { rowCategories ->
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            rowCategories.forEach { category ->
                                FilterChip(selected=selectedChartCategories.contains(category.name),onClick={
                                    val updated=if(selectedChartCategories.contains(category.name)) selectedChartCategories-category.name else selectedChartCategories+category.name
                                    onSelectedChartCategoryNamesChange(updated.joinToString("|"))
                                },label={Text(category.name)})
                            }
                        }
                    }
                }
            }
            items(selectedChartCategories.filter { name -> categories.any { it.name==name } }.toList(),key={"category-daily-"+it}) { categoryName ->
            val dailyValues=(1..month.lengthOfMonth()).map { day ->
                val date=month.atDay(day).toString()
                monthExpenses.filter { it.category==categoryName && it.date==date }.sumOf { it.amount }
            }
            val maxValue=dailyValues.maxOrNull()?.takeIf { it>0.0 } ?: 1.0
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(categoryName,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                        Text(money(dailyValues.sum()),fontWeight=FontWeight.Bold)
                        TextButton(onClick={onSelectedChartCategoryNamesChange((selectedChartCategories-categoryName).joinToString("|"))}) { Text("Убрать") }
                    }
                    val dayTicks=listOf(1,5,10,15,20,25,month.lengthOfMonth()).distinct().filter { it<=month.lengthOfMonth() }
                    ScaledBarChart(values=dailyValues,xLabels=dayTicks.map { it.toString() },color=Color(0xFF6556D9),height=112.dp)
                }
            }
        }
        }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Плановые и фактические расходы",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); TextButton(onClick={onShowPlanChange(!showPlan)}) { Text(if(showPlan) "Скрыть" else "Показать") } } }
        if(showPlan) {
            items(categories,key={"chart-plan-"+it.name}) { cat ->
                val fact=monthExpenses.filter { it.category==cat.name }.sumOf { it.amount }
                val plan=plannedForPeriod(monthIncome,cat.percent,from,to)
                Card { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) { Text(cat.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold); Text("${money(fact)} / ${money(plan)}",fontWeight=FontWeight.SemiBold) }
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Факт",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress={if(plan>0)(fact/plan).coerceIn(0.0,1.0).toFloat() else 0f},modifier=Modifier.weight(1f),color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                    }
                    Text("${if(fact>plan) "Превышение" else "Остаток"}: ${money(kotlin.math.abs(plan-fact))}",style=MaterialTheme.typography.bodySmall,color=if(fact>plan) Color(0xFFC74444) else Color(0xFF168A72))
                } }
            }
        }
        item { Row(verticalAlignment=Alignment.CenterVertically) { Text("Структура расходов",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f)); TextButton(onClick={onShowPieChange(!showPie)}) { Text(if(showPie) "Скрыть" else "Показать") } } }
        if(showPie) {
            item {
                val values=categories.map { cat -> cat to monthExpenses.filter { it.category==cat.name }.sumOf { it.amount } }.filter { it.second>0 }
                Card { Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(values.isEmpty()) Text("За выбранный месяц расходов пока нет.") else {
                        BoxWithConstraints(contentAlignment=Alignment.Center) {
                            val chartSize = minOf(210.dp, maxWidth * 0.68f)
                            Canvas(Modifier.size(chartSize)) {
                                var angle=-90f
                                val sum=values.sumOf { it.second }.toFloat().coerceAtLeast(1f)
                                val strokeWidth=size.minDimension*0.22f
                                val inset=strokeWidth/2f
                                val arcSize=androidx.compose.ui.geometry.Size(size.width-strokeWidth,size.height-strokeWidth)
                                values.forEachIndexed { index,pair ->                                    val sweep=pair.second.toFloat()/sum*360f
                                    drawArc(color=palette[index%palette.size],startAngle=angle,sweepAngle=sweep,useCenter=false,topLeft=androidx.compose.ui.geometry.Offset(inset,inset),size=arcSize,style=Stroke(width=strokeWidth))
                                    angle+=sweep
                                }
                            }
                            Column(horizontalAlignment=Alignment.CenterHorizontally) { Text(money(total),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge); Text("Всего",style=MaterialTheme.typography.labelSmall) }
                        }
                        values.forEachIndexed { index,pair ->
                            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                                Box(Modifier.size(10.dp).background(palette[index%palette.size],RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(8.dp))
                                Text(pair.first.name,modifier=Modifier.weight(1f),maxLines=2)
                                Text(
                                    "${money(pair.second)} · ${if(total>0)(pair.second/total*100).toInt() else 0}%",
                                    fontWeight=FontWeight.SemiBold,
                                    style=MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                } }
            }
        }
    }
}


@Composable
private fun ScaledBarChart(values:List<Double>,xLabels:List<String>,color:Color,height:androidx.compose.ui.unit.Dp) {
    val maxValue=values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val middleValue=maxValue/2.0
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("тыс. ₽",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.width(42.dp).height(height),verticalArrangement=Arrangement.SpaceBetween) {
                Text(String.format(Locale("ru","RU"),"%.1f",maxValue/1000.0),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(String.format(Locale("ru","RU"),"%.1f",middleValue/1000.0),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(Modifier.weight(1f).height(height)) {
                val slot=size.width/values.size.coerceAtLeast(1)
                val barWidth=(slot*.62f).coerceAtLeast(2f)
                drawLine(Color.Gray.copy(alpha=.22f),androidx.compose.ui.geometry.Offset(0f,0f),androidx.compose.ui.geometry.Offset(size.width,0f),strokeWidth=1f)
                drawLine(Color.Gray.copy(alpha=.22f),androidx.compose.ui.geometry.Offset(0f,size.height/2f),androidx.compose.ui.geometry.Offset(size.width,size.height/2f),strokeWidth=1f)
                drawLine(Color.Gray.copy(alpha=.22f),androidx.compose.ui.geometry.Offset(0f,size.height),androidx.compose.ui.geometry.Offset(size.width,size.height),strokeWidth=1f)
                values.forEachIndexed { index,value ->
                    val barHeight=(value/maxValue).toFloat()*size.height
                    drawRect(color=color,topLeft=androidx.compose.ui.geometry.Offset(index*slot+(slot-barWidth)/2f,size.height-barHeight),size=androidx.compose.ui.geometry.Size(barWidth,barHeight))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start=42.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            xLabels.forEach { label -> Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun AnnualChartsScreen(expenses:List<Expense>,categories:List<BudgetCategory>,year:Int,onYearChange:(Int)->Unit,selectedCategoryNames:String,onSelectedCategoryNamesChange:(String)->Unit,showCategoryCharts:Boolean,onShowCategoryChartsChange:(Boolean)->Unit) {
    val selected=selectedCategoryNames.split("|").filter { it.isNotBlank() }.toSet()
    val yearExpenses=expenses.filter { it.date.startsWith("$year-") }
    val totals=(1..12).map { m -> yearExpenses.filter { runCatching { LocalDate.parse(it.date).monthValue==m }.getOrDefault(false) }.sumOf { it.amount } }
    val total=totals.sum()
    val labels=listOf("Я","Ф","М","А","М","И","И","А","С","О","Н","Д")
    val palette=listOf(Color(0xFF315CBA),Color(0xFF168A72),Color(0xFFF2A541),Color(0xFF9A6AD6),Color(0xFFE16C70),Color(0xFF5C9DB8),Color(0xFF8B9A45),Color(0xFF7A8290))
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { Text("Графики за год",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold) }
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={onYearChange(year-1)}) { Text("‹") }
            Text(year.toString(),modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
            TextButton(onClick={if(year<LocalDate.now().year) onYearChange(year+1)}) { Text("›") }
        } }
        item { MetricCard("Расходы за год",money(total),Modifier.fillMaxWidth()) }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Расходы по месяцам",fontWeight=FontWeight.Bold)
            if(total==0.0) Text("За этот год расходов пока нет.",color=MaterialTheme.colorScheme.onSurfaceVariant) else {
                ScaledBarChart(values=totals,xLabels=labels,color=Color(0xFF6556D9),height=145.dp)
            }
        } } }
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Графики по категориям",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                TextButton(onClick={onShowCategoryChartsChange(!showCategoryCharts)}) {
                    Text(if(showCategoryCharts) "Свернуть ▲" else "Развернуть ▼")
                }
            }
        }
        if(showCategoryCharts) {
            item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Выберите категории для сравнения расходов по месяцам.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                categories.chunked(2).forEach { group -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    group.forEach { cat -> FilterChip(selected=selected.contains(cat.name),onClick={
                        val next=if(selected.contains(cat.name)) selected-cat.name else selected+cat.name
                        onSelectedCategoryNamesChange(next.joinToString("|"))
                    },label={Text(cat.name)}) }
                } }
            } }
            items(selected.filter { name -> categories.any { it.name==name } }.toList(),key={"annual-"+it}) { name ->
            val values=(1..12).map { m -> yearExpenses.filter { it.category==name && runCatching { LocalDate.parse(it.date).monthValue==m }.getOrDefault(false) }.sumOf { it.amount } }
            val sum=values.sum()
            Card { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                    Text(money(sum),fontWeight=FontWeight.Bold)
                    TextButton(onClick={onSelectedCategoryNamesChange((selected-name).joinToString("|"))}) { Text("Убрать") }
                }
                if(sum==0.0) Text("Расходов по категории за год нет.",style=MaterialTheme.typography.bodySmall) else {
                    val color=palette[(categories.indexOfFirst { it.name==name }.coerceAtLeast(0))%palette.size]
                    ScaledBarChart(values=values,xLabels=labels,color=color,height=120.dp)
                    values.forEachIndexed { i,v -> if(v>0.0) Text(java.time.Month.of(i+1).getDisplayName(java.time.format.TextStyle.FULL,Locale("ru","RU"))+": "+money(v),style=MaterialTheme.typography.bodySmall) }
                }
            } }
        }
        }
    }
}

@Composable
private fun SpendingBreakdownCard(
    expenses:List<Expense>,
    categories:List<BudgetCategory>,
    from:String,
    to:String,
    title:String
) {
    val palette=listOf(
        Color(0xFF6556D9),Color(0xFF36AFA8),Color(0xFFF2B84B),Color(0xFF8B7AE8),
        Color(0xFFE16C70),Color(0xFF5C9DB8),Color(0xFF8B9A45),Color(0xFF7A8290)
    )
    val values=categories.map { cat ->
        cat to expenses.filter { it.category==cat.name && it.date>=from && it.date<=to }.sumOf { it.amount }
    }.filter { it.second>0.0 }.sortedByDescending { it.second }
    val total=values.sumOf { it.second }
    Card(shape=RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            if(total<=0.0) {
                Text("За выбранный период расходов пока нет.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(142.dp),contentAlignment=Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            var angle=-90f
                            values.forEachIndexed { index,pair ->
                                val sweep=(pair.second/total*360.0).toFloat()
                                drawArc(color=palette[index%palette.size],startAngle=angle,sweepAngle=sweep,useCenter=false,style=Stroke(width=size.minDimension*.22f))
                                angle+=sweep
                            }
                        }
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(money(total),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                            Text("Всего",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        values.take(6).forEachIndexed { index,pair ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(palette[index%palette.size],RoundedCornerShape(50)))
                                Spacer(Modifier.width(7.dp))
                                Text(pair.first.name,modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,maxLines=1)
                                Text("${(pair.second/total*100).toInt()}%",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
                            }
                        }
                    }
                }
                values.forEachIndexed { index,pair ->
                    if(index<values.size) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(pair.first.name,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                            Text(money(pair.second),style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        }
    }

private fun persistUiExpanded(context: Context, key: String, expanded: Boolean) {
    context.getSharedPreferences("thunbudget_ui_state", Context.MODE_PRIVATE)
        .edit().putBoolean(key, expanded).commit()
}

@Composable
private fun rememberUiExpanded(context: Context, key: String, defaultValue: Boolean = true): MutableState<Boolean> {
    val preferences = remember(context) {
        context.getSharedPreferences("thunbudget_ui_state", Context.MODE_PRIVATE)
    }
    val state = rememberSaveable(key) {
        mutableStateOf(preferences.getBoolean(key, defaultValue))
    }
    SideEffect {
        preferences.edit().putBoolean(key, state.value).apply()
    }
    return state
}

@Composable
private fun MetricCard(label:String,value:String,modifier:Modifier=Modifier) {
    Card(modifier=modifier,shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) { Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(value,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExpenseDialog(categories:List<BudgetCategory>,initialCategory:String?=null,onDismiss:()->Unit,onSave:(String,String,Double,String)->Unit) {
    var title by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var category by remember(initialCategory,categories) { mutableStateOf(initialCategory ?: categories.firstOrNull()?.name ?: "Прочее") }; var date by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"))) }; var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Новый расход")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title,{title=it},label={Text("Описание")},singleLine=true)
            OutlinedTextField(amount,{amount=it},label={Text("Сумма, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                OutlinedTextField(value=category,onValueChange={},modifier=Modifier.menuAnchor(),readOnly=true,label={Text("Категория")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)})
                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { categories.forEach { DropdownMenuItem(text={Text(it.name)},onClick={category=it.name;expanded=false}) } }
            }
            OutlinedTextField(date,{date=it.filter(Char::isDigit).take(8)},label={Text("Дата (ДД.ММ.ГГГГ)")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
        }
    },confirmButton={ TextButton(onClick={ val v=amount.replace(',','.').toDoubleOrNull(); if(title.isNotBlank() && v!=null && v>0 && parseUserDate(date)!=null) onSave(title,category,v,parseUserDate(date)!!.toString()) },enabled=title.isNotBlank() && (amount.replace(',','.').toDoubleOrNull() ?: 0.0)>0 && parseUserDate(date)!=null) { Text("Сохранить") } },dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

@Composable
private fun SettingsDialog(
    income:Double,savings:Double,categories:List<BudgetCategory>,start:String,end:String,
    darkTheme:Boolean,
    onThemeChange:(Boolean)->Unit,onRequestReset:()->Unit,hasPreviousRestoreBackup:Boolean,hasAutomaticBackup:Boolean,
    externalBackupFolder:String?,externalBackupLastSuccess:String?,externalBackupLastError:String?,
    onChooseExternalFolder:()->Unit,onClearExternalFolder:()->Unit,onExportExcel:()->Unit,onImportExcel:()->Unit,
    onDismiss:()->Unit,onRestoreAutomatic:()->Unit,onBackup:()->Unit,onRestore:()->Unit,
    onRestorePrevious:()->Unit,onArrangeDashboard:()->Unit,
    firstPeriodStartDay:Int,firstPeriodEndDay:Int,onSaveReportingDays:(Int,Int)->Unit,
    onSave:(Double,Double,List<BudgetCategory>,String,String,Map<String,String>)->Unit
) {
    var inc by remember { mutableStateOf(income.toString()) }
    var sav by remember { mutableStateOf(savings.toString()) }
    var cats by remember { mutableStateOf(categories) }
    var sourceNames by remember { mutableStateOf(categories.map { it.name }) }
    var from by remember { mutableStateOf(runCatching { LocalDate.parse(start).format(DateTimeFormatter.ofPattern("ddMMyyyy")) }.getOrDefault("")) }
    var to by remember { mutableStateOf(runCatching { LocalDate.parse(end).format(DateTimeFormatter.ofPattern("ddMMyyyy")) }.getOrDefault("")) }
    var section by rememberSaveable { mutableStateOf("income") }
    val sections = listOf(
        "income" to "Доход",
        "period" to "Период",
        "reporting" to "Отчётные окна",
        "overview" to "Обзор",
        "categories" to "Категории",
        "export" to "Excel",
        "backup" to "Копирование",
        "reset" to "Сброс данных"
    )
    val validIncome = inc.replace(',','.').toDoubleOrNull()
    val validSavings = sav.replace(',','.').toDoubleOrNull()
    val parsedFrom = parseUserDate(from)
    val parsedTo = parseUserDate(to)
    val validDates = parsedFrom != null && parsedTo != null && !parsedTo.isBefore(parsedFrom)
    val validCategories = cats.isNotEmpty() && cats.all { it.name.isNotBlank() && it.percent.isFinite() && it.percent >= 0.0 } &&
        cats.map { it.name.trim().lowercase() }.distinct().size == cats.size && cats.sumOf { it.percent } <= 100.0
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Настройки")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.heightIn(max=560.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(sections) { (key,label) ->
                        FilterChip(selected=section==key,onClick={section=key},label={Text(label)})
                    }
                }
                Divider()
                when(section) {
                    "income" -> Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Доход и накопления",fontWeight=FontWeight.Bold)
                        OutlinedTextField(inc,{inc=it},label={Text("Месячный доход, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(sav,{sav=it},label={Text("Цель накоплений, %")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
                        Text("Цель рассчитывается от дохода и используется в планировании бюджета.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "period" -> Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Период планирования",fontWeight=FontWeight.Bold)
                        OutlinedTextField(from,{from=it.filter(Char::isDigit).take(8)},label={Text("Начало (ДД.ММ.ГГГГ)")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(to,{to=it.filter(Char::isDigit).take(8)},label={Text("Окончание (ДД.ММ.ГГГГ)")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
                        if(!validDates) Text("Проверь даты и порядок периода.",color=Color(0xFFC74444),style=MaterialTheme.typography.bodySmall)
                    }
                    "reporting" -> ReportingPeriodSettingsSection(startDay=firstPeriodStartDay,endDay=firstPeriodEndDay,onSave=onSaveReportingDays)
                    "overview" -> Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Настройка главного экрана",fontWeight=FontWeight.Bold)
                        Text("Измени порядок информационных блоков на вкладке «Обзор».",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick=onArrangeDashboard,modifier=Modifier.fillMaxWidth()) { Text("Настроить окна обзора") }
                    }
                    "categories" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=440.dp)) {
                        item { Text("Категории бюджета",fontWeight=FontWeight.Bold) }
                        items(cats.size) { index ->
                            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(cats[index].name,{v->cats=cats.toMutableList().also{it[index]=it[index].copy(name=v)}},label={Text("Название категории")},singleLine=true,modifier=Modifier.fillMaxWidth())
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    OutlinedTextField(cats[index].percent.toString(),{v->cats=cats.toMutableList().also{it[index]=it[index].copy(percent=v.replace(',','.').toDoubleOrNull()?:0.0)}},label={Text("Доля, %")},modifier=Modifier.weight(1f),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                                    TextButton(onClick={cats=cats.filterIndexed{idx,_->idx!=index};sourceNames=sourceNames.filterIndexed{idx,_->idx!=index}}) { Text("Удалить",color=Color(0xFFC74444)) }
                                }
                            }
                        }
                        item { OutlinedButton(onClick={cats=cats+BudgetCategory("",0.0);sourceNames=sourceNames+""},modifier=Modifier.fillMaxWidth()) { Text("Добавить категорию") } }
                        item { Text("Сумма долей: ${cats.sumOf{it.percent}}% (не более 100%)",style=MaterialTheme.typography.labelMedium,color=if(cats.sumOf{it.percent}>100.0) Color(0xFFC74444) else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    "export" -> Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Импорт и экспорт Excel",fontWeight=FontWeight.Bold)
                        Text("Перенос операций выполняется без удаления текущих данных. Повторяющиеся операции пропускаются. Резервная копия приложения находится в отдельном разделе.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick=onExportExcel,modifier=Modifier.fillMaxWidth()) { Text("Экспорт полного отчёта в Excel (.xlsx)") }
                        OutlinedButton(onClick=onImportExcel,modifier=Modifier.fillMaxWidth()) { Text("Импорт операций из Excel (.xlsx)") }
                        Text("Для импорта в книге должен быть лист «Операции» со столбцами: Дата, Описание, Категория, Сумма.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "backup" -> Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Резервное копирование",fontWeight=FontWeight.Bold)
                        Text("Резервные копии предназначены для восстановления данных приложения.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Автокопия создаётся через 10 минут после последнего изменения. Хранятся до 7 последних дневных копий. Выбери папку облачного хранилища, чтобы копии сохранялись вне телефона.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if(externalBackupFolder.isNullOrBlank()) "Автоматическое сохранение во внешнюю папку выключено" else "Автоматическое сохранение во внешнюю папку включено",style=MaterialTheme.typography.bodySmall)
                        externalBackupLastSuccess?.let { Text("Последняя внешняя копия: $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        externalBackupLastError?.let { Text("Ошибка внешнего резервирования: $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                        OutlinedButton(onClick=onChooseExternalFolder,modifier=Modifier.fillMaxWidth()) { Text(if(externalBackupFolder.isNullOrBlank()) "Выбрать папку для автокопий" else "Изменить папку автокопий") }
                        if(!externalBackupFolder.isNullOrBlank()) OutlinedButton(onClick=onClearExternalFolder,modifier=Modifier.fillMaxWidth()) { Text("Отключить внешние автокопии") }
                        OutlinedButton(onClick=onBackup,modifier=Modifier.fillMaxWidth()) { Text("Создать резервную копию вручную") }
                        if(hasAutomaticBackup) OutlinedButton(onClick=onRestoreAutomatic,modifier=Modifier.fillMaxWidth()) { Text("Восстановить автокопию приложения") }
                        OutlinedButton(onClick=onRestore,modifier=Modifier.fillMaxWidth()) { Text("Восстановить из файла") }
                        if(hasPreviousRestoreBackup) OutlinedButton(onClick=onRestorePrevious,modifier=Modifier.fillMaxWidth()) { Text("Отменить последнее восстановление") }
                    }
                    "reset" -> Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Полный сброс данных",fontWeight=FontWeight.Bold)
                        Text("Удалит все операции, доходы, категории, настройки бюджета и локальные резервные копии. Внешние файлы, которые ты ранее экспортировал или сохранил в облачную папку, останутся отдельно.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick=onRequestReset,
                            modifier=Modifier.fillMaxWidth(),
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFC62828),contentColor=Color.White)
                        ) { Text("Полностью сбросить данные") }
                    }
                }
            }
        },
        confirmButton={
            TextButton(
                onClick={
                    if(validIncome!=null && validIncome.isFinite() && validIncome>0 &&
                        validSavings!=null && validSavings.isFinite() && validSavings in 0.0..100.0 &&
                        validCategories && validDates) {
                        onSave(validIncome,validSavings,cats.map{it.copy(name=it.name.trim())},parsedFrom!!.toString(),parsedTo!!.toString(),
                            sourceNames.zip(cats).filter{it.first.isNotBlank()}.associate{it.first to it.second.name.trim()})
                    }
                },
                enabled=validIncome!=null && validIncome.isFinite() && validIncome>0 &&
                    validSavings!=null && validSavings.isFinite() && validSavings in 0.0..100.0 &&
                    validCategories && validDates
            ) { Text("Сохранить") }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("Закрыть")}}
    )
}

@Composable
private fun ReportingPeriodSettingsSection(startDay: Int, endDay: Int, onSave: (Int, Int) -> Unit) {
    var startText by remember(startDay) { mutableStateOf(startDay.toString()) }
    var endText by remember(endDay) { mutableStateOf(endDay.toString()) }
    var showConfirmation by remember { mutableStateOf(false) }
    val start = startText.toIntOrNull()
    val end = endText.toIntOrNull()
    val valid = start != null && end != null && start in 1..31 && end in start..31
    val preview = if (valid) runCatching { ReportingPeriodCalculator.propose(YearMonth.now(), start!!, end!!) }.getOrNull() else null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Первое отчётное окно", fontWeight = FontWeight.Bold)
        OutlinedTextField(value=startText,onValueChange={startText=it.filter(Char::isDigit).take(2)},label={Text("Начало первого окна (день месяца)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=endText,onValueChange={endText=it.filter(Char::isDigit).take(2)},label={Text("Окончание первого окна (день месяца)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
        if (preview != null) {
            Text("Предлагаемое второе окно: ${preview.second.start.format(DateTimeFormatter.ofPattern("dd.MM"))} — ${preview.second.end.format(DateTimeFormatter.ofPattern("dd.MM"))}.")
            Text("Расчёт учитывает фактическое число дней месяца, включая февраль и високосный год.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text("Укажи корректные дни: окончание не может быть раньше начала.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        Button(onClick={if(valid) showConfirmation=true},enabled=valid,modifier=Modifier.fillMaxWidth()) { Text("Проверить второе окно") }
    }
    if (showConfirmation && preview != null && start != null && end != null) {
        AlertDialog(
            onDismissRequest={showConfirmation=false},
            title={Text("Подтвердить отчётные окна")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Первое окно: ${preview.first.start.format(DateTimeFormatter.ofPattern("dd.MM"))} — ${preview.first.end.format(DateTimeFormatter.ofPattern("dd.MM"))}")
                    Text("Второе окно: ${preview.second.start.format(DateTimeFormatter.ofPattern("dd.MM"))} — ${preview.second.end.format(DateTimeFormatter.ofPattern("dd.MM"))}")
                    Text("Второе окно автоматически заполнит оставшиеся календарные дни до следующего первого окна.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton={TextButton(onClick={showConfirmation=false;onSave(start,end)}){Text("Подтвердить")}},
            dismissButton={
                Row {
                    TextButton(onClick={showConfirmation=false}){Text("Изменить период")}
                    TextButton(onClick={startText=startDay.toString();endText=endDay.toString();showConfirmation=false}){Text("Отменить действие")}
                }
            }
        )
    }
}

@Composable
private fun PeriodEditorDialog(start:String,end:String,onDismiss:()->Unit,onSave:(String,String)->Unit) {
    var from by remember(start) { mutableStateOf(runCatching { LocalDate.parse(start).format(DateTimeFormatter.ofPattern("ddMMyyyy")) }.getOrDefault("")) }
    var to by remember(end) { mutableStateOf(runCatching { LocalDate.parse(end).format(DateTimeFormatter.ofPattern("ddMMyyyy")) }.getOrDefault("")) }
    val parsedFrom = parseUserDate(from)
    val parsedTo = parseUserDate(to)
    val valid = parsedFrom != null && parsedTo != null && !parsedTo.isBefore(parsedFrom)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Отчётный период")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Укажи любые даты. Например, с 1-го по 14-е число или с 1-го по конец месяца.")
            OutlinedTextField(from,{from=it.filter(Char::isDigit).take(8)},label={Text("Начало (ДД.ММ.ГГГГ)")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            OutlinedTextField(to,{to=it.filter(Char::isDigit).take(8)},label={Text("Окончание (ДД.ММ.ГГГГ)")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            if(!valid) Text("Проверь даты и порядок периода.",color=Color(0xFFC74444),style=MaterialTheme.typography.bodySmall)
        }
    },confirmButton={TextButton(onClick={if(valid) onSave(parsedFrom!!.toString(),parsedTo!!.toString())},enabled=valid){Text("Применить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

@Composable
private fun CategoryManagerDialog(categories:List<BudgetCategory>,onDismiss:()->Unit,onAdd:(String,Double)->Unit,onDelete:(BudgetCategory)->Unit,onEdit:(BudgetCategory)->Unit) {
    var name by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Категории бюджета")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=480.dp)) {
            Text("Удаление категории не удаляет старые операции — они останутся в истории.")
            categories.forEach { cat ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(cat.name,modifier=Modifier.weight(1f))
                    IconButton(onClick={onEdit(cat)}) { Text("✎") }
                    IconButton(onClick={onDelete(cat)}) { Text("×",color=Color(0xFFC74444)) }                }
            }
            Divider()
            Text("Добавить категорию",fontWeight=FontWeight.SemiBold)
            OutlinedTextField(name,{name=it},label={Text("Название")},singleLine=true)
            OutlinedTextField(percent,{percent=it},label={Text("Доля бюджета, %")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        }
    },confirmButton={
        val value=percent.replace(',','.').toDoubleOrNull()
        TextButton(onClick={if(name.isNotBlank()&&value!=null&&value>=0) {onAdd(name.trim(),value);name="";percent=""}},enabled=name.isNotBlank()&&value!=null&&value>=0) { Text("Добавить") }
    },dismissButton={TextButton(onClick=onDismiss){Text("Готово")}})
}

@Composable
private fun CategoryEditDialog(category:BudgetCategory,onDismiss:()->Unit,onSave:(String,Double)->Unit) {
    var name by remember(category.name) { mutableStateOf(category.name) }
    var percent by remember(category.name) { mutableStateOf(category.percent.toString()) }
    val value=percent.replace(',','.').toDoubleOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={Text("Изменить категорию")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("Название категории")},singleLine=true)
            OutlinedTextField(percent,{percent=it},label={Text("Доля бюджета, %")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        }
    },confirmButton={TextButton(onClick={if(name.isNotBlank()&&value!=null&&value>=0) onSave(name.trim(),value)},enabled=name.isNotBlank()&&value!=null&&value>=0){Text("Сохранить")}},dismissButton={TextButton(onClick=onDismiss){Text("Отмена")}})
}

private fun plannedForPeriod(income: Double, percent: Double, start: String, end: String): Double {
    val from = LocalDate.parse(start)
    val to = LocalDate.parse(end)
    if (to.isBefore(from)) return 0.0
    var day = from
    var total = 0.0
    while (!day.isAfter(to)) {
        total += income * percent / 100.0 / day.lengthOfMonth()
        day = day.plusDays(1)
    }
    return total
}

private fun money(value:Double):String = String.format(Locale("ru","RU"),"%,.0f ₽",value)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseEditDialog(
    expense: Expense,
    categories: List<BudgetCategory>,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(expense.id) { mutableStateOf(expense.title) }
    var amount by remember(expense.id) { mutableStateOf(expense.amount.toString()) }
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var date by remember(expense.id) { mutableStateOf(runCatching { LocalDate.parse(expense.date).format(DateTimeFormatter.ofPattern("ddMMyyyy")) }.getOrDefault("")) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Изменить расход")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title,{title=it},label={Text("Описание")},singleLine=true)
                OutlinedTextField(amount,{amount=it},label={Text("Сумма, ₽")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
                ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                    OutlinedTextField(value=category,onValueChange={},modifier=Modifier.menuAnchor(),readOnly=true,label={Text("Категория")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)})
                    ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { categories.forEach { DropdownMenuItem(text={Text(it.name)},onClick={category=it.name;expanded=false}) } }
                }
                OutlinedTextField(date,{date=it.filter(Char::isDigit).take(8)},label={Text("Дата (ДД.ММ.ГГГГ)")},placeholder={Text("ДД.ММ.ГГГГ")},visualTransformation=DateDigitsVisualTransformation,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            }
        },
        confirmButton={
            TextButton(onClick={
                val v=amount.replace(',','.').toDoubleOrNull()
                if(title.isNotBlank() && v!=null && v>0 && parseUserDate(date)!=null)
                    onSave(expense.copy(title=title,category=category,amount=v,date=parseUserDate(date)!!.toString()))
            },enabled=title.isNotBlank() && (amount.replace(',','.').toDoubleOrNull() ?: 0.0)>0 && parseUserDate(date)!=null) { Text("Сохранить") }
        },
        dismissButton={
            Row {
                TextButton(onClick=onDelete) { Text("Удалить",color=Color(0xFFC74444)) }
                TextButton(onClick=onDismiss) { Text("Отмена") }
            }
        }
    )
}


private data class VoiceExpenseDraft(
    val title: String,
    val category: String,
    val amount: Double,
    val date: String,
    val sourcePhrase: String = "",
    val isIncome: Boolean = false,
    val isCategoryAdd: Boolean = false,
    val isCategoryEdit: Boolean = false,
    val isEdit: Boolean = false,
    val isCategoryRename: Boolean = false,
    val isIncomeEdit: Boolean = false,
    val isDelete: Boolean = false
)

private fun startOfflineSpeechRecognition(
    context: Context,
    recognizer: SpeechRecognizer,
    onResult: (List<String>) -> Unit,
    onError: (Int) -> Unit
) {
    val errorCallback = onError
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { errorCallback(error) }
        override fun onResults(results: Bundle?) {
            val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (alternatives.isEmpty()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(alternatives)
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }
    recognizer.startListening(intent)


}