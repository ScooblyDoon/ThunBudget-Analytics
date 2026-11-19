package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AssistantCommandValidatorTest {
    private val categories = listOf("Продукты", "Транспорт", "Жильё и ЖКХ")

    @Test fun validExpenseIsNormalizedAndRequiresConfirmation() {
        val result = AssistantCommandValidator.validate(
            AssistantCommand(
                intent = AssistantIntent.ADD_EXPENSE,
                amount = 1250.0,
                category = "продукты",
                date = "2026-11-18",
                confidence = 0.95
            ),
            categories,
            LocalDate.parse("2026-11-19")
        )
        assertTrue(result is AssistantCommandValidation.Valid)
        val command = (result as AssistantCommandValidation.Valid).command
        assertEquals("Продукты", command.category)
        assertTrue(command.requiresConfirmation)
    }

    @Test fun unknownCategoryNeedsClarification() {
        val result = AssistantCommandValidator.validate(
            AssistantCommand(
                intent = AssistantIntent.ADD_EXPENSE,
                amount = 100.0,
                category = "Криптовалюта",
                confidence = 0.9
            ),
            categories
        )
        assertTrue(result is AssistantCommandValidation.NeedsClarification)
    }

    @Test fun readOnlyIntentDoesNotRequireConfirmation() {
        val result = AssistantCommandValidator.validate(
            AssistantCommand(intent = AssistantIntent.QUERY_REMAINING, confidence = 0.9),
            categories
        )
        assertTrue(result is AssistantCommandValidation.Valid)
        assertEquals(false, (result as AssistantCommandValidation.Valid).command.requiresConfirmation)
    }

    @Test fun modelCannotChooseArbitraryExpenseId() {
        val result = AssistantCommandValidator.validate(
            AssistantCommand(
                intent = AssistantIntent.DELETE_EXPENSE,
                target = "expense_12345",
                confidence = 0.9
            ),
            categories
        )
        assertTrue(result is AssistantCommandValidation.NeedsClarification)
    }

    @Test fun reversedPeriodIsRejected() {
        val result = AssistantCommandValidator.validate(
            AssistantCommand(
                intent = AssistantIntent.QUERY_SPENDING,
                periodStart = "2026-11-20",
                periodEnd = "2026-11-01",
                confidence = 0.9
            ),
            categories
        )
        assertTrue(result is AssistantCommandValidation.Rejected)
    }
}
