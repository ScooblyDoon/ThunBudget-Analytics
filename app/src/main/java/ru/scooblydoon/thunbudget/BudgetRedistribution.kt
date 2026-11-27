package ru.scooblydoon.thunbudget

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * All monetary inputs are amounts for one exact reporting period, in rubles.
 * Variable forecasts cover only the remaining days of the period.
 */
data class RedistributionCategoryInput(
    val name: String,
    val plannedAmount: Double,
    val actualToDate: Double,
    val knownFutureExpenses: Double = 0.0,
    val unpaidRecurringPayments: Double = 0.0,
    val variableForecast: Double = 0.0,
    val variableForecastUpper: Double = variableForecast,
    val hasEvidence: Boolean = true,
    val protectedFromRedistribution: Boolean = false
)

data class BudgetRedistributionDeficit(
    val category: String,
    val actualSpend: Double,
    val projectedSpend: Double,
    val plannedAmount: Double,
    val amount: Double
)

data class BudgetRedistributionTransfer(
    val fromCategory: String,
    val toCategory: String,
    val amount: Double,
    val donorFreePercent: Double
)

data class BudgetRedistributionProposal(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val fingerprint: String,
    val deficits: List<BudgetRedistributionDeficit>,
    val transfers: List<BudgetRedistributionTransfer>,
    val uncoveredByCategory: Map<String, Double>
) {
    val totalTransferred: Double get() = transfers.sumOf { it.amount }
    val totalUncovered: Double get() = uncoveredByCategory.values.sumOf { it }
}

data class AppliedBudgetRedistributionTransfer(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val fromCategory: String,
    val toCategory: String,
    val amount: Double,
    val proposalFingerprint: String,
    val appliedAt: String
)

object BudgetRedistributionCalculator {
    private const val DEFAULT_SAFETY_BUFFER_PERCENT = 2.0
    private const val MINIMUM_DONOR_FREE_PERCENT = 5.0
    private const val EPSILON_CENTS = 0L

    private data class Donor(
        val name: String,
        val availableCents: Long,
        val freePercent: Double,
        val reserve: Boolean
    )

