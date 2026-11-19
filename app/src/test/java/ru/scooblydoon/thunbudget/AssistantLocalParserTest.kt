package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AssistantLocalParserTest {
    private val categories = listOf("Продукты", "Транспорт", "Жильё и ЖКХ")
    private val today = LocalDate.parse("2026-11-19")

    @Test fun parsesRemainingQuery() {
        val command = AssistantLocalParser.parse("Сколько у меня осталось денег до зарплаты?", categories, today)
        assertEquals(AssistantIntent.QUERY_REMAINING, command?.intent)
    }

    @Test fun parsesCategorySpendingForCurrentMonth() {
        val command = AssistantLocalParser.parse("Сколько я потратил на продукты в этом месяце?", categories, today)
        assertEquals(AssistantIntent.QUERY_CATEGORY_SPENDING, command?.intent)
        assertEquals("Продукты", command?.category)
        assertEquals("2026-11-01", command?.periodStart)
        assertEquals("2026-11-19", command?.periodEnd)
    }

    @Test fun parsesYesterdayExpenseWithWordsAmount() {
        val command = AssistantLocalParser.parse("Вчера потратил две тысячи на продукты", categories, today)
        assertEquals(AssistantIntent.ADD_EXPENSE, command?.intent)
        assertEquals(2000.0, command?.amount)
        assertEquals("Продукты", command?.category)
        assertEquals("2026-11-18", command?.date)
    }

    @Test fun parsesOverspendingQuery() {
        val command = AssistantLocalParser.parse("Покажи, где я превысил бюджет", categories, today)
        assertEquals(AssistantIntent.QUERY_OVERSPENDING, command?.intent)
    }

    @Test fun parsesDailyForecast() {
        val command = AssistantLocalParser.parse("Если буду тратить по тысяче рублей в день, сколько останется к зарплате?", categories, today)
        assertEquals(AssistantIntent.FORECAST, command?.intent)
        assertEquals(1000.0, command?.amount)
    }

    @Test fun unknownPhraseDoesNotProduceCommand() {
        assertNull(AssistantLocalParser.parse("Расскажи анекдот", categories, today))
    }
}
