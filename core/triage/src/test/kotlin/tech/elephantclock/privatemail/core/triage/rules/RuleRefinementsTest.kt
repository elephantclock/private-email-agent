package tech.elephantclock.privatemail.core.triage.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.TestMail.h
import tech.elephantclock.privatemail.core.triage.TestMail.mail

/** Rule changes made for cases the golden corpus (T23) found wrong. */
class RuleRefinementsTest {
    private val engine = RuleCases.engine

    private fun final(m: MailItem): Decision.Final = engine.decide(m) as Decision.Final

    private fun verdict(m: MailItem): Verdict = final(m).verdict

    private val authFail =
        h("Authentication-Results" to "mx.izi.travel; spf=fail smtp.mailfrom=x; dkim=none; dmarc=fail")
    private val esp = h("List-Unsubscribe" to "<https://x.example/u>", "X-Mailgun-Sid" to "abc")

    @Test
    fun `a forged VIP stays in Priority without an alert`() {
        val forged = final(mail(from = "boss@company.example", headers = authFail))
        assertEquals("vip", forged.rule)
        assertEquals(VerdictAction.KEEP, forged.verdict.action)
        assertEquals(Importance.NORMAL, forged.verdict.importance)
        assertEquals("VIP sender, but the message failed authentication", forged.reason)
        assertEquals(Importance.URGENT, verdict(mail(from = "boss@company.example")).importance)
    }

    @Test
    fun `a learned VIP rule with failed authentication doesn't alert either`() {
        val forged = final(mail(from = "friend@vip.example", headers = authFail))
        assertEquals("learned:5", forged.rule)
        assertEquals(Importance.NORMAL, forged.verdict.importance)
        assertEquals("Your rule: friend@vip.example is a VIP, but the message failed authentication", forged.reason)
    }

    @Test
    fun `a promotion mentioning delivery or flights is still marketing`() {
        val promo = mail(from = "offers@megastore.example", subject = "50% off and free delivery", headers = esp)
        assertEquals("bulk.marketing", final(promo).rule)
        val flights = mail(from = "deals@flycheap.example", subject = "Flights to Lisbon from 29 EUR", headers = esp)
        assertEquals("bulk.marketing", final(flights).rule)
        val shipped = mail(from = "news@shop.example", subject = "Your order #123 has shipped", headers = esp)
        assertNotEquals(VerdictAction.DISCARD, verdict(shipped).action)
    }

    @Test
    fun `an order confirmation that mentions the total goes to Orders`() {
        val order =
            mail(
                from = "no-reply@shop.example",
                subject = "Order confirmation #A-1",
                preview = "Total charged: 18.00 EUR",
            )
        assertEquals(FolderRole.ORDERS, verdict(order).target.role)
        val invoice = mail(from = "no-reply@shop.example", subject = "Invoice for your order #A-1")
        assertEquals(FolderRole.MONEY, verdict(invoice).target.role)
    }

    @Test
    fun `a duplicate charge is Money with an alert`() {
        val alert =
            mail(from = "alerts-noreply@bank.example", subject = "Possible duplicate payment: 49.00 EUR charged twice")
        assertEquals(FolderRole.MONEY, verdict(alert).target.role)
        assertEquals(Importance.HIGH, verdict(alert).importance)
        val receipt = mail(from = "no-reply@bank.example", subject = "Payment received")
        assertEquals(Importance.NORMAL, verdict(receipt).importance)
    }

    @Test
    fun `a brand name split by a hidden character is phishing`() {
        val disguised = final(mail(from = "notice@secure-center.example", name = "Pay​Pal Service"))
        assertEquals("phishing.disguised-brand", disguised.rule)
        assertEquals("Hidden characters disguise the name paypal", disguised.reason)
        val inSubject = final(mail(from = "x@notice.example", subject = "Your Net​flix account"))
        assertEquals("phishing.disguised-brand", inSubject.rule)
        // The brand itself, and hidden characters that split no brand name, are not this rule.
        assertNotEquals(
            "phishing.disguised-brand",
            engine.decide(mail(from = "service@paypal.com", name = "Pay​Pal")).rule,
        )
        assertNotEquals(
            "phishing.disguised-brand",
            engine.decide(mail(from = "x@a.example", name = "Hello​ PayPal")).rule,
        )
        // Someone the user writes to is never junked.
        assertNotEquals(
            VerdictAction.JUNK,
            (engine.decide(mail(from = "anna@example.org", name = "Pay​Pal")) as? Decision.Final)?.verdict?.action,
        )
    }
}
