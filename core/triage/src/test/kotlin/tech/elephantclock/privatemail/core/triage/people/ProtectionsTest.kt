package tech.elephantclock.privatemail.core.triage.people

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.TestMail.mail

class ProtectionsTest {
    private val index =
        RelationshipIndex
            .Builder()
            .addSentStats("anna@example.org", 2, 1)
            .addOwnMessageId("<mine@izi.travel>")
            .addVip("boss@company.example")
            .build()
    private val protections = Protections(index, contacts = { it == "carol@friends.example" })

    @Test
    fun `each protection`() {
        assertEquals(Protection(sentTo = true), protections.evaluate(mail(from = "Anna@Example.org")))
        assertEquals(Protection(contact = true), protections.evaluate(mail(from = "carol@friends.example")))
        assertEquals(Protection(contact = true), protections.evaluate(mail(from = "carol+news@friends.example")))
        assertEquals(
            Protection(repliedThread = true),
            protections.evaluate(mail(references = listOf("<mine@izi.travel>"))),
        )
        assertEquals(Protection(vip = true), protections.evaluate(mail(from = "boss@company.example")))
        assertEquals(
            Protection(securityCode = true),
            protections.evaluate(mail(subject = "Your verification code is 123456")),
        )
        assertEquals(Protection(money = true), protections.evaluate(mail(subject = "Invoice #12")))
        assertEquals(Protection.NONE, protections.evaluate(mail(from = "stranger@example.org", subject = "Hi")))
        assertEquals(Protection.NONE, protections.evaluate(mail(from = null)))
    }

    @Test
    fun `preview keywords count`() {
        assertTrue(protections.blocksDiscard(mail(subject = "Hello", preview = "Ваш код подтверждения: 4821")))
        assertTrue(protections.blocksDiscard(mail(subject = "Hello", preview = "Счёт на оплату во вложении")))
    }

    @Test
    fun `given signals are used instead of re-scanning`() {
        val m = mail(subject = "Invoice #12")
        assertFalse(protections.blocksDiscard(m, Signals()))
        assertTrue(protections.blocksDiscard(mail(subject = "x"), Signals(securityCode = true)))
    }

    @Test
    fun `without contacts, or when the lookup fails, only contacts are lost`() {
        val noContacts = Protections(index)
        assertFalse(noContacts.blocksDiscard(mail(from = "carol@friends.example")))
        val failing = Protections(index) { error("permission revoked") }
        assertFalse(failing.blocksDiscard(mail(from = "carol@friends.example")))
        assertTrue(failing.blocksDiscard(mail(from = "anna@example.org")))
    }

    @Test
    fun `relationship blocks discard and junk, content blocks only discard`() {
        val person = Protection(sentTo = true)
        val code = Protection(securityCode = true)
        assertTrue(person.forbids(VerdictAction.DISCARD))
        assertTrue(person.forbids(VerdictAction.JUNK))
        assertTrue(code.forbids(VerdictAction.DISCARD))
        assertFalse(code.forbids(VerdictAction.JUNK))
        assertFalse(person.forbids(VerdictAction.FILE))
        assertFalse(Protection.NONE.forbids(VerdictAction.DISCARD))
    }

    @Test
    fun `enforce rewrites a forbidden verdict and explains it`() {
        val discard = Verdict.discard(VerdictSource.AI, "Newsletter", confidence = 0.7f)
        val kept = Protection(sentTo = true, money = true).enforce(discard)
        assertEquals(
            Verdict.keep(
                Importance.NORMAL,
                VerdictSource.AI,
                "Kept: you have written to this sender, payment or invoice (would have been discard: Newsletter)",
                0.7f,
            ),
            kept,
        )
        val junk = Verdict.junk(VerdictSource.RULE, "Look-alike domain")
        assertSame(junk, Protection(money = true).enforce(junk))
        val file = Verdict.file(FolderTarget(FolderRole.MONEY), Importance.NORMAL, VerdictSource.RULE, "Invoice")
        assertSame(file, Protection(vip = true).enforce(file))
    }

    @Test
    fun `reasons are stable`() {
        assertEquals(
            listOf(
                "VIP",
                "you have written to this sender",
                "you replied in this thread",
                "sender is in your contacts",
                "security code",
                "payment or invoice",
            ),
            Protection(true, true, true, true, true, true).reasons,
        )
    }
}
