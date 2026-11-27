package ru.scooblydoon.thunbudget

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * A single, dated income operation. Legacy rows are kept visible as one
 * explicitly-labelled aggregate because the old format did not store details.
 */
data class IncomeEntry(
    val id: String,
    val title: String,
    val amount: Double,
    val date: String,
    val periodStart: String,
    val periodEnd: String,
    val isLegacyAggregate: Boolean = false,
    val isCarryover: Boolean = false,
    val linkedExpenseId: Long? = null,
    val isAdditional: Boolean = false
)

/**
 * Replaces only the regular/base income for one reporting period.
 * Additional one-off receipts and explicitly linked carryover entries are
 * retained so editing the base amount cannot silently delete them.
 */
internal object IncomeLedgerRules {
    fun replacePrimaryIncome(
        entries: List<IncomeEntry>,
        start: String,
        end: String,
        amount: Double
    ): List<IncomeEntry> {
        require(amount.isFinite() && amount >= 0.0)
        val periodStart = LocalDate.parse(start)
        val periodEnd = LocalDate.parse(end)
        require(!periodEnd.isBefore(periodStart))
        val periodEntries = entries.filter { it.periodStart == start && it.periodEnd == end }
        val preserved = periodEntries.filter { it.isCarryover || it.isAdditional }
        val retained = entries.filterNot { it.periodStart == start && it.periodEnd == end }
        val primary = if (amount > 0.0) {
            listOf(
                IncomeEntry(
                    id = "manual-$start-$end",
                    title = "Доход за период",
                    amount = amount,
                    date = end,
                    periodStart = start,
                    periodEnd = end
                )
            )
        } else {
            emptyList()
        }
        return retained + preserved + primary
    }
}

data class LegacyPeriodIncome(
    val start: String,
    val end: String,
    val amount: Double,
    val earnedAmount: Double = amount,
    val carryoverAmount: Double = 0.0
)

object IncomeLedgerMigration {
    fun migrate(
        legacy: List<LegacyPeriodIncome>,
        existing: List<IncomeEntry> = emptyList()
    ): List<IncomeEntry> {
        val result = existing.toMutableList()
        val knownIds = result.mapTo(mutableSetOf()) { it.id }
        // Legacy summaries can overlap with detailed entries already written
        // to the new ledger. Import only the missing difference per period so
        // migration neither duplicates an existing total nor drops a partial one.
        val earnedTotals = result.filterNot { it.isCarryover }
            .groupBy { it.periodStart to it.periodEnd }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .toMutableMap()
        val carryoverTotals = result.filter { it.isCarryover }
            .groupBy { it.periodStart to it.periodEnd }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .toMutableMap()

        legacy.forEachIndexed { index, row ->
            val start = LocalDate.parse(row.start)
            val end = LocalDate.parse(row.end)
            require(!end.isBefore(start)) { "Некорректный отчётный период." }
            require(row.amount.isFinite() && row.amount >= 0.0 &&
                row.earnedAmount.isFinite() && row.earnedAmount >= 0.0 &&
                row.carryoverAmount.isFinite() && row.carryoverAmount >= 0.0
            ) { "Некорректная сумма дохода." }

            val periodKey = row.start to row.end
            val earnedId = "legacy-${row.start}-${row.end}-${index}"
            val missingEarned = (row.earnedAmount - (earnedTotals[periodKey] ?: 0.0)).coerceAtLeast(0.0)
            if (missingEarned > 0.001 && knownIds.add(earnedId)) {
                result += IncomeEntry(
                    id = earnedId,
                    title = "Перенесённый доход за период",
                    amount = missingEarned,
                    date = row.end,
                    periodStart = row.start,
                    periodEnd = row.end,
                    isLegacyAggregate = true
                )
                earnedTotals[periodKey] = (earnedTotals[periodKey] ?: 0.0) + missingEarned
            }

            val carryoverId = "legacy-carryover-${row.start}-${row.end}-${index}"
            val missingCarryover = (row.carryoverAmount - (carryoverTotals[periodKey] ?: 0.0)).coerceAtLeast(0.0)
            if (missingCarryover > 0.001 && knownIds.add(carryoverId)) {
                result += IncomeEntry(
                    id = carryoverId,
                    title = "Перенос средств за период",
                    amount = missingCarryover,
                    date = row.end,
                    periodStart = row.start,
                    periodEnd = row.end,
                    isLegacyAggregate = true,
                    isCarryover = true
                )
                carryoverTotals[periodKey] = (carryoverTotals[periodKey] ?: 0.0) + missingCarryover
            }
        }
        return result
    }
}

/**
 * SharedPreferences-backed ledger. Migration is validated before the single
 * commit; legacy keys are deliberately retained for rollback/old-version safety.
 */
class IncomeLedgerRepository(private val preferences: SharedPreferences) {
    companion object {
        const val ENTRIES_KEY = "income_entries"
        const val MIGRATION_KEY = "income_ledger_migration_v1"
        private const val LEGACY_KEY = "period_incomes"
    }

