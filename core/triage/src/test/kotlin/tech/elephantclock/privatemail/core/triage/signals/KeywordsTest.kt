package tech.elephantclock.privatemail.core.triage.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class KeywordsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("securityCodes")
    fun `security codes`(
        text: String,
        expected: Boolean,
    ) {
        assertEquals(expected, Keywords.securityCode(text), text)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("money")
    fun `money signals`(
        text: String,
        expected: Boolean,
    ) {
        assertEquals(expected, Keywords.money(text), text)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orders")
    fun `orders and travel`(
        text: String,
        expected: Boolean,
    ) {
        assertEquals(expected, Keywords.ordersOrTravel(text), text)
    }

    @Test
    fun `account security and personal messages`() {
        listOf(
            "Reset your password",
            "Новый вход в аккаунт",
            "Neues Gerät angemeldet",
            "Votre mot de passe",
            "Tu contraseña",
            "تسجيل الدخول",
        ).forEach { assertEquals(true, Keywords.accountSecurity(it), it) }
        assertEquals(false, Keywords.accountSecurity("Autumn sale"))
        listOf(
            "Anna sent you a message",
            "Новое сообщение от Анны",
            "Einladung zum Netzwerk",
            "Nuevo mensaje",
            "رسالة جديدة",
        ).forEach { assertEquals(true, Keywords.personalMessage(it), it) }
        assertEquals(false, Keywords.personalMessage("Anna liked your photo"))
    }

    @Test
    fun `digit runs`() {
        assertEquals(true, Keywords.hasCode("is 4821"))
        assertEquals(true, Keywords.hasCode("is 482 193."))
        assertEquals(true, Keywords.hasCode("is 482-193"))
        assertEquals(true, Keywords.hasCode("رمز ٤٨٢١٩٣"), "Arabic-Indic digits")
        assertEquals(false, Keywords.hasCode("is 123"))
        assertEquals(false, Keywords.hasCode("is 123456789"))
        assertEquals(false, Keywords.hasCode("PR #4821"))
        assertEquals(false, Keywords.hasCode("v4821"))
        assertEquals(false, Keywords.hasCode("12.50"))
        assertEquals(false, Keywords.hasCode("1 2 3 4"))
    }

    @Test
    fun `hostile input stays fast and never throws`() {
        val inputs =
            listOf(
                "1".repeat(100_000),
                "1 ".repeat(50_000),
                "код ".repeat(30_000),
                "оплаоплаопла".repeat(10_000),
                "\uD800".repeat(10_000),
                "",
            )
        val start = System.nanoTime()
        repeat(10) {
            for (s in inputs) {
                Keywords.securityCode(s)
                Keywords.money(s)
                Keywords.ordersOrTravel(s)
                Keywords.report(s)
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assert(ms < 3_000) { "took $ms ms" }
    }

    companion object {
        @JvmStatic
        fun securityCodes() =
            listOf(
                Arguments.of("Your verification code is 482193", true),
                Arguments.of("Your Verification Code: 482 193", true),
                Arguments.of("G-482193 is your Google verification code", true),
                Arguments.of("Код подтверждения: 4821", true),
                Arguments.of("Ваш код для входа — 123456", true),
                Arguments.of("Ваш одноразовый пароль 99887766", true),
                Arguments.of("Ihr Bestätigungscode lautet 552211", true),
                Arguments.of("Votre code de vérification : 774411", true),
                Arguments.of("Tu código de verificación es 3344", true),
                Arguments.of("رمز التحقق الخاص بك هو 551234", true),
                Arguments.of("Verification\u200B code 551234", true),
                // Negatives
                Arguments.of("Code review requested on PR #4821", false),
                Arguments.of("[repo] Fix the code path (#1234)", false),
                Arguments.of("Your verification is complete", false),
                Arguments.of("Your code is ready to deploy", false),
                Arguments.of("Weekly report 2026", false),
                Arguments.of("Promo code SAVE20 — 20% off", false),
                Arguments.of("", false),
            )

        @JvmStatic
        fun money() =
            listOf(
                Arguments.of("Invoice #12 for October", true),
                Arguments.of("Your receipt from Shop", true),
                Arguments.of("Payment failed", true),
                Arguments.of("Refund processed", true),
                Arguments.of("Your bill is ready", true),
                Arguments.of("Счёт на оплату №45", true),
                Arguments.of("Оплата прошла успешно", true),
                Arguments.of("Платёж отклонён", true),
                Arguments.of("Списание с карты", true),
                Arguments.of("Ваш чек", true),
                Arguments.of("Ihre Rechnung", true),
                Arguments.of("Votre facture", true),
                Arguments.of("Su factura de octubre", true),
                Arguments.of("فاتورة رقم ١٢", true),
                Arguments.of("Duplicate payment detected", true),
                // Negatives
                Arguments.of("Billion-dollar ideas newsletter", false),
                Arguments.of("Save 20% today — only €9.99", false),
                Arguments.of("Чекап здоровья", false),
                Arguments.of("Hello from the team", false),
                Arguments.of("Paidia conference", false),
            )

        @JvmStatic
        fun orders() =
            listOf(
                Arguments.of("Your order #123 has shipped", true),
                Arguments.of("Booking confirmation: Hotel Adlon", true),
                Arguments.of("Ваш заказ №55 передан в доставку", true),
                Arguments.of("Ihre Bestellung ist unterwegs", true),
                Arguments.of("Votre commande n°12", true),
                Arguments.of("Tu pedido ha sido enviado", true),
                Arguments.of("تم شحن طلبك", true),
                Arguments.of("Meeting notes", false),
            )
    }
}
