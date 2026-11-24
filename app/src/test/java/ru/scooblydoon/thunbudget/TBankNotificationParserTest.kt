package ru.scooblydoon.thunbudget

import org.junit.Assert.*
import org.junit.Test

class TBankNotificationParserTest {
    private fun input(text: String, title: String = "Т-Банк", key: String? = null) =
        TBankNotificationParser.Input(
            packageName = TBankNotificationParser.T_BANK_PACKAGE,
            title = title,
            text = text,
            postTimeMillis = 1_795_000_000_000L,
            notificationKey = key
        )

    @Test fun ignoresOtherPackages() {
        assertNull(TBankNotificationParser.parse(input("Покупка 100 ₽").copy(packageName = "com.other.app")))
    }

    @Test fun parsesPurchaseAndMapsSupermarket() {
        val event = TBankNotificationParser.parse(input("Покупка Магнит 1 250,50 ₽"))!!
        assertEquals(TBankNotificationParser.OperationType.PURCHASE, event.operationType)
        assertEquals(TBankNotificationParser.Flow.EXPENSE, event.flow)
        assertEquals(125050L, event.amountKopecks)
        assertEquals("Продукты", event.category)
    }

    @Test fun mapsGasStationToTransport() {
        val event = TBankNotificationParser.parse(input("Покупка Газпромнефть 2 100 ₽"))!!
        assertEquals("Транспорт", event.category)
    }

    @Test fun mapsCafeAndEntertainment() {
        val event = TBankNotificationParser.parse(input("Оплата Kula Bar 850 ₽"))!!
        assertEquals("Кафе и развлечения", event.category)
    }

    @Test fun mapsPharmacyToHealth() {
        val event = TBankNotificationParser.parse(input("Покупка Аптека 540 ₽"))!!
        assertEquals("Здоровье", event.category)
    }

    @Test fun unknownMerchantExpenseGoesToOther() {
        val event = TBankNotificationParser.parse(input("Покупка Неизвестный магазин 300 ₽"))!!
        assertEquals("Другое", event.category)
        assertEquals(TBankNotificationParser.Flow.EXPENSE, event.flow)
    }

    @Test fun incomingTransferIsIncomeCandidateNotExpense() {
        val event = TBankNotificationParser.parse(input("Поступление от Иван 5 000 ₽"))!!
        assertEquals(TBankNotificationParser.OperationType.TRANSFER_IN, event.operationType)
        assertEquals(TBankNotificationParser.Flow.INCOME, event.flow)
        assertNull(event.category)
    }

    @Test fun cardTopUpIsIncomeCandidate() {
        val event = TBankNotificationParser.parse(input("Пополнение на 1 ₽, счет RUB…", title = "Альфа-Банк"))!!
        assertEquals(TBankNotificationParser.OperationType.TRANSFER_IN, event.operationType)
        assertEquals(TBankNotificationParser.Flow.INCOME, event.flow)
        assertEquals(100L, event.amountKopecks)
        assertNull(event.category)
    }

    @Test fun purchaseAmountIgnoresCardBalanceAndCashbackDetails() {
        val event = TBankNotificationParser.parse(
            input("Покупка на 220,96 ₽, кэшбэк 11 ₽, остаток 5 000 ₽", title = "Магнит")
        )!!
        assertEquals(TBankNotificationParser.OperationType.PURCHASE, event.operationType)
        assertEquals(TBankNotificationParser.Flow.EXPENSE, event.flow)
        assertEquals(22096L, event.amountKopecks)
        assertEquals("Продукты", event.category)
    }

    @Test fun outgoingTransferToAnotherPersonIsOtherExpense() {
        val event = TBankNotificationParser.parse(input("Перевод на 10 ₽, счет RUB...."))!!
        assertEquals(TBankNotificationParser.OperationType.TRANSFER_OUT, event.operationType)
        assertEquals(TBankNotificationParser.Flow.EXPENSE, event.flow)
        assertEquals("Другое", event.category)
    }

    @Test fun ownAccountTransferDoesNotAffectIncomeOrExpense() {
        val event = TBankNotificationParser.parse(input("Перевод между своими счетами 10 000 ₽"))!!
        assertEquals(TBankNotificationParser.Flow.INTERNAL, event.flow)
        assertTrue(event.requiresReview)
    }

    @Test fun refundRequiresLinkToOriginalExpense() {
        val event = TBankNotificationParser.parse(input("Возврат покупки Магнит 500 ₽"))!!
        assertEquals(TBankNotificationParser.OperationType.REFUND, event.operationType)
        assertEquals(TBankNotificationParser.Flow.REVIEW, event.flow)
        assertTrue(event.reviewReasons.contains(TBankNotificationParser.ReviewReason.REFUND_WITHOUT_ORIGINAL))
    }

