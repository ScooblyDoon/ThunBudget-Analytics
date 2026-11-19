package ru.scooblydoon.thunbudget

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipInputStream

/**
 * Reads expense rows from the first worksheet of an .xlsx file.
 * Expected columns: Дата, Описание, Категория, Сумма (the same layout as XlsxExporter).
 * Supports inline strings and shared strings used by standard Excel workbooks.
 */
object XlsxImporter {
    fun readExpenses(openStream: (Uri) -> java.io.InputStream?, uri: Uri): List<Expense> {
        val entries = mutableMapOf<String, ByteArray>()
        openStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name == "xl/worksheets/sheet1.xml" || entry.name == "xl/sharedStrings.xml")) {
                        entries[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Не удалось открыть Excel-файл.")
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: error("В книге Excel не найден первый лист.")
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val rows = parseRows(sheet, shared)
        require(rows.isNotEmpty()) { "Лист Excel пуст." }
        val header = rows.first().map { it.trim().lowercase() }
        fun column(vararg names: String): Int = header.indexOfFirst { value -> names.any { value == it } }
        val dateCol = column("дата", "date")
        val titleCol = column("описание", "название", "операция", "description", "title")
        val categoryCol = column("категория", "category")
        val amountCol = column("сумма, ₽", "сумма", "amount", "сумма руб.")
        require(dateCol >= 0 && titleCol >= 0 && categoryCol >= 0 && amountCol >= 0) {
            "Не найдены обязательные столбцы: Дата, Описание, Категория, Сумма."
        }
        return rows.drop(1).mapNotNull { row ->
            val rawDate = row.getOrNull(dateCol).orEmpty().trim()
            val title = row.getOrNull(titleCol).orEmpty().trim()
            val category = row.getOrNull(categoryCol).orEmpty().trim()
            val rawAmount = row.getOrNull(amountCol).orEmpty().trim()
            if (rawDate.isBlank() && title.isBlank() && rawAmount.isBlank()) return@mapNotNull null
            require(title.isNotBlank() && category.isNotBlank()) { "В одной из строк не заполнены описание или категория." }
            val date = parseDate(rawDate) ?: error("Некорректная дата в строке: $rawDate")
            val amount = rawAmount.replace("₽", "").replace(" ", "").replace("\u00A0", "").replace(",", ".").toDoubleOrNull()
                ?: error("Некорректная сумма в операции «$title».")
            require(amount.isFinite() && amount > 0.0) { "Сумма операции «$title» должна быть больше нуля." }
            Expense(id = System.currentTimeMillis() + rows.indexOf(row), title = title, category = category, amount = amount, date = date)
        }
    }

    private fun parseDate(value: String): String? = runCatching {
        val text = value.trim()
        if (text.matches(Regex("""\\d{4}-\\d{2}-\\d{2}"""))) LocalDate.parse(text).toString()
        else {
            val serial = text.toDouble()
            require(serial >= 1.0 && serial <= 2_958_465.0)
            LocalDate.of(1899, 12, 30).plusDays(serial.toLong()).toString()
        }
    }.getOrNull()

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        val values = mutableListOf<String>()
        var current = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "si") current = StringBuilder() else if (parser.name == "t") inText = true
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> values.add(current.toString())
                }
            }
            event = parser.next()
        }
        return values
    }

    private fun parseRows(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var row: MutableMap<Int, String>? = null
        var column = -1
        var cellType = ""
        var value = StringBuilder()
        var inValue = false
        var inInlineText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        val reference = parser.getAttributeValue(null, "r").orEmpty()
                        column = reference.takeWhile { it.isLetter() }.fold(0) { acc, char -> acc * 26 + (char.uppercaseChar() - 'A' + 1) } - 1
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        value = StringBuilder()
                    }
                    "v" -> inValue = true
                    "t" -> if (cellType == "inlineStr") inInlineText = true
                }
                XmlPullParser.TEXT -> if (inValue || inInlineText) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "t" -> inInlineText = false
                    "c" -> {
                        val raw = value.toString()
                        val resolved = if (cellType == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                        if (column >= 0) row?.set(column, resolved)
                    }
                    "row" -> {
                        val current = row.orEmpty()
                        rows.add((0..(current.keys.maxOrNull() ?: -1)).map { current[it].orEmpty() })
                        row = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }
}