    fun load(): List<IncomeEntry> = decodeEntries(preferences.getString(ENTRIES_KEY, "[]") ?: "[]")

    fun migrateLegacyIfNeeded(): Boolean {
        if (preferences.getBoolean(MIGRATION_KEY, false)) return true
        return runCatching {
            val legacyRaw = preferences.getString(LEGACY_KEY, "[]") ?: "[]"
            val legacyJson = JSONArray(legacyRaw)
            val legacy = (0 until legacyJson.length()).map { index ->
                val item = legacyJson.getJSONObject(index)
                LegacyPeriodIncome(
                    start = item.getString("start"),
                    end = item.getString("end"),
                    amount = item.getDouble("amount"),
                    earnedAmount = item.optDouble("earnedAmount", item.getDouble("amount")),
                    carryoverAmount = item.optDouble("carryoverAmount", 0.0)
                )
            }
            val currentRaw = preferences.getString(ENTRIES_KEY, "[]") ?: "[]"
            val current = decodeEntries(currentRaw)
            val migrated = IncomeLedgerMigration.migrate(legacy, current)
            val editor = preferences.edit()
                .putString(ENTRIES_KEY, encodeEntries(migrated))
                .putBoolean(MIGRATION_KEY, true)
            require(editor.commit()) { "Не удалось сохранить новый журнал доходов." }
            true
        }.getOrDefault(false)
    }

    fun save(entries: List<IncomeEntry>): Boolean = runCatching {
        validate(entries)
        preferences.edit().putString(ENTRIES_KEY, encodeEntries(entries)).commit()
    }.getOrDefault(false)

    fun add(
        title: String,
        amount: Double,
        date: LocalDate,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        isCarryover: Boolean = false,
        linkedExpenseId: Long? = null,
        isAdditional: Boolean = false
    ): IncomeEntry {
        require(title.isNotBlank())
        require(amount.isFinite() && amount > 0.0)
        require(!periodEnd.isBefore(periodStart))
        // The transaction date is independent from the selected reporting-period association.
        val entry = IncomeEntry(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            amount = amount,
            date = date.toString(),
            periodStart = periodStart.toString(),
            periodEnd = periodEnd.toString(),
            isCarryover = isCarryover,
            linkedExpenseId = linkedExpenseId,
            isAdditional = isAdditional
        )
        check(save(load() + entry)) { "Не удалось сохранить доход." }
        return entry
    }

    fun update(entry: IncomeEntry): Boolean {
        val entries = load()
        if (entries.none { it.id == entry.id }) return false
        return save(entries.map { if (it.id == entry.id) entry else it })
    }

    fun delete(id: String): Boolean {
        val entries = load()
        if (entries.none { it.id == id }) return false
        return save(entries.filterNot { it.id == id })
    }

    fun totalForPeriod(start: String, end: String): Double =
        load().filter { it.periodStart == start && it.periodEnd == end }.sumOf { it.amount }

    private fun validate(entries: List<IncomeEntry>) {
        require(entries.map { it.id }.distinct().size == entries.size) { "Повторяется идентификатор дохода." }
        entries.forEach {
            require(it.id.isNotBlank() && it.title.isNotBlank())
            require(it.amount.isFinite() && it.amount > 0.0)
            require(it.linkedExpenseId == null || (it.isCarryover && it.linkedExpenseId > 0L))
            require(!it.isAdditional || !it.isCarryover) { "Дополнительный доход не может одновременно быть переносом." }
            val date = LocalDate.parse(it.date)
            val start = LocalDate.parse(it.periodStart)
            val end = LocalDate.parse(it.periodEnd)
            require(!end.isBefore(start) && !date.isBefore(start) && !date.isAfter(end))
        }
    }

    private fun decodeEntries(raw: String): List<IncomeEntry> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            IncomeEntry(
                id = item.getString("id"),
                title = item.getString("title"),
                amount = item.getDouble("amount"),
                date = item.getString("date"),
                periodStart = item.getString("periodStart"),
                periodEnd = item.getString("periodEnd"),
                isLegacyAggregate = item.optBoolean("isLegacyAggregate", false),
                isCarryover = item.optBoolean("isCarryover", false),
                linkedExpenseId = if (item.has("linkedExpenseId") && !item.isNull("linkedExpenseId")) item.optLong("linkedExpenseId").takeIf { it > 0L } else null,
                isAdditional = item.optBoolean("isAdditional", false)
            )
        }.also(::validate)
    }

    private fun encodeEntries(entries: List<IncomeEntry>): String {
        validate(entries)
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject()
                .put("id", entry.id)
                .put("title", entry.title)
                .put("amount", entry.amount)
                .put("date", entry.date)
                .put("periodStart", entry.periodStart)
                .put("periodEnd", entry.periodEnd)
                .put("isLegacyAggregate", entry.isLegacyAggregate)
                .put("isCarryover", entry.isCarryover)
                .put("linkedExpenseId", entry.linkedExpenseId)
                .put("isAdditional", entry.isAdditional))
        }
        return array.toString()
    }
}
