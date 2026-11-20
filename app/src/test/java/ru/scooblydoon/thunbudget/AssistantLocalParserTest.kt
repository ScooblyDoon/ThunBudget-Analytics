package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
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

    @Test fun parsesYesterdayCategoryQuery() {
        val command = AssistantLocalParser.parse("Сколько потратил на продукты вчера?", categories, today)
        assertEquals(AssistantIntent.QUERY_CATEGORY_SPENDING, command?.intent)
        assertEquals("2026-11-18", command?.periodStart)
        assertEquals("2026-11-18", command?.periodEnd)
    }

    @Test fun parsesGenericExpensesQuery() {
        val command = AssistantLocalParser.parse("Покажи все расходы за неделю", categories, today)
        assertEquals(AssistantIntent.QUERY_SPENDING, command?.intent)
        assertEquals("2026-11-13", command?.periodStart)
        assertEquals("2026-11-19", command?.periodEnd)
    }

    @Test fun parsesVoiceUpdateLastExpense() {
        val command = AssistantLocalParser.parse("Измени последний расход на 1500 рублей", categories, today)
        assertEquals(AssistantIntent.UPDATE_EXPENSE, command?.intent)
        assertEquals(1500.0, command?.amount)
        assertEquals("last_expense", command?.target)
    }

    @Test fun parsesVoiceDeleteLastExpense() {
        val command = AssistantLocalParser.parse("Удали последнюю операцию", categories, today)
        assertEquals(AssistantIntent.DELETE_EXPENSE, command?.intent)
        assertEquals("last_expense", command?.target)
    }

    @Test fun parsesExpenseWithExplicitDateAndImpreciseCategory() {
        val command = AssistantLocalParser.parse(
            "Добавь расход 1 250,50 рублей на продукт за 05.11.2026",
            categories,
            today
        )
        assertEquals(AssistantIntent.ADD_EXPENSE, command?.intent)
        assertEquals("Продукты", command?.category)
        assertEquals(1250.50, command?.amount ?: 0.0, 0.001)
        assertEquals("2026-11-05", command?.date)
    }

    @Test fun unknownPhraseDoesNotProduceCommand() {
        assertNull(AssistantLocalParser.parse("Расскажи анекдот", categories, today))
    }
}
