package ru.scooblydoon.thunbudget

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale

/**
 * Small, transparent Russian-language vocabulary for mapping everyday words
 * to categories that actually exist in the user's budget.
 *
 * Merchant MCC codes are deliberately not treated as a source of truth here:
 * they describe a merchant's primary business, not the exact purchased item.
 */
internal object AssistantRecognitionDictionary {
    private val builtInAliases = linkedMapOf(
        listOf("продукт", "питание") to listOf(
            "еда", "продукты", "супермаркет", "пятерочка", "пятерочка",
            "магнит", "перекресток", "перекрёсток", "лента", "ашан",
            "окей", "хлеб", "молоко", "овощи", "фрукты"
        ),
        listOf("транспорт") to listOf(
            "такси", "автобус", "метро", "проезд", "бензин", "заправка",
            "парковка", "электричка", "каршеринг", "маршрутка", "топливо"
        ),
        listOf("кафе", "ресторан", "развлеч") to listOf(
            "ресторан", "кофе", "кофейня", "доставка еды", "фастфуд",
            "столовая", "пицца", "кино", "театр", "концерт"
        ),
        listOf("жиль", "жкх", "дом") to listOf(
            "коммуналка", "жкх", "аренда", "ипотека", "квартплата",
            "отопление", "водоснабжение", "электричество"
        ),
        listOf("здоров", "медицин") to listOf(
            "аптека", "лекарства", "врач", "поликлиника", "анализы",
            "стоматолог", "медицина"
        ),
        listOf("покуп", "одеж", "обув") to listOf(
            "одежда", "обувь", "маркетплейс", "озон", "вайлдберриз",
            "wildberries", "подарок"
        ),
        listOf("связ", "интернет") to listOf(
            "телефон", "сотовая связь", "мобильная связь", "интернет", "связь"
        )
    )

    fun normalize(value: String): String = value
        .lowercase(Locale("ru", "RU"))
        .replace('ё', 'е')
        .replace(Regex("""[^а-яa-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun resolveCategory(
        phrase: String,
        availableCategories: List<String>,
        userAliases: Map<String, String> = emptyMap()
    ): String? {
        if (availableCategories.isEmpty()) return null
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank()) return null

        // A user's exact category name always wins over a generic synonym.
        availableCategories
            .sortedByDescending { normalize(it).length }
            .firstOrNull { category -> contains(normalizedPhrase, normalize(category)) }
            ?.let { return it }

        // Confirmed personal aliases take precedence over built-in aliases.
        userAliases.entries
            .sortedByDescending { normalize(it.key).length }
            .firstOrNull { (alias, target) ->
                availableCategories.any { it.equals(target, ignoreCase = true) } &&
                    contains(normalizedPhrase, normalize(alias))
            }
            ?.let { (_, target) ->
                return availableCategories.first { it.equals(target, ignoreCase = true) }
            }

        for (category in availableCategories.sortedByDescending { normalize(it).length }) {
            val normalizedCategory = normalize(category)
            val group = builtInAliases.entries.firstOrNull { (stems, _) ->
                stems.any { stem -> normalizedCategory.contains(normalize(stem)) }
            } ?: continue
            if (group.value.any { alias -> contains(normalizedPhrase, normalize(alias)) }) {
                return category
            }
        }
        return null
    }

    private fun contains(text: String, token: String): Boolean {
        if (token.isBlank()) return false
        val regex = Regex("""(?<![а-яa-z0-9])${Regex.escape(token)}(?![а-яa-z0-9])""")
        return regex.containsMatchIn(text)
    }
}

/**
 * User-confirmed aliases are stored locally and versioned separately from
 * the expense ledger. The alias target must be an existing category.
 */
internal class AssistantRecognitionRepository(private val preferences: SharedPreferences) {
    companion object {
        const val KEY = "assistant_recognition_aliases_v1"
        private const val SCHEMA_VERSION = 1
    }

    fun loadCategoryAliases(): Map<String, String> = runCatching {
        val raw = preferences.getString(KEY, null) ?: return emptyMap()
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION)
        val aliases = root.getJSONObject("aliases")
        val result = linkedMapOf<String, String>()
        val keys = aliases.keys()
        while (keys.hasNext()) {
            val alias = keys.next()
            val normalizedAlias = AssistantRecognitionDictionary.normalize(alias)
            val category = aliases.optString(alias).trim()
            if (normalizedAlias.isNotBlank() && category.isNotBlank()) {
                result[normalizedAlias] = category
            }
        }
        result
    }.getOrDefault(emptyMap())

    fun rememberCategoryAlias(alias: String, category: String, availableCategories: List<String>) {
        val normalizedAlias = AssistantRecognitionDictionary.normalize(alias)
        require(normalizedAlias.length in 2..60) { "Синоним должен содержать от 2 до 60 символов." }
        val canonicalCategory = availableCategories.firstOrNull { it.equals(category.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("Категория «$category» не найдена.")
        val updated = loadCategoryAliases().toMutableMap()
        updated[normalizedAlias] = canonicalCategory
        save(updated)
    }

    fun removeCategoryAlias(alias: String) {
        val normalizedAlias = AssistantRecognitionDictionary.normalize(alias)
        val updated = loadCategoryAliases().toMutableMap()
        if (updated.remove(normalizedAlias) != null) save(updated)
    }

    fun clear() {
        check(preferences.edit().remove(KEY).commit()) {
            "Не удалось очистить словарь помощника."
        }
    }

    private fun save(aliases: Map<String, String>) {
        val aliasObject = JSONObject()
        aliases.toSortedMap().forEach { (alias, category) -> aliasObject.put(alias, category) }
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("aliases", aliasObject)
        check(preferences.edit().putString(KEY, json.toString()).commit()) {
            "Не удалось сохранить словарь помощника."
        }
    }
}
