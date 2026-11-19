package ru.scooblydoon.thunbudget

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BudgetCategory(val name: String, val percent: Double)
data class Expense(val id: Long, val title: String, val category: String, val amount: Double, val date: String)

object XlsxExporter {
    fun write(
        context: Context,
        uri: Uri,
        expenses: List<Expense>,
        categories: List<BudgetCategory>,
        income: Double,
        year: Int,
        periodStart: String,
        periodEnd: String,
        periodIncome: Double,
        monthlyIncomes: Map<String, Double>
    ) {
        val from = LocalDate.parse(periodStart)
        val to = LocalDate.parse(periodEnd)
        require(!to.isBefore(from)) { "Некорректные даты отчётного периода." }
        val sheets = listOf(
            "Операции" to operations(expenses),
            "Отчётный период" to periods(expenses, categories, income, from, to, periodIncome),
            "Месяцы" to months(expenses, categories, income, year, monthlyIncomes),
            "Год по категориям" to annual(expenses, categories, income, year),
            "Рекомендации" to recommendations(expenses, categories, income, from, to)
        )
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                entry(zip, "[Content_Types].xml", contentTypes(sheets.size))
                entry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                entry(zip, "xl/workbook.xml", workbook(sheets))
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
                entry(zip, "xl/styles.xml", styles())
                sheets.forEachIndexed { index, pair ->
                    entry(zip, "xl/worksheets/sheet${index + 1}.xml", sheet(pair.second))
                }
            }
        } ?: error("Не удалось открыть файл Excel для записи.")
    }

    private fun operations(expenses: List<Expense>) = listOf(
        listOf("Дата", "Описание", "Категория", "Сумма, ₽")
    ) + expenses.sortedBy { it.date }.map { listOf(it.date, it.title, it.category, it.amount) }

    internal fun periods(
        expenses: List<Expense>,
        cats: List<BudgetCategory>,
        income: Double,
        start: LocalDate,
        end: LocalDate,
        periodIncome: Double
    ): List<List<Any>> {
        require(!end.isBefore(start))
        val rows = mutableListOf<List<Any>>(
            listOf("Начало", "Окончание", "Категория", "План, ₽", "Факт, ₽", "Не выбрано, ₽", "Превышение, ₽")
        )
        cats.forEach { category ->
            val fact = expenses.asSequence()
                .filter { it.category == category.name && it.date >= start.toString() && it.date <= end.toString() }
                .sumOf { it.amount }
            val plan = if (periodIncome > 0.0) periodIncome * category.percent / 100.0
                else planned(income, category.percent, start, end)
            rows.add(
                listOf(
                    start.toString(), end.toString(), category.name, plan, fact,
                    (plan - fact).coerceAtLeast(0.0), (fact - plan).coerceAtLeast(0.0)
                )
            )
        }
        return rows
    }

    internal fun months(expenses: List<Expense>, cats: List<BudgetCategory>, income: Double, year: Int, monthlyIncomes: Map<String, Double>): List<List<Any>> {
        val rows = mutableListOf<List<Any>>(
            listOf("Месяц", "Категория", "План, ₽", "Факт, ₽", "Не выбрано, ₽", "Превышение, ₽")
        )
        for (month in 1..12) {
            val ym = YearMonth.of(year, month)
            val from = ym.atDay(1)
            val to = ym.atEndOfMonth()
            val monthIncome = monthlyIncomes[ym.toString()]?.takeIf { it.isFinite() && it > 0.0 } ?: income
            cats.forEach { category ->
                val fact = expenses.asSequence()
                    .filter { it.category == category.name && it.date >= from.toString() && it.date <= to.toString() }
                    .sumOf { it.amount }
                val plan = monthIncome * category.percent / 100.0
                rows.add(
                    listOf(
                        ym.toString(), category.name, plan, fact,
                        (plan - fact).coerceAtLeast(0.0), (fact - plan).coerceAtLeast(0.0)
                    )
                )
            }
        }
        return rows
    }

    private fun annual(expenses: List<Expense>, cats: List<BudgetCategory>, income: Double, year: Int): List<List<Any>> {
        val from = LocalDate.of(year, 1, 1)
        val to = minOf(LocalDate.now(), LocalDate.of(year, 12, 31))
        val rows = mutableListOf<List<Any>>(
            listOf("Категория", "Доля бюджета, %", "План за год, ₽", "Факт за год, ₽", "Не выбрано, ₽", "Превышение, ₽")
        )
        cats.forEach { category ->
            val fact = expenses.asSequence()
                .filter { it.category == category.name && it.date >= from.toString() && it.date <= to.toString() }
                .sumOf { it.amount }
            val plan = if (to.isBefore(from)) 0.0 else planned(income, category.percent, from, to)
            rows.add(
                listOf(
                    category.name, category.percent, plan, fact,
                    (plan - fact).coerceAtLeast(0.0), (fact - plan).coerceAtLeast(0.0)
                )
            )
        }
        return rows
    }

    private fun recommendations(
        expenses: List<Expense>,
        cats: List<BudgetCategory>,
        income: Double,
        from: LocalDate,
        to: LocalDate,
    ): List<List<Any>> {
        val rows = mutableListOf<List<Any>>(
            listOf("Категория с превышением", "Сумма превышения, ₽", "Категория-донор", "Доступный остаток, ₽", "Рекомендуемый перенос, ₽")
        )
        val spent = cats.associate { category ->
            category.name to expenses.asSequence()
                .filter { it.category == category.name && it.date >= from.toString() && it.date <= to.toString() }
                .sumOf { it.amount }
        }
        val caps = cats.associate { category ->
            category.name to planned(income, category.percent, from, to)
        }
        val donors = cats.filter { (caps[it.name] ?: 0.0) > (spent[it.name] ?: 0.0) }
            .map { it.name to ((caps[it.name] ?: 0.0) - (spent[it.name] ?: 0.0)) }.toMutableList()
        cats.filter { (spent[it.name] ?: 0.0) > (caps[it.name] ?: 0.0) }.forEach { over ->
            var deficit = (spent[over.name] ?: 0.0) - (caps[over.name] ?: 0.0)
            donors.toList().forEach { donor ->
                if (deficit > 0.0 && donor.first != over.name) {
                    val transfer = minOf(deficit, donor.second)
                    if (transfer > 0.0) {
                        rows.add(
                            listOf(
                                over.name, (spent[over.name] ?: 0.0) - (caps[over.name] ?: 0.0),
                                donor.first, donor.second, transfer
                            )
                        )
                        deficit -= transfer
                        val index = donors.indexOfFirst { it.first == donor.first }
                        if (index >= 0) donors[index] = donor.first to (donor.second - transfer)
                    }
                }
            }
        }
        return rows
    }

    internal fun planned(income: Double, percent: Double, from: LocalDate, to: LocalDate): Double {
        if (to.isBefore(from)) return 0.0
        var day = from
        var total = 0.0
        while (!day.isAfter(to)) {
            total += income * percent / 100.0 / day.lengthOfMonth()
            day = day.plusDays(1)
        }
        return total
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun xml(value: String) = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun col(number: Int): String {
        var value = number + 1
        var result = ""
        while (value > 0) {
            val remainder = (value - 1) % 26
            result = ('A'.code + remainder).toChar() + result
            value = (value - 1) / 26
        }
        return result
    }

    private fun sheet(rows: List<List<Any>>): String = buildString {
        val columnCount = rows.maxOfOrNull { it.size } ?: 1
        val lastColumn = col(columnCount - 1)
        val widths = (0 until columnCount).map { column ->
            val longest = rows.maxOfOrNull { it.getOrNull(column)?.toString()?.length ?: 0 } ?: 10
            (longest + 2).coerceIn(10, 42)
        }
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><dimension ref="A1:${lastColumn}${rows.size}"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A2" sqref="A2"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="18"/><cols>""")
        widths.forEachIndexed { index, width ->
            append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"${width}.0\" customWidth=\"1\"/>")
        }
        append("</cols><sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 1
            append("<row r=\"$rowNumber\"")
            if (rowIndex == 0) append(" ht=\"30\" customHeight=\"1\"")
            append(">")
            row.forEachIndexed { columnIndex, value ->
                val reference = col(columnIndex) + rowNumber
                val header = rows.firstOrNull()?.getOrNull(columnIndex)?.toString().orEmpty()
                val style = when {
                    rowIndex == 0 -> 1
                    value is Number && header.contains("%") -> 3
                    value is Number && header.contains("₽") -> 2
                    value is String && header in setOf("Дата", "Начало", "Окончание") -> 4
                    else -> 0
                }
                if (value is Number) {
                    append("<c r=\"$reference\" s=\"$style\"><v>${value.toDouble()}</v></c>")
                } else {
                    val dateSerial = if (header in setOf("Дата", "Начало", "Окончание")) {
                        runCatching {
                            ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.parse(value.toString()))
                        }.getOrNull()
                    } else null
                    if (dateSerial != null) {
                        append("<c r=\"$reference\" s=\"4\"><v>$dateSerial</v></c>")
                    } else {
                        append("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData><autoFilter ref=\"A1:$lastColumn${rows.size}\"/><pageMargins left=\"0.25\" right=\"0.25\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/><pageSetup paperSize=\"9\" orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>")
    }

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00&quot; ₽&quot;"/><numFmt numFmtId="165" formatCode="0.00&quot;%&quot;"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF5145A8"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left/><right/><top/><bottom style="thin"><color rgb="FFD7D3EA"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

    private fun contentTypes(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (index in 1..count) append("<Override PartName=\"/xl/worksheets/sheet$index.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        append("</Types>")
    }

    private fun workbook(sheets: List<Pair<String, List<List<Any>>>>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets>""")
        sheets.forEachIndexed { index, sheet ->
            append("<sheet name=\"${xml(sheet.first)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        append("</sheets><definedNames>")
        sheets.forEachIndexed { index, sheet ->
            val safeName = sheet.first.replace("'", "''")
            append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"$index\">'$safeName'!$1:$1</definedName>")
        }
        append("</definedNames></workbook>")
    }

    private fun workbookRels(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (index in 1..count) {
            append("<Relationship Id=\"rId$index\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$index.xml\"/>")
        }
        append("<Relationship Id=\"rId${count + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
    }
}
