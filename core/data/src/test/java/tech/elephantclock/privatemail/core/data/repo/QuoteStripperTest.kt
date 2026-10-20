package tech.elephantclock.privatemail.core.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteStripperTest {
    private fun strip(text: String) = QuoteStripper.strip(text.trimIndent())

    @Test
    fun `angle-quoted lines go`() {
        assertEquals(
            "Yes, Friday works.\n\nSee you then.",
            strip(
                """
                Yes, Friday works.
                > Can we meet on Friday?
                >> Earlier text

                See you then.
                """,
            ),
        )
    }

    @Test
    fun `English attribution cuts the rest, also when wrapped`() {
        assertEquals(
            "Thanks, sent.",
            strip("Thanks, sent.\n\nOn Mon, 19 Oct 2026 at 10:00, Anna <anna@x.example> wrote:\nOld text"),
        )
        assertEquals(
            "Thanks, sent.",
            strip(
                "Thanks, sent.\n\nOn Mon, 19 Oct 2026 at 10:00, Anna Very-Long-Name <anna@x.example>\nwrote:\nOld text",
            ),
        )
    }

    @Test
    fun `Russian attributions and separators cut the rest`() {
        assertEquals(
            "Оплачу завтра.",
            strip("Оплачу завтра.\n\n20 окт. 2026 г., в 10:00, Анна <anna@x.ru> написал(а):\nСтарый текст"),
        )
        assertEquals(
            "Оплачу завтра.",
            strip("Оплачу завтра.\n\nпн, 19 окт. 2026 г. в 10:00, Анна <anna@x.ru>:\nСтарый текст"),
        )
        assertEquals("Оплачу завтра.", strip("Оплачу завтра.\n\n-----Исходное сообщение-----\nОт: Анна\nСтарый текст"))
        assertEquals(
            "Оплачу завтра.",
            strip(
                "Оплачу завтра.\n\nОт: Анна <anna@x.ru>\nОтправлено: 19 октября 2026 г.\nКому: Сэм\nТема: Счёт\n\nСтарый",
            ),
        )
    }

    @Test
    fun `German attributions and Outlook blocks cut the rest`() {
        assertEquals("Passt, danke.", strip("Passt, danke.\n\nAm 19.10.2026 um 10:00 schrieb Anna <anna@x.de>:\nAlt"))
        assertEquals("Passt, danke.", strip("Passt, danke.\n\n-----Ursprüngliche Nachricht-----\nVon: Anna\nAlt"))
        assertEquals(
            "Passt, danke.",
            strip(
                "Passt, danke.\n\nVon: Anna <anna@x.de>\nGesendet: Montag, 19. Oktober 2026\nAn: Sam\nBetreff: Angebot\n\nAlt",
            ),
        )
        assertEquals(
            "Sounds good.",
            strip(
                "Sounds good.\n\n________________________________\nFrom: Anna\nSent: Monday\nTo: Sam\nSubject: Offer\n\nOld",
            ),
        )
    }

    @Test
    fun `forward headers go but the forwarded text stays`() {
        assertEquals(
            "FYI, see below.\n\nPlease pay invoice 42 by Friday.",
            strip(
                """
                FYI, see below.

                ---------- Forwarded message ---------
                From: Billing <billing@shop.example>
                Date: Mon, 19 Oct 2026
                Subject: Invoice 42
                To: Sam <sam@home.example>

                Please pay invoice 42 by Friday.
                """,
            ),
        )
        assertEquals(
            "Смотри ниже.\n\nОплатите счёт.",
            strip(
                "Смотри ниже.\n\n-------- Пересылаемое сообщение --------\nОт: Магазин\nТема: Счёт\n\nОплатите счёт.",
            ),
        )
        assertEquals(
            "Zur Info.\n\nBitte zahlen.",
            strip(
                "Zur Info.\n\n---------- Weitergeleitete Nachricht ---------\nVon: Shop\nBetreff: Rechnung\n\nBitte zahlen.",
            ),
        )
    }

    @Test
    fun `signatures are cut and blank runs collapse`() {
        assertEquals("Hello\n\nBye", QuoteStripper.strip("Hello\n\n\n\nBye\n-- \nAnna\n+1 555 0100"))
    }

    @Test
    fun `ordinary text that only looks similar stays`() {
        val text = "On Friday we wrote the plan.\nFrom: the team, with thanks.\nThe date: 24 October."
        assertEquals(text, QuoteStripper.strip(text))
    }

    @Test
    fun `a message that is only a quote keeps its text`() {
        assertEquals("> just a quote", QuoteStripper.strip("> just a quote"))
    }
}
