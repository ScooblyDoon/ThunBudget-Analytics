package ru.scooblydoon.thunbudget

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BudgetCategory(val name: String, val percent: Double)
data class Expense(val id: Long, val title: String, val category: String, val amount: Double, val date: String)

object XlsxExporter {
    fun write(context: Context, uri: Uri, expenses: List<Expense>, categories: List<BudgetCategory>, income: Double, year: Int) {
        val sheets = listOf(
            "Операции" to operations(expenses),
            "Периоды 14 дней" to periods(expenses, categories, income, year),
            "Месяцы" to months(expenses, categories, income, year),
            "Год по категориям" to annual(expenses, categories, income, year),
            "Рекомендации" to recommendations(expenses, categories, income)
        )
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                entry(zip, "[Content_Types].xml", contentTypes(sheets.size))
                entry(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
                entry(zip, "xl/workbook.xml", workbook(sheets))
                entry(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
                sheets.forEachIndexed { index, pair -> entry(zip, "xl/worksheets/sheet${index + 1}.xml", sheet(pair.second)) }
            }
        }
    }

    private fun operations(expenses: List<Expense>) = listOf(
        listOf("Дата", "Описание", "Категория", "Сумма, ₽")
    ) + expenses.sortedBy { it.date }.map { listOf(it.date, it.title, it.category, it.amount) }

    private fun periods(expenses: List<Expense>, cats: List<BudgetCategory>, income: Double, year: Int): List<List<Any>> {
        val rows = mutableListOf<List<Any>>(listOf("Начало", "Окончание", "Категория", "План, ₽", "Факт, ₽", "Не выбрано, ₽", "Превышение, ₽"))
        var start = LocalDate.of(year - 1, 12, 24)
        val yearEnd = LocalDate.of(year, 12, 31)
        while (start <= yearEnd) {
            val end = if (start.dayOfMonth == 24) start.plusMonths(1).withDayOfMonth(8) else start.withDayOfMonth(23)
            cats.forEach { c ->
                val fact = expenses.filter { it.category == c.name && it.date >= start.toString() && it.date <= end.toString() }.sumOf { it.amount }
                val plan = planned(income, c.percent, start, end)
                rows.add(listOf(start.toString(), end.toString(), c.name, plan, fact, (plan - fact).coerceAtLeast(0.0), (fact - plan).coerceAtLeast(0.0)))
            }
            start = end.plusDays(1)
        }
        return rows
    }

    private fun months(expenses: List<Expense>, cats: List<BudgetCategory>, income: Double, year: Int): List<List<Any>> {
        val rows = mutableListOf<List<Any>>(listOf("Месяц", "Категория", "План, ₽", "Факт, ₽", "Не выбрано, ₽", "Превышение, ₽"))
        for (m in 1..12) {
            val ym = YearMonth.of(year, m)
            val from = ym.atDay(1); val to = ym.atEndOfMonth()
            cats.forEach { c ->
                val fact = expenses.filter { it.category == c.name && it.date >= from.toString() && it.date <= to.toString() }.sumOf { it.amount }
                val plan = income * c.percent / 100.0
                rows.add(listOf(ym.toString(), c.name, plan, fact, (plan - fact).coerceAtLeast(0.0), (fact - plan).coerceAtLeast(0.0)))
            }
        }
        return rows
    }

    private fun annual(expenses: List<Expense>, cats: List<BudgetCategory>, income: Double, year: Int): List<List<Any>> {
        val from = LocalDate.of(year, 1, 1).toString(); val to = LocalDate.of(year, 12, 31).toString()
        val rows = mutableListOf<List<Any>>(listOf("Категория", "Доля бюджета, %", "План за год, ₽", "Факт за год, ₽", "Не выбрано, ₽", "Превышение, ₽"))
        cats.forEach { c ->
            val fact = expenses.filter { it.category == c.name && it.date >= from && it.date <= to }.sumOf { it.amount }
            val plan = income * 12 * c.percent / 100.0
            rows.add(listOf(c.name, c.percent, plan, fact, (plan-fact).coerceAtLeast(0.0), (fact-plan).coerceAtLeast(0.0)))
        }
        return rows
    }

    private fun recommendations(expenses: List<Expense>, cats: List<BudgetCategory>, income: Double): List<List<Any>> {
        val today = LocalDate.now()
        val (from, to) = currentPeriod(today)
        val rows = mutableListOf<List<Any>>(listOf("Категория с превышением", "Сумма превышения, ₽", "Категория-донор", "Доступный остаток, ₽", "Рекомендуемый перенос, ₽"))
        val spent = cats.associate { c -> c.name to expenses.filter { it.category == c.name && it.date >= from.toString() && it.date <= to.toString() }.sumOf { it.amount } }
        val caps = cats.associate { c -> c.name to planned(income, c.percent, from, to) }
        val donors = cats.filter { (caps[it.name] ?: 0.0) > (spent[it.name] ?: 0.0) }.map { it.name to ((caps[it.name] ?: 0.0) - (spent[it.name] ?: 0.0)) }.toMutableList()
        cats.filter { (spent[it.name] ?: 0.0) > (caps[it.name] ?: 0.0) }.forEach { over ->
            var deficit = (spent[over.name] ?: 0.0) - (caps[over.name] ?: 0.0)
            donors.toList().forEach { donor ->
                if (deficit > 0.0 && donor.first != over.name) {
                    val transfer = minOf(deficit, donor.second)
                    if (transfer > 0.0) {
                        rows.add(listOf(over.name, (spent[over.name] ?: 0.0) - (caps[over.name] ?: 0.0), donor.first, donor.second, transfer))
                        deficit -= transfer
                        val index = donors.indexOfFirst { it.first == donor.first }
                        if (index >= 0) donors[index] = donor.first to (donor.second - transfer)
                    }
                }
            }
        }
        return rows
    }

    private fun currentPeriod(date: LocalDate): Pair<LocalDate, LocalDate> = when {
        date.dayOfMonth >= 24 -> date.withDayOfMonth(24) to date.plusMonths(1).withDayOfMonth(8)
        date.dayOfMonth >= 9 -> date.withDayOfMonth(9) to date.withDayOfMonth(23)
        else -> date.minusMonths(1).withDayOfMonth(24) to date.withDayOfMonth(8)
    }

    private fun planned(income: Double, percent: Double, from: LocalDate, to: LocalDate): Double {
        var d = from; var total = 0.0
        while (!d.isAfter(to)) { total += income * percent / 100.0 / d.lengthOfMonth(); d = d.plusDays(1) }
        return total
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun col(n: Int): String { var x=n+1; var out=""; while(x>0){val r=(x-1)%26;out=('A'.code+r).toChar()+out;x=(x-1)/26};return out }

    private fun sheet(rows: List<List<Any>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { ri, row ->
            append("<row r=\"${ri+1}\">")
            row.forEachIndexed { ci, value ->
                val ref = col(ci) + (ri+1)
                if (value is Number) append("<c r=\"$ref\"><v>${value.toDouble()}</v></c>")
                else append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun contentTypes(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..count) append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        append("</Types>")
    }

    private fun workbook(sheets: List<Pair<String, List<List<Any>>>>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s -> append("<sheet name=\"${xml(s.first)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>") }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        append("</Relationships>")
    }
}
