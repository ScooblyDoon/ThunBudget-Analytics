package ru.scooblydoon.thunbudget

import java.time.LocalDate
import java.util.Locale

internal fun parseVoiceDate(phrase: String, today: LocalDate): LocalDate? {
    val normalized = phrase.lowercase(Locale("ru", "RU")).replace('ё', 'е')
    if (Regex("""\b(сегодня|за сегодня)\b""").containsMatchIn(normalized)) return today
    if (Regex("""\b(вчера|за вчера)\b""").containsMatchIn(normalized)) return today.minusDays(1)
    if (Regex("""\b(позавчера|за позавчера)\b""").containsMatchIn(normalized)) return today.minusDays(2)

    val numeric = Regex("""(?<!\d)(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2,4}))?(?!\d)""").find(normalized)?.takeIf { it.groupValues[3].isNotBlank() || Regex("""\b(за|от|дата)\b""").containsMatchIn(normalized) }
    if (numeric != null) {
        val day = numeric.groupValues[1].toIntOrNull() ?: return null
        val month = numeric.groupValues[2].toIntOrNull() ?: return null
        val yearText = numeric.groupValues[3]
        val year = when (yearText.length) {
            0 -> today.year
            2 -> 2000 + (yearText.toIntOrNull() ?: return null)
            else -> yearText.toIntOrNull() ?: return null
        }
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    val months = listOf("январ" to 1, "феврал" to 2, "март" to 3, "апрел" to 4,
        "ма" to 5, "июн" to 6, "июл" to 7, "август" to 8, "сентябр" to 9,
        "октябр" to 10, "ноябр" to 11, "декабр" to 12)
    val wordDate = Regex("""\b(\d{1,2})\s+(январ[а-я]*|феврал[а-я]*|март[а-я]*|апрел[а-я]*|ма[йя][а-я]*|июн[а-я]*|июл[а-я]*|август[а-я]*|сентябр[а-я]*|октябр[а-я]*|ноябр[а-я]*|декабр[а-я]*)(?:\s+(\d{4}))?\b""").find(normalized)
    if (wordDate != null) {
        val day = wordDate.groupValues[1].toIntOrNull() ?: return null
        val monthWord = wordDate.groupValues[2]
        val month = months.firstOrNull { monthWord.startsWith(it.first) }?.second ?: return null
        val year = wordDate.groupValues[3].toIntOrNull() ?: today.year
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
    return null
}

private fun editDistance(left: String, right: String): Int {
    if (left == right) return 0
    var previous = IntArray(right.length + 1) { it }
    for (i in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = i + 1
        for (j in right.indices) {
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                previous[j] + if (left[i] == right[j]) 0 else 1)
        }
        previous = current
    }
    return previous[right.length]
}

internal fun findVoiceCategory(phrase: String, categories: List<BudgetCategory>): BudgetCategory? {
    val normalized = phrase.lowercase(Locale("ru", "RU")).replace('ё', 'е')
    categories.sortedByDescending { it.name.length }.firstOrNull {
        normalized.contains(it.name.lowercase(Locale("ru", "RU")).replace('ё', 'е'))
    }?.let { return it }

    val ignored = setOf("добавь", "добавить", "расход", "потратил", "потратила", "купил",
        "купила", "оплатил", "оплатила", "рублей", "рубля", "рубль", "сегодня", "вчера", "позавчера")
    val heard = Regex("""[а-яa-z]{3,}""").findAll(normalized).map { it.value }
        .filterNot { it in ignored }.toList()
    if (heard.isEmpty()) return null

    return categories.mapNotNull { category ->
        val words = Regex("""[а-яa-z]{3,}""").findAll(
            category.name.lowercase(Locale("ru", "RU")).replace('ё', 'е')
        ).map { it.value }.toList()
        if (words.isEmpty()) return@mapNotNull null
        val matched = words.count { target ->
            heard.any { candidate -> editDistance(target, candidate) <= if (target.length >= 7) 2 else 1 }
        }
        val score = matched.toDouble() / words.size
        if (score >= 0.75) category to score else null
    }.maxByOrNull { it.second }?.first
}

internal fun formatDateDigits(input: String): String {
    val digits = input.filter(Char::isDigit).take(8)
    return buildString {
        digits.forEachIndexed { index, char ->
            if (index == 2 || index == 4) append('.')
            append(char)
        }
    }
}

internal fun parseUserDate(input: String): LocalDate? {
    val digits = input.filter(Char::isDigit)
    if (digits.length != 8) return null
    val day = digits.substring(0, 2).toIntOrNull() ?: return null
    val month = digits.substring(2, 4).toIntOrNull() ?: return null
    val year = digits.substring(4, 8).toIntOrNull() ?: return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