    /**
     * Expected overspending uses the central forecast. Donor availability uses
     * the conservative upper forecast and reserves one global 2% buffer across
     * the combined ordinary-donor pool. Reserve money is considered first and
     * does not consume that ordinary-donor buffer.
     */
    fun calculate(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        periodIncome: Double,
        categories: List<RedistributionCategoryInput>
    ): BudgetRedistributionProposal {
        require(!periodEnd.isBefore(periodStart)) { "Некорректные даты отчётного периода." }
        require(periodIncome.isFinite()) { "Доход периода должен быть конечным числом." }
        require(categories.map { normalized(it.name) }.distinct().size == categories.size) {
            "Названия категорий должны быть уникальными."
        }
        categories.forEach(::validateInput)

        val inputFingerprint = fingerprint(periodStart, periodEnd, periodIncome, categories)
        val usableIncome = periodIncome.coerceAtLeast(0.0)
        val incomeAllowsTransfers = periodIncome > 0.0

        fun cents(value: Double): Long = toCents(value)
        fun money(value: Long): Double = value / 100.0

        val budgets = categories.associate { it.name to cents(it.plannedAmount) }
        val projectedSpend = categories.associate { input ->
            input.name to sumCents(
                input.actualToDate,
                input.knownFutureExpenses,
                input.unpaidRecurringPayments,
                input.variableForecast
            )
        }
        val conservativeSpend = categories.associate { input ->
            input.name to sumCents(
                input.actualToDate,
                input.knownFutureExpenses,
                input.unpaidRecurringPayments,
                maxOf(input.variableForecast, input.variableForecastUpper)
            )
        }

        val deficits = categories.mapNotNull { input ->
            if (isReserve(input.name)) return@mapNotNull null
            val planned = budgets.getValue(input.name)
            val projected = projectedSpend.getValue(input.name)
            val deficit = (projected - planned).coerceAtLeast(0L)
            if (deficit <= EPSILON_CENTS) null else {
                BudgetRedistributionDeficit(
                    category = input.name,
                    actualSpend = input.actualToDate,
                    projectedSpend = money(projected),
                    plannedAmount = money(planned),
                    amount = money(deficit)
                )
            }
        }.sortedWith(
            compareByDescending<BudgetRedistributionDeficit> { cents(it.amount) }
                .thenBy { normalized(it.category) }
        )

        val donors = if (!incomeAllowsTransfers) {
            emptyList()
        } else {
            categories.mapNotNull { input ->
                val reserve = isReserve(input.name)
                val alwaysProtectedSavings = normalized(input.name).contains("накоп")
                val protectedByDefault = BudgetRedistributionProtectionDefaults
                    .isProtectedByDefault(input.name)
                val protected = input.protectedFromRedistribution ||
                    alwaysProtectedSavings || protectedByDefault
                val planned = budgets.getValue(input.name)
                val spent = conservativeSpend.getValue(input.name)
                val available = (planned - spent).coerceAtLeast(0L)
                val freePercent = if (planned > 0L) available.toDouble() * 100.0 / planned else 0.0
                val evidenceEligible = reserve || input.hasEvidence
                val freeEligible = reserve || freePercent > MINIMUM_DONOR_FREE_PERCENT
                if (protected || available <= EPSILON_CENTS || !evidenceEligible || !freeEligible) {
                    null
                } else {
                    Donor(input.name, available, freePercent, reserve)
                }
            }.sortedWith(
                compareByDescending<Donor> { it.reserve }
                    .thenByDescending { donorTier(it.freePercent) }
                    .thenByDescending { it.freePercent }
                    .thenByDescending { it.availableCents }
                    .thenBy { normalized(it.name) }
            )
        }

        // With a defined zero/negative period balance there is no budget source.
        // Do not let a positive category value accidentally create donor funds.
        val ordinaryBufferCents = if (incomeAllowsTransfers) {
            toCents(usableIncome * DEFAULT_SAFETY_BUFFER_PERCENT / 100.0)
        } else 0L
        var ordinaryPoolRemaining = (
            donors.filterNot { it.reserve }.sumOf { it.availableCents } - ordinaryBufferCents
        ).coerceAtLeast(0L)
        val donorRemaining = donors.associate { it.name to it.availableCents }.toMutableMap()
        val transfers = mutableListOf<BudgetRedistributionTransfer>()
        val uncovered = linkedMapOf<String, Double>()

        deficits.forEach { deficit ->
            var need = cents(deficit.amount)
            donors.forEach { donor ->
                if (need <= EPSILON_CENTS || donor.name.equals(deficit.category, ignoreCase = true)) {
                    return@forEach
                }
                val donorRemainingCents = donorRemaining[donor.name] ?: 0L
                if (donorRemainingCents <= EPSILON_CENTS) return@forEach
                val available = if (donor.reserve) {
                    donorRemainingCents
                } else {
                    minOf(donorRemainingCents, ordinaryPoolRemaining)
                }
                if (available <= EPSILON_CENTS) return@forEach
                val moved = minOf(need, available)
                transfers += BudgetRedistributionTransfer(
                    fromCategory = donor.name,
                    toCategory = deficit.category,
                    amount = money(moved),
                    donorFreePercent = donor.freePercent
                )
                donorRemaining[donor.name] = donorRemainingCents - moved
                if (!donor.reserve) ordinaryPoolRemaining -= moved
                need -= moved
            }
            if (need > EPSILON_CENTS) uncovered[deficit.category] = money(need)
        }

        return BudgetRedistributionProposal(
            periodStart = periodStart,
            periodEnd = periodEnd,
            fingerprint = inputFingerprint,
            deficits = deficits,
            transfers = transfers,
            uncoveredByCategory = uncovered
        )
    }

