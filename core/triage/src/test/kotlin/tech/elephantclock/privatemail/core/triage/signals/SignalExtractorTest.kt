package tech.elephantclock.privatemail.core.triage.signals

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.AuthResult
import tech.elephantclock.privatemail.core.model.AuthResults
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.triage.TestMail.h
import tech.elephantclock.privatemail.core.triage.TestMail.mail
import kotlin.random.Random

class SignalExtractorTest {
    private val extractor =
        SignalExtractor(frequentDomains = listOf("izi.travel"), trustedAuthservIds = setOf("mx.izi.travel"))

    @Test
    fun `a plain personal message has no signals`() {
        assertEquals(
            Signals(),
            extractor.extract(mail(from = "anna@example.org", subject = "Lunch?", preview = "See you at 1")),
        )
    }

    @Test
    fun `a newsletter`() {
        val s =
            extractor.extract(
                mail(
                    from = "news@shop.example",
                    subject = "Autumn sale",
                    headers =
                        h(
                            "List-Unsubscribe" to "<https://shop.example/u>",
                            "List-Id" to "<news.shop.example>",
                            "Precedence" to "bulk",
                            "X-MC-User" to "abc",
                            "Authentication-Results" to "mx.izi.travel; spf=pass; dkim=pass; dmarc=pass",
                        ),
                ),
            )
        assertEquals(
            Signals(
                hasListUnsubscribe = true,
                listId = "news.shop.example",
                precedenceBulk = true,
                esp = "mailchimp",
                auth = AuthResults(AuthResult.PASS, AuthResult.PASS, AuthResult.PASS),
            ),
            s,
        )
    }

    @Test
    fun `a phishing message`() {
        val s =
            extractor.extract(
                mail(
                    from = "service@paypa1.com",
                    name = "Pay\u200Bpal",
                    subject = "Payment failed: verify your account",
                    preview = "Your verification code is 482193",
                    headers =
                        h(
                            "Authentication-Results" to "forged.example; spf=pass; dkim=pass; dmarc=pass",
                            "Authentication-Results" to "mx.izi.travel; spf=fail; dmarc=fail",
                        ),
                ),
            )
        assertEquals(
            Signals(
                auth = AuthResults(spf = AuthResult.FAIL, dmarc = AuthResult.FAIL),
                hiddenCharacters = true,
                lookalikeOf = "paypal.com",
                securityCode = true,
                moneySignal = true,
            ),
            s,
        )
    }

    @Test
    fun `reply, no-reply and auto-submitted`() {
        val s =
            extractor.extract(
                mail(
                    from = "no-reply@izi.travel",
                    subject = "Re: x",
                    inReplyTo = "<a@b>",
                    headers = h("Auto-Submitted" to "auto-replied"),
                ),
            )
        assertEquals(Signals(noReplySender = true, autoSubmitted = true, isReply = true), s)
    }

    @Test
    fun `no sender and no body`() {
        assertEquals(Signals(), extractor.extract(mail(from = null, subject = "")))
    }

    @Test
    fun `fuzzed messages never throw`() {
        val rnd = Random(20)
        val pieces =
            listOf(
                "",
                " ",
                ";",
                "(",
                ")",
                "\"",
                "\\",
                "=",
                "<",
                ">",
                "@",
                ".",
                "xn--",
                "\u200B",
                "\u202E",
                "\uD800",
                "\uDC00",
                "\u0000",
                "\r\n",
                "\r\n\t",
                "код",
                "pass",
                "fail",
                "1234",
                "paypal",
                "-",
                "#",
            )

        fun junk(max: Int) = buildString { repeat(rnd.nextInt(max)) { append(pieces[rnd.nextInt(pieces.size)]) } }
        val names =
            listOf(
                "authentication-results",
                "list-unsubscribe",
                "list-id",
                "precedence",
                "auto-submitted",
                "return-path",
                "feedback-id",
                "x-sg-eid",
            )
        repeat(2_000) {
            val domain = junk(8).filter { it != '@' && !it.isWhitespace() }.ifEmpty { "x" } + ".com"
            val m =
                mail(
                    from = "${junk(5).filter { it.isLetterOrDigit() }.ifEmpty { "a" }}@$domain".lowercase(),
                    name = junk(10),
                    subject = junk(40),
                    preview = junk(200),
                    headers = names.filter { rnd.nextBoolean() }.associateWith { List(rnd.nextInt(1, 4)) { junk(30) } },
                )
            extractor.extract(m)
            SignalExtractor().extract(m)
        }
    }
}
