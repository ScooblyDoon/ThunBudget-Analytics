package ru.scooblydoon.thunbudget

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/**
 * Stable, versioned text representation of budget-only category transfers.
 * It avoids Android JSON dependencies so validation and round-trip behavior
 * can be exercised in local JVM unit tests.
 */
object BudgetRedistributionLedgerCodec {
    private const val HEADER = "ThunBudget-BudgetRedistribution-Ledger-v1"
    private const val MAX_ROWS = 20_000

    fun encode(rows: List<AppliedBudgetRedistributionTransfer>): String {
        validate(rows)
        val encodedRows = rows.sortedWith(
            compareBy<AppliedBudgetRedistributionTransfer> { it.periodStart }
                .thenBy { it.periodEnd }
                .thenBy { it.proposalFingerprint }
                .thenBy { it.fromCategory }
                .thenBy { it.toCategory }
                .thenBy { it.amount }
        ).joinToString("\n") { row ->
            listOf(
                row.periodStart.toString(),
                row.periodEnd.toString(),
                encodeField(row.fromCategory),
                encodeField(row.toCategory),
                BigDecimal.valueOf(row.amount).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                row.proposalFingerprint,
                encodeField(row.appliedAt)
            ).joinToString("\t")
        }
        return if (encodedRows.isEmpty()) "$HEADER\n" else "$HEADER\n$encodedRows\n"
    }

    fun decode(raw: String): List<AppliedBudgetRedistributionTransfer> {
        require(raw.startsWith("$HEADER\n")) { "Неизвестная версия журнала перераспределения." }
        val body = raw.removePrefix("$HEADER\n")
        if (body.isBlank()) return emptyList()
        val rows = body.lineSequence().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 7) { "Некорректная строка журнала перераспределения." }
            AppliedBudgetRedistributionTransfer(
                periodStart = LocalDate.parse(fields[0]),
                periodEnd = LocalDate.parse(fields[1]),
                fromCategory = decodeField(fields[2]),
                toCategory = decodeField(fields[3]),
                amount = fields[4].toDouble(),
                proposalFingerprint = fields[5],
                appliedAt = decodeField(fields[6])
            )
        }.toList()
        validate(rows)
        return rows
    }

    fun validate(rows: List<AppliedBudgetRedistributionTransfer>) {
        require(rows.size <= MAX_ROWS) { "Журнал перераспределения слишком велик." }
        rows.forEach { row ->
            require(!row.periodEnd.isBefore(row.periodStart)) { "Некорректные даты перевода." }
            require(row.fromCategory.isNotBlank() && row.toCategory.isNotBlank())
            require(!row.fromCategory.equals(row.toCategory, ignoreCase = true))
            require(row.amount.isFinite() && row.amount > 0.0)
            require(BigDecimal.valueOf(row.amount).stripTrailingZeros().scale() <= 2) {
                "Сумма перевода должна быть указана с точностью до копеек."
            }
            require(row.proposalFingerprint.matches(Regex("[0-9a-f]{64}")))
            Instant.parse(row.appliedAt)
        }
        val duplicateRows = rows.map {
            listOf(
                it.periodStart.toString(), it.periodEnd.toString(),
                it.proposalFingerprint, it.fromCategory.lowercase(), it.toCategory.lowercase(),
                BigDecimal.valueOf(it.amount).setScale(2, RoundingMode.HALF_UP).toPlainString()
            ).joinToString("|")
        }
        require(duplicateRows.distinct().size == duplicateRows.size) {
            "В журнале повторяется один и тот же перевод."
        }
        val fingerprints = rows.groupBy { it.proposalFingerprint }
        require(fingerprints.values.all { group ->
            group.map { it.periodStart to it.periodEnd }.distinct().size == 1
        }) { "Один отпечаток предложения не может относиться к разным периодам." }
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeField(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
