package tech.elephantclock.privatemail.core.triage.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class LookalikeDomainTest {
    private val detector =
        LookalikeDomain(
            frequentDomains = listOf("izi.travel", "elephantclock.tech", "acme-logistics.de", "partner.com"),
        )

    @ParameterizedTest(name = "{0} imitates {1}")
    @MethodSource("lookalikes")
    fun `flags imitations`(
        domain: String,
        imitated: String,
    ) {
        assertEquals(imitated, detector.detect(domain))
    }

    @ParameterizedTest(name = "{0} is fine")
    @ValueSource(
        strings = [
            "paypal.com", "accounts.google.com", "mail.izi.travel", "izi.travel", "e.linkedin.com",
            "email.apple.com", "google.de", "amazon.co.uk", "amazon.it", "sberbank.ru", "online.sberbank.ru",
            "github.com", "notifications.github.com", "elephantclock.tech", "PayPal.COM.",
            // Regional domains of brands and frequent domains: same name under another suffix
            "google.com.au", "mail.google.com.br", "amazon.com.br", "yandex.com.tr", "paypal.com.au", "partner.com.au",
            // Ordinary domains near brand names
            "black.com", "apply.com", "amazom.com", "email.com", "acme-logistic.de", "offices.com", "bookings.com",
            "stripes.com", "cooking.com",
            // Unrelated, including a genuine Cyrillic domain
            "example.org", "shop.example", "пример.рф", "xn--e1afmkfd.xn--p1ai", "mail.ru",
            "ups.example.co.uk",
            "apple-farm.com", "live-events.com", "zoom-meetings.org", "my-booking-site.com",
        ],
    )
    fun `does not flag legitimate or unrelated domains`(domain: String) {
        assertNull(detector.detect(domain))
    }

    @Test
    fun `display name carrying a brand domain from an unrelated sender`() {
        assertEquals("paypal.com", detector.detect("evil.example", "service@paypal.com"))
        assertEquals("paypal.com", detector.detect("evil.example", "PayPal.com Support"))
        assertNull(detector.detect("paypal.com", "service@paypal.com"))
        assertNull(detector.detect("google.com", "Jane (jane@paypal.com) via Google Docs"))
        assertNull(detector.detect("evil.example", "PayPal Support"), "brand name without a domain is left to the AI")
        assertNull(detector.detect("evil.example", "mypaypal.company"))
    }

    @Test
    fun `frequent domains that are also brands are not duplicated or self-flagged`() {
        val d = LookalikeDomain(listOf("gmail.com", "GMAIL.com", "", "not a domain", "[1.2.3.4]"))
        assertNull(d.detect("gmail.com"))
        assertEquals("google.com", d.detect("gmai1.com"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", ".", "..", "a", ".com", "xn--", "xn--.com", "xn--zzzzzzzz.com", "xn--ls8h.la", "[127.0.0.1]",
            "a..b", "-.-", "\uD800.com", "ex ample.com", "a@b.com",
        ],
    )
    fun `garbage never throws`(domain: String) {
        detector.detect(domain)
        detector.detect(domain, domain)
    }

    @Test
    fun `very long domain is ignored without throwing`() {
        assertNull(detector.detect("a".repeat(300) + ".com"))
        detector.detect(("ab.".repeat(80)) + "com", "x".repeat(100_000))
    }

    companion object {
        @JvmStatic
        fun lookalikes() =
            listOf(
                // Digit swaps and letter look-alikes
                Arguments.of("paypa1.com", "paypal.com"),
                Arguments.of("paypai.com", "paypal.com"),
                Arguments.of("g00gle.com", "google.com"),
                Arguments.of("micr0soft.com", "microsoft.com"),
                Arguments.of("rnicrosoft.com", "microsoft.com"),
                Arguments.of("0zon.ru", "ozon.ru"),
                Arguments.of("lzi.travel", "izi.travel"),
                // Cyrillic / Greek homoglyphs, as punycode and as Unicode
                Arguments.of("xn--pypal-4ve.com", "paypal.com"), // punycode of paypal with a Cyrillic a (U+0430)
                Arguments.of("p\u0430ypal.com", "paypal.com"),
                Arguments.of("\u0430\u0440\u0440l\u0435.com", "apple.com"),
                Arguments.of("g\u03BF\u03BFgle.com", "google.com"), // Greek omicron
                Arguments.of("\u0455berbank.ru", "sberbank.ru"),
                Arguments.of("payp\u00E1l.com", "paypal.com"),
                // One typo
                Arguments.of("gooogle.com", "google.com"),
                Arguments.of("gogle.com", "google.com"),
                Arguments.of("arnazon.com", "amazon.com"),
                Arguments.of("amazonn.com", "amazon.com"),
                Arguments.of("microsofl.com", "microsoft.com"),
                Arguments.of("gmial.com", "google.com"),
                Arguments.of("tinkof.ru", "tinkoff.ru"),
                Arguments.of("micosoft.com", "microsoft.com"),
                Arguments.of("wildberies.ru", "wildberries.ru"),
                Arguments.of("elephantclok.tech", "elephantclock.tech"),
                Arguments.of("acme-logisitcs.de", "acme-logistics.de"),
                // Brand used inside another domain
                Arguments.of("paypal.com.secure-login.ru", "paypal.com"),
                Arguments.of("izi.travel.account-check.com", "izi.travel"),
                Arguments.of("paypal.com.evil.example", "paypal.com"),
                Arguments.of("izi.travel.de", "izi.travel"), // a subdomain of travel.de, not izi's
                Arguments.of("login.google.com.secure.co.uk", "google.com"),
                Arguments.of("paypal-secure.com", "paypal.com"),
                Arguments.of("secure-paypa1.net", "paypal.com"),
                Arguments.of("sberbank-online.info", "sberbank.ru"),
                Arguments.of("gosuslugi-verify.ru", "gosuslugi.ru"),
            )
    }
}