    fun netAdjustments(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        appliedTransfers: List<AppliedBudgetRedistributionTransfer>
    ): Map<String, Double> {
        val adjustments = linkedMapOf<String, Long>()
        appliedTransfers
            .filter { it.periodStart == periodStart && it.periodEnd == periodEnd }
            .forEach { transfer ->
                validateTransfer(transfer)
                val amount = toCents(transfer.amount)
                adjustments[transfer.fromCategory] =
                    (adjustments[transfer.fromCategory] ?: 0L) - amount
                adjustments[transfer.toCategory] =
                    (adjustments[transfer.toCategory] ?: 0L) + amount
            }
        return adjustments.mapValues { (_, amount) -> amount / 100.0 }
            .filterValues { it != 0.0 }
    }

    fun appendAppliedTransfers(
        proposal: BudgetRedistributionProposal,
        existingLedger: List<AppliedBudgetRedistributionTransfer>,
        appliedAt: String
    ): List<AppliedBudgetRedistributionTransfer> {
        require(proposal.transfers.isNotEmpty()) { "В предложении нет переводов для применения." }
        require(proposal.fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Некорректный отпечаток предложения." }
        require(existingLedger.none { it.proposalFingerprint == proposal.fingerprint }) {
            "Это предложение уже было применено."
        }
        require(existingLedger.all { it.proposalFingerprint != proposal.fingerprint }) {
            "В журнале уже есть такое предложение."
        }
        val added = proposal.transfers.map { transfer ->
            AppliedBudgetRedistributionTransfer(
                periodStart = proposal.periodStart,
                periodEnd = proposal.periodEnd,
                fromCategory = transfer.fromCategory,
                toCategory = transfer.toCategory,
                amount = transfer.amount,
                proposalFingerprint = proposal.fingerprint,
                appliedAt = appliedAt
            ).also(::validateTransfer)
        }
        return existingLedger + added
    }

    private fun validateInput(input: RedistributionCategoryInput) {
        require(input.name.isNotBlank()) { "Название категории не может быть пустым." }
        val values = listOf(
            input.plannedAmount,
            input.actualToDate,
            input.knownFutureExpenses,
            input.unpaidRecurringPayments,
            input.variableForecast,
            input.variableForecastUpper
        )
        require(values.all { it.isFinite() && it >= 0.0 }) {
            "Суммы категории должны быть конечными и неотрицательными."
        }
    }

    private fun validateTransfer(transfer: AppliedBudgetRedistributionTransfer) {
        require(!transfer.periodEnd.isBefore(transfer.periodStart))
        require(transfer.fromCategory.isNotBlank() && transfer.toCategory.isNotBlank())
        require(!transfer.fromCategory.equals(transfer.toCategory, ignoreCase = true))
        require(transfer.amount.isFinite() && transfer.amount > 0.0)
        require(transfer.proposalFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(transfer.appliedAt.isNotBlank())
    }

    private fun fingerprint(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        periodIncome: Double,
        categories: List<RedistributionCategoryInput>
    ): String {
        val canonical = buildString {
            append(periodStart).append('|').append(periodEnd).append('|')
            append(decimal(periodIncome)).append('\n')
            categories.sortedBy { normalized(it.name) }.forEach { input ->
                append(normalized(input.name)).append('|')
                append(decimal(input.plannedAmount)).append('|')
                append(decimal(input.actualToDate)).append('|')
                append(decimal(input.knownFutureExpenses)).append('|')
                append(decimal(input.unpaidRecurringPayments)).append('|')
                append(decimal(input.variableForecast)).append('|')
                append(decimal(input.variableForecastUpper)).append('|')
                append(input.hasEvidence).append('|')
                append(input.protectedFromRedistribution).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun decimal(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun sumCents(vararg amounts: Double): Long =
        amounts.sumOf(::toCents)

    private fun toCents(value: Double): Long =
        BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2).longValueExact()

    private fun normalized(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace('ё', 'е')

    private fun isReserve(name: String): Boolean =
        normalized(name) == normalized(BudgetAllocationCalculator.RESERVE_CATEGORY) ||
            normalized(name) == normalized("Непредвиденные расходы")

    private fun donorTier(freePercent: Double): Int = when {
        freePercent >= 20.0 -> 3
        freePercent >= 15.0 -> 2
        freePercent >= 10.0 -> 1
        else -> 0
    }
}
