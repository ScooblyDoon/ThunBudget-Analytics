package ru.scooblydoon.thunbudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantRecognitionDictionaryTest {
    @Test
    fun resolvesCommonRussianTransportSynonymsOnlyToExistingCategory() {
        val result = AssistantRecognitionDictionary.resolveCategory(
            phrase = "Оплатил бензин на заправке",
            availableCategories = listOf("Продукты", "Транспорт", "Кафе")
        )
        assertEquals("Транспорт", result)
    }

    @Test
    fun resolvesPharmacyAndMedicineToHealthCategory() {
        val result = AssistantRecognitionDictionary.resolveCategory(
            phrase = "Купил лекарства в аптеке",
            availableCategories = listOf("Здоровье", "Покупки")
        )
        assertEquals("Здоровье", result)
    }

    @Test
    fun exactCategoryNameTakesPriorityOverGenericBuiltInSynonym() {
        val result = AssistantRecognitionDictionary.resolveCategory(
            phrase = "Продукты",
            availableCategories = listOf("Питание", "Продукты")
        )
        assertEquals("Продукты", result)
    }

    @Test
    fun userConfirmedAliasTakesPriorityOverBuiltInDictionary() {
        val result = AssistantRecognitionDictionary.resolveCategory(
            phrase = "Сельпо",
            availableCategories = listOf("Продукты", "Покупки"),
            userAliases = mapOf("сельпо" to "Продукты")
        )
        assertEquals("Продукты", result)
    }

    @Test
    fun aliasCannotResolveToCategoryThatIsNotInTheBudget() {
        val result = AssistantRecognitionDictionary.resolveCategory(
            phrase = "Сельпо",
            availableCategories = listOf("Продукты", "Покупки"),
            userAliases = mapOf("сельпо" to "Несуществующая категория")
        )
        assertNull(result)
    }

    @Test
    fun ambiguousGenericShopTermIsNotAutoCategorized() {
        val result = AssistantRecognitionDictionary.resolveCategory(
            phrase = "Покупка в магазине",
            availableCategories = listOf("Продукты", "Покупки", "Дом")
        )
        assertNull(result)
    }


    @Test
    fun parsesExplicitSynonymTeachingCommand() {
        val teaching = AssistantRecognitionDictionary.parseTeachingCommand(
            "Запомни синоним «кофейня» для категории «Кафе и развлечения»"
        )
        assertEquals("кофейня", teaching?.alias)
        assertEquals("Кафе и развлечения", teaching?.category)
    }

    @Test
    fun parsesNaturalSynonymTeachingPhrase() {
        val teaching = AssistantRecognitionDictionary.parseTeachingCommand(
            "Запомни, что кофейня — это категория Кафе и развлечения"
        )
        assertEquals("кофейня", teaching?.alias)
        assertEquals("Кафе и развлечения", teaching?.category)
    }

    @Test
    fun parsesAliasRemovalCommand() {
        assertEquals(
            "кофейня",
            AssistantRecognitionDictionary.parseAliasRemovalCommand("Удали синоним «кофейня»")
        )
    }

    @Test
    fun doesNotLearnVeryShortAlias() {
        assertNull(
            AssistantRecognitionDictionary.parseTeachingCommand(
                "Запомни синоним «ко» для категории «Кафе и развлечения»"
            )
        )
    }

    @Test
    fun normalizesRussianYoAndPunctuation() {
        assertEquals("пятерочка кофе", AssistantRecognitionDictionary.normalize("Пятёрочка, кофе!"))
    }
}
