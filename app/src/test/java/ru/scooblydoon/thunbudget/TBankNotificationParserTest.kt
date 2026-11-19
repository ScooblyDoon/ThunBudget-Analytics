package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TBankNotificationParserTest {
    private val timestamp = LocalDate.of(2026, 11, 19)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun parsesPurchaseAmountAndMerchant() {
        val result = TBankNotificationParser.parse(
            title = "Т-Банк",
            text = "Покупка в Пятёрочке 1 250 ₽",
            bigText = null,
            postTime = timestamp
        )
        assertEquals(BankOperationType.EXPENSE, result?.type)
        assertEquals(1250.0, result?.amount ?: 0.0, 0.001)
        assertEquals("в Пятёрочке", result?.title)
        assertEquals("2026-11-19", result?.date)
    }

    @Test
    fun parsesIncomingTransfer() {
        val result = TBankNotificationParser.parse(
            title = "Т-Банк",
            text = "Поступление 54 000 ₽",
            bigText = null,
            postTime = timestamp
        )
        assertEquals(BankOperationType.INCOME, result?.type)
        assertEquals(54000.0, result?.amount ?: 0.0, 0.001)
    }

    @Test
    fun acceptsSameAmountRepeatedInCollapsedAndExpandedText() {
        val result = TBankNotificationParser.parse(
            title = "Т-Банк",
            text = "Покупка 1 250 ₽",
            bigText = "Покупка в магазине 1 250 ₽",
            postTime = timestamp
        )
        assertEquals(BankOperationType.EXPENSE, result?.type)
        assertEquals(1250.0, result?.amount ?: 0.0, 0.001)
    }

    @Test
    fun ignoresNotificationsWithSeveralAmounts() {
        val result = TBankNotificationParser.parse(
            title = "Т-Банк",
            text = "Покупка 1 250 ₽. Остаток 8 750 ₽",
            bigText = null,
            postTime = timestamp
        )
        assertNull(result)
    }

    @Test
    fun ignoresAmbiguousOrNonTransactionNotifications() {
        assertNull(TBankNotificationParser.parse("Т-Банк", "Ваш баланс 8 750 ₽", null, timestamp))
        assertNull(TBankNotificationParser.parse("Т-Банк", "1 250 ₽", null, timestamp))
    }
}
