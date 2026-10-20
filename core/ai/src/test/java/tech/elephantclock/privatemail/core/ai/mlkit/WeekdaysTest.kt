package tech.elephantclock.privatemail.core.ai.mlkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.LanguageTag

class WeekdaysTest {
    private val ru = LanguageTag.RUSSIAN
    private val en = LanguageTag.ENGLISH
    private val de = LanguageTag("de")

    @Test
    fun `Russian weekdays are found in their inflected forms, look-alike words are not`() {
        assertEquals(listOf(1, 2, 4, 6), Weekdays.find("Во вторник или в среду, до пятницы, не в воскресенье", ru))
        // "среди" (among), "средство" (means), "пятно" (spot) aren't weekdays.
        assertEquals(emptyList<Int>(), Weekdays.find("Среди прочего, средство от пятна", ru))
    }

    @Test
    fun `English and German weekdays, plurals included`() {
        assertEquals(listOf(0, 4), Weekdays.find("Mondays and Friday", en))
        assertEquals(listOf(1, 5, 5), Weekdays.find("Dienstags, Samstag oder Sonnabend", de))
    }

    @Test
    fun `kept compares the weekdays of source and translation`() {
        assertTrue(Weekdays.kept("Tuesday at 3 pm", en, "Во вторник в 15:00", ru))
        assertFalse(Weekdays.kept("Tuesday at 3 pm", en, "В понедельник в 15:00", ru))
        assertFalse(Weekdays.kept("Tuesday at 3 pm", en, "В 15:00", ru))
        // Languages without a table aren't checked.
        assertTrue(Weekdays.kept("Tuesday", en, "火曜日", LanguageTag("ja")))
    }

    @Test
    fun `the glossary names each weekday once in the target language`() {
        assertEquals(
            "Tuesday = вторник, Friday = пятница",
            Weekdays.glossary("Tuesday or Friday, not Tuesdays", en, ru),
        )
        assertNull(Weekdays.glossary("No days here", en, ru))
    }
}
