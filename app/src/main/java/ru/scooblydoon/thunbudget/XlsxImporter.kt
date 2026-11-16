package ru.scooblydoon.thunbudget

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

data class LegacyBudgetImport(
    val expenses: List<Expense>,
    val categories: List<BudgetCategory>
)

object XlsxImporter {
    fun read(context: Context, uri: Uri): LegacyBudgetImport {
        val sheets = mutableMapOf<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Не удалось открыть Excel-файл.")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet1.xml" || entry.name == "xl/worksheets/sheet4.xml") {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    sheets[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val operationsXml = sheets["xl/worksheets/sheet1.xml"]
            ?: error("В файле не найден лист «Операции». Выбери Excel-экспорт из старой версии ThunBudget.")
        val operationRows = parseRows(operationsXml)
        require(operationRows.isNotEmpty()) { "Лист операций пуст." }
        val header = operationRows.first()
        require(header.getOrNull(0) == "Дата" &&
            header.getOrNull(1) == "Описание" &&
            header.getOrNull(2) == "Категория" &&
            header.getOrNull(3) == "Сумма, ₽") {
            "Формат листа «Операции» не соответствует экспорту ThunBudget."
        }

        val now = System.currentTimeMillis()
        val expenses = operationRows.drop(1).mapIndexedNotNull { index, row ->
            if (row.all { it.isBlank() }) return@mapIndexedNotNull null
            val date = row.getOrNull(0)?.trim().orEmpty()
            val title = row.getOrNull(1)?.trim().orEmpty()
            val category = row.getOrNull(2)?.trim().orEmpty()
            val amount = row.getOrNull(3)?.trim()?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull()
            require(title.isNotBlank() && category.isNotBlank() && amount != null && amount.isFinite() && amount > 0.0) {
                "В Excel обнаружена операция с пустыми или некорректными полями (строка ${index + 2})."
            }
            require(runCatching { LocalDate.parse(date) }.isSuccess) {
                "В Excel обнаружена некорректная дата операции (строка ${index + 2})."
            }
            Expense(now + index, title, category, amount, date)
        }
        require(expenses.isNotEmpty()) { "На листе «Операции» нет расходов для переноса." }

        val categories = sheets["xl/worksheets/sheet4.xml"]?.let { xml ->
            val rows = parseRows(xml)
            if (rows.isEmpty() || rows.first().getOrNull(0) != "Категория") emptyList()
            else rows.drop(1).mapNotNull { row ->
                val name = row.getOrNull(0)?.trim().orEmpty()
                if (name.isBlank()) null else {
                    val percent = row.getOrNull(1)?.trim()?.replace(',', '.')?.toDoubleOrNull()
                    require(percent != null && percent.isFinite() && percent >= 0.0) {
                        "В Excel найдена категория с некорректной долей бюджета."
                    }
                    BudgetCategory(name, percent)
                }
            }.also { imported ->
                require(imported.map { it.name.lowercase() }.distinct().size == imported.size) {
                    "В Excel найдены повторяющиеся категории."
                }
            }
        } ?: emptyList()

        return LegacyBudgetImport(expenses.sortedByDescending { it.date }, categories)
    }

    private fun parseRows(xml: ByteArray): List<List<String>> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val rows = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var column = -1
        var cellValue = StringBuilder()
        var maxColumn = -1

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        row = mutableMapOf()
                        maxColumn = -1
                    }
                    "c" -> {
                        val reference = parser.getAttributeValue(null, "r").orEmpty()
                        column = columnIndex(reference)
                        cellValue = StringBuilder()
                    }
                    "t", "v" -> if (column >= 0) {
                        cellValue.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        if (column >= 0) {
                            row[column] = cellValue.toString()
                            maxColumn = maxOf(maxColumn, column)
                        }
                        column = -1
                    }
                    "row" -> {
                        if (maxColumn >= 0) rows.add((0..maxColumn).map { row[it].orEmpty() })
                    }
                }
            }
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return -1
        var result = 0
        letters.forEach { char -> result = result * 26 + (char - 'A' + 1) }
        return result - 1
    }
}