    @Test fun reversalRequiresReview() {
        val event = TBankNotificationParser.parse(input("Операция отменена 500 ₽"))!!
        assertEquals(TBankNotificationParser.OperationType.REVERSAL, event.operationType)
        assertEquals(TBankNotificationParser.Flow.REVIEW, event.flow)
    }

    @Test fun cashWithdrawalIsOtherNotPurchaseCategory() {
        val event = TBankNotificationParser.parse(input("Снятие наличных 5 000 ₽"))!!
        assertEquals(TBankNotificationParser.OperationType.CASH_WITHDRAWAL, event.operationType)
        assertEquals(TBankNotificationParser.Flow.EXPENSE, event.flow)
        assertEquals("Другое", event.category)
    }

    @Test fun cashbackIsIncomeCandidate() {
        val event = TBankNotificationParser.parse(input("Кэшбэк 120 ₽"))!!
        assertEquals(TBankNotificationParser.OperationType.CASHBACK, event.operationType)
        assertEquals(TBankNotificationParser.Flow.INCOME, event.flow)
    }

    @Test fun repeatedSameAmountMerchantRemainSeparateWithoutStableId() {
        val first = TBankNotificationParser.parse(input("Покупка КрасАвтоТранс 48 ₽"))!!
        val second = TBankNotificationParser.parse(input("Покупка КрасАвтоТранс 48 ₽"))!!
        assertEquals(first.amountKopecks, second.amountKopecks)
        assertNull(first.safeDeduplicationKey)
        assertNull(second.safeDeduplicationKey)
    }

    @Test fun stableNotificationKeyProducesDeduplicationKey() {
        val event = TBankNotificationParser.parse(input("Покупка Магнит 100 ₽", key = "notification-123"))!!
        assertEquals("tbank:${TBankNotificationParser.T_BANK_PACKAGE}:notification-123", event.safeDeduplicationKey)
    }

    @Test fun multipleAmountsAreNotGuessed() {
        val event = TBankNotificationParser.parse(input("Покупка 100 ₽. Баланс 2 000 ₽"))!!
        assertNull(event.amountKopecks)
        assertTrue(event.requiresReview)
    }

    @Test fun balanceNoticeDoesNotBecomeExpense() {
        val event = TBankNotificationParser.parse(input("Баланс карты 12 000 ₽"))!!
        assertEquals(TBankNotificationParser.OperationType.BALANCE_NOTICE, event.operationType)
        assertEquals(TBankNotificationParser.Flow.NONE, event.flow)
    }

    @Test fun ignoresCashbackAmountWhenCashbackAppearsBeforePurchase() {
        val event = TBankNotificationParser.parse(
            input("Кэшбэк 11 ₽, покупка Магнит 220,96 ₽")
        )!!
        assertEquals(TBankNotificationParser.OperationType.PURCHASE, event.operationType)
        assertEquals(22096L, event.amountKopecks)
        assertEquals(TBankNotificationParser.Flow.EXPENSE, event.flow)
    }

    @Test fun ignoresBalanceAmountWhenBalanceAppearsBeforePurchase() {
        val event = TBankNotificationParser.parse(
            input("Баланс карты 5 000 ₽. Покупка Магнит 220 ₽")
        )!!
        assertEquals(TBankNotificationParser.OperationType.PURCHASE, event.operationType)
        assertEquals(22000L, event.amountKopecks)
    }

    @Test fun parsesNonBreakingSpaceGroupedThousandsAndKopecks() {
        val event = TBankNotificationParser.parse(input("Покупка Магнит 12 345,67 ₽"))!!
        assertEquals(1_234_567L, event.amountKopecks)
    }

    @Test fun refusesToChooseBetweenTwoTransactionAmounts() {
        val event = TBankNotificationParser.parse(
            input("Покупка Магнит 100 ₽. Покупка Аптека 200 ₽")
        )!!
        assertNull(event.amountKopecks)
        assertTrue(event.requiresReview)
        assertTrue(event.reviewReasons.contains(TBankNotificationParser.ReviewReason.AMBIGUOUS_AMOUNT))
    }
    @Test fun decimalPunctuationDoesNotSplitTransactionAmount() {
        val comma = TBankNotificationParser.parse(input("Покупка Магнит 220,96 ₽"))!!
        val dot = TBankNotificationParser.parse(input("Покупка Магнит 220.96 ₽"))!!
        assertEquals(22096L, comma.amountKopecks)
        assertEquals(22096L, dot.amountKopecks)
    }

    @Test fun informationalAmountAndPurchaseAmountSeparatedBySentenceAreHandled() {
        val event = TBankNotificationParser.parse(input("Баланс карты 5 000 ₽. Покупка Магнит 220,96 ₽"))!!
        assertEquals(22096L, event.amountKopecks)
    }

    @Test fun ambiguousAmountsInsideSameClauseRequireReview() {
        val event = TBankNotificationParser.parse(input("Покупка Магнит 220 ₽ и списание 300 ₽"))!!
        assertNull(event.amountKopecks)
        assertTrue(event.requiresReview)
    }

}
