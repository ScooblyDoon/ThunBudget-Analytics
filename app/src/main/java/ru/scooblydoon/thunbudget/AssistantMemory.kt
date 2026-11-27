package ru.scooblydoon.thunbudget

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Small on-device working memory for follow-up questions.
 *
 * It stores only query context (period/category/intent), not transaction rows,
 * balances, amounts, raw user phrases, or model prompts.
 */
internal data class AssistantMemoryState(
    val lastPeriodId: String? = null,
    val lastPeriodName: String? = null,
    val lastPeriodStart: String? = null,
    val lastPeriodEnd: String? = null,
    val lastCategory: String? = null,
    val lastIntent: String? = null,
    val updatedAt: String? = null,
    val turnCount: Int = 0
)

internal object AssistantMemoryRules {
    fun remember(
        current: AssistantMemoryState,
        intent: String,
        periodId: String? = null,
        periodName: String? = null,
        periodStart: String? = null,
        periodEnd: String? = null,
        category: String? = null,
        timestamp: String
    ): AssistantMemoryState {
        val validRange = validRange(periodStart, periodEnd)
        return current.copy(
            lastPeriodId = clean(periodId, 100) ?: current.lastPeriodId,
            lastPeriodName = clean(periodName, 80) ?: current.lastPeriodName,
            lastPeriodStart = validRange?.first ?: current.lastPeriodStart,
            lastPeriodEnd = validRange?.second ?: current.lastPeriodEnd,
            lastCategory = clean(category, 80) ?: current.lastCategory,
            lastIntent = clean(intent, 60),
            updatedAt = clean(timestamp, 40),
            turnCount = (current.turnCount + 1).coerceAtMost(Int.MAX_VALUE)
        )
    }

    fun resolvePeriodId(
        memory: AssistantMemoryState,
        availablePeriodIds: Set<String>
    ): String? = memory.lastPeriodId?.takeIf { it in availablePeriodIds }

    fun clear(): AssistantMemoryState = AssistantMemoryState()

    private fun validRange(start: String?, end: String?): Pair<String, String>? {
        if (start.isNullOrBlank() || end.isNullOrBlank()) return null
        val parsedStart = runCatching { LocalDate.parse(start) }.getOrNull() ?: return null
        val parsedEnd = runCatching { LocalDate.parse(end) }.getOrNull() ?: return null
        if (parsedEnd.isBefore(parsedStart)) return null
        return parsedStart.toString() to parsedEnd.toString()
    }

    private fun clean(value: String?, maxLength: Int): String? =
        value?.trim()?.take(maxLength)?.takeIf { it.isNotEmpty() }
}

/** SharedPreferences-backed, versioned assistant context. */
internal class AssistantMemoryRepository(private val preferences: SharedPreferences) {
    companion object {
        const val KEY = "assistant_memory_v1"
        private const val SCHEMA_VERSION = 1
    }

    fun load(): AssistantMemoryState = runCatching {
        val raw = preferences.getString(KEY, null) ?: return AssistantMemoryState()
        val json = JSONObject(raw)
        require(json.optInt("schemaVersion", -1) == SCHEMA_VERSION)
        AssistantMemoryState(
            lastPeriodId = json.optStringOrNull("lastPeriodId"),
            lastPeriodName = json.optStringOrNull("lastPeriodName"),
            lastPeriodStart = json.optStringOrNull("lastPeriodStart"),
            lastPeriodEnd = json.optStringOrNull("lastPeriodEnd"),
            lastCategory = json.optStringOrNull("lastCategory"),
            lastIntent = json.optStringOrNull("lastIntent"),
            updatedAt = json.optStringOrNull("updatedAt"),
            turnCount = json.optInt("turnCount", 0).coerceAtLeast(0)
        )
    }.getOrDefault(AssistantMemoryState())

    fun save(state: AssistantMemoryState) {
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("lastPeriodId", state.lastPeriodId)
            .put("lastPeriodName", state.lastPeriodName)
            .put("lastPeriodStart", state.lastPeriodStart)
            .put("lastPeriodEnd", state.lastPeriodEnd)
            .put("lastCategory", state.lastCategory)
            .put("lastIntent", state.lastIntent)
            .put("updatedAt", state.updatedAt)
            .put("turnCount", state.turnCount.coerceAtLeast(0))
        check(preferences.edit().putString(KEY, json.toString()).commit()) {
            "Не удалось сохранить контекст помощника."
        }
    }

    fun remember(
        current: AssistantMemoryState,
        intent: String,
        period: FlexiblePeriodDefinition? = null,
        periodStart: String? = null,
        periodEnd: String? = null,
        category: String? = null,
        timestamp: String = java.time.Instant.now().toString()
    ): AssistantMemoryState {
        val updated = AssistantMemoryRules.remember(
            current = current,
            intent = intent,
            periodId = period?.id,
            periodName = period?.name,
            periodStart = period?.start?.toString() ?: periodStart,
            periodEnd = period?.end?.toString() ?: periodEnd,
            category = category,
            timestamp = timestamp
        )
        save(updated)
        return updated
    }

    fun clear() {
        check(preferences.edit().remove(KEY).commit()) {
            "Не удалось очистить память помощника."
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
