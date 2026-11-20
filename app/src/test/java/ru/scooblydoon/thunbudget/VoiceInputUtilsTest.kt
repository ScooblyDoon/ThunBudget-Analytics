package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class VoiceInputUtilsTest {
    private val today = LocalDate.parse("2026-11-20")
    private val categories = listOf(
        BudgetCategory("Продукты", 25.0),
        BudgetCategory("Транспорт", 10.0),
        BudgetCategory("Кафе и развлечения", 10.0)
    )

    @Test fun parsesDecimalAmountWithKopecks() {
        assertEquals(1250.50, (parseSpokenAmount("добавь расход 1 250 рублей 50 копеек") ?: 0.0), 0.001)
    }

    @Test fun preservesDotDecimalAmount() {
        assertEquals(1.25, (parseSpokenAmount("добавь расход 1.25 рубля") ?: 0.0), 0.001)
        assertNull(parseVoiceDate("добавь расход 1.25 рубля", today))
    }

    @Test fun parsesDecimalCommaAmount() {
        assertEquals(1250.75, (parseSpokenAmount("добавь расход 1 250,75 рублей") ?: 0.0), 0.001)
    }

    @Test fun parsesAmountWrittenInWords() {
        assertEquals(2550.0, (parseSpokenAmount("две тысячи пятьсот пятьдесят рублей") ?: 0.0), 0.001)
    }

    @Test fun parsesYesterdayAndDayBeforeYesterday() {
        assertEquals(LocalDate.parse("2026-11-19"), parseVoiceDate("вчера", today))
        assertEquals(LocalDate.parse("2026-11-18"), parseVoiceDate("позавчера", today))
    }

    @Test fun parsesExplicitNumericDate() {
        assertEquals(LocalDate.parse("2026-11-05"), parseVoiceDate("расход за 05.11.2026", today))
    }

    @Test fun parsesDateWithMonthName() {
        assertEquals(LocalDate.parse("2026-11-05"), parseVoiceDate("за 5 ноября", today))
    }

    @Test fun parsesDayAndMonthWithoutYearAsMostRecentPastDate() {
        assertEquals(LocalDate.parse("2026-11-15"), parseVoiceDate("внеси 500 рублей в транспорт за 15 ноября", today))
        assertEquals(LocalDate.parse("2025-12-15"), parseVoiceDate("за 15 декабря", today))
    }

    @Test fun rejectsInvalidDate() {
        assertNull(parseVoiceDate("за 32.13.2026", today))
    }

    @Test fun fuzzyMatchesCategoryInflection() {
        assertEquals("Продукты", findVoiceCategory("добавь расход на продукт", categories)?.name)
    }

    @Test fun doesNotGuessUnrelatedCategory() {
        assertNull(findVoiceCategory("добавь расход 500 рублей на авиабилет", categories))
    }

    @Test fun formatsDigitsAsRussianDate() {
        assertEquals("05.11.2026", formatDateDigits("05112026"))
        assertEquals("05.11.2026", formatDateDigits("05.11.2026"))
    }

    @Test fun convertsIsoVoiceDateToDigitsInDayMonthYearOrder() {
        assertEquals("17112026", voiceDateDigits("2026-11-17"))
        assertEquals("", voiceDateDigits("not-a-date"))
    }

    @Test fun parsesMaskedDate() {
        assertEquals(LocalDate.parse("2026-11-05"), parseUserDate("05.11.2026"))
        assertNull(parseUserDate("32.13.2026"))
    }
}
