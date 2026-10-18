package tech.elephantclock.privatemail.core.triage.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.triage.TestMail.h
import tech.elephantclock.privatemail.core.triage.TestMail.mail

class BulkDetectorTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("noReply")
    fun `no-reply local parts`(
        email: String,
        expected: Boolean,
    ) {
        assertEquals(expected, BulkDetector.isNoReply(Address.of(email)))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("esp")
    fun `recognises ESPs`(
        headers: Map<String, List<String>>,
        expected: String?,
    ) {
        assertEquals(expected, BulkDetector.detect(mail(headers = headers).header).esp)
    }

    @Test
    fun `list headers, precedence and auto-submitted`() {
        val r =
            BulkDetector.detect(
                mail(
                    headers =
                        h(
                            "List-Unsubscribe" to "<mailto:u@news.example>",
                            "List-Id" to "Shop News <news.shop.example>",
                            "Precedence" to " Bulk ",
                            "Auto-Submitted" to "auto-generated",
                        ),
                ).header,
            )
        assertEquals(BulkDetector.Result(true, "news.shop.example", true, null, false, true), r)
    }

    @Test
    fun `quiet values`() {
        val r =
            BulkDetector.detect(
                mail(headers = h("List-Unsubscribe" to "  ", "Precedence" to "list", "Auto-Submitted" to "no")).header,
            )
        assertEquals(BulkDetector.Result(false, null, false, null, false, false), r)
    }

    @Test
    fun `list id edge cases`() {
        assertEquals("plain.example", BulkDetector.listId(" Plain.Example "))
        assertEquals(null, BulkDetector.listId("<>"))
        assertEquals(255, BulkDetector.listId("<" + "a".repeat(5000) + ">")!!.length)
        assertEquals("broken <x", BulkDetector.listId("Broken <x"), "an unclosed bracket keeps the whole value")
    }

    @Test
    fun `auto-submitted with parameters`() {
        assertEquals(true, BulkDetector.isAutoSubmitted("auto-replied; owner-email=\"x@y\""))
        assertEquals(false, BulkDetector.isAutoSubmitted("No"))
        assertEquals(false, BulkDetector.isAutoSubmitted(""))
    }

    companion object {
        @JvmStatic
        fun noReply() =
            listOf(
                Arguments.of("noreply@x.com", true),
                Arguments.of("no-reply@x.com", true),
                Arguments.of("no_reply@x.com", true),
                Arguments.of("do-not-reply@x.com", true),
                Arguments.of("donotreply@x.com", true),
                Arguments.of("noreply+bounce123@x.com", true),
                Arguments.of("noreply-orders@x.com", true),
                Arguments.of("NoReply@X.com", true),
                Arguments.of("mailer-daemon@x.com", true),
                Arguments.of("ne-otvechat@x.ru", true),
                Arguments.of("no-responder@x.es", true),
                Arguments.of("ne-pas-repondre@x.fr", true),
                Arguments.of("nicht-antworten@x.de", true),
                Arguments.of("support@x.com", false),
                Arguments.of("nora@x.com", false),
                Arguments.of("reply@x.com", false),
                Arguments.of("notifications@github.com", false),
            )

        @JvmStatic
        fun esp() =
            listOf(
                Arguments.of(h("X-Mailgun-Sid" to "abc"), "mailgun"),
                Arguments.of(h("X-SG-EID" to "abc"), "sendgrid"),
                Arguments.of(h("X-MC-User" to "abc"), "mailchimp"),
                Arguments.of(h("X-Campaign" to "spring"), "campaign"),
                Arguments.of(h("Feedback-ID" to "1.eu-west-1.abc:AmazonSES"), "amazonses"),
                Arguments.of(h("Feedback-ID" to "123:456:gmail"), null),
                Arguments.of(h("Return-Path" to "<bounce-123@mail.sendgrid.net>"), "sendgrid"),
                Arguments.of(h("Return-Path" to "<b@bounce.mcsv.net>"), "mailchimp"),
                Arguments.of(h("Return-Path" to "<x@notsendgrid.net>"), null),
                Arguments.of(h("Return-Path" to "<>"), null),
                Arguments.of(h("Return-Path" to "garbage"), null),
                Arguments.of(h("X-SG-EID" to ""), null),
                Arguments.of(emptyMap<String, List<String>>(), null),
            )
    }
}
