package tech.elephantclock.privatemail.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class InvariantsTest {
    @Test
    fun `each action accepts exactly its folder roles`() {
        val allowed =
            VerdictAction.entries.associateWith { action -> FolderRole.entries.filter(action::allows).toSet() }
        assertEquals(setOf(FolderRole.PRIORITY), allowed[VerdictAction.KEEP])
        assertEquals(setOf(FolderRole.DISCARDED), allowed[VerdictAction.DISCARD])
        assertEquals(setOf(FolderRole.JUNK), allowed[VerdictAction.JUNK])
        assertEquals(
            setOf(
                FolderRole.PEOPLE,
                FolderRole.MONEY,
                FolderRole.WORK,
                FolderRole.ORDERS,
                FolderRole.REPORTS,
                FolderRole.CUSTOM,
            ),
            allowed[VerdictAction.FILE],
        )
    }

    @Test
    fun `verdict constructor enforces action, importance, reason and confidence`() {
        val ok = Fixtures.verdict
        assertThrows<IllegalArgumentException> { ok.copy(action = VerdictAction.DISCARD) }
        assertThrows<IllegalArgumentException> { ok.copy(action = VerdictAction.KEEP) }
        assertThrows<IllegalArgumentException> { ok.copy(target = FolderTarget.Priority) }
        assertThrows<IllegalArgumentException> { ok.copy(reason = " ") }
        assertThrows<IllegalArgumentException> { ok.copy(confidence = 1.1f) }
        assertThrows<IllegalArgumentException> { ok.copy(confidence = -0.1f) }
        assertThrows<IllegalArgumentException> {
            Verdict.discard(VerdictSource.RULE, "newsletter").copy(importance = Importance.URGENT)
        }
        assertDoesNotThrow { Verdict.discard(VerdictSource.RULE, "newsletter") }
        assertDoesNotThrow { Verdict.junk(VerdictSource.RULE, "DMARC fail + look-alike domain") }
        assertDoesNotThrow { Verdict.keep(Importance.URGENT, VerdictSource.AI, "deadline Monday", 0.9f) }
    }

    @Test
    fun `custom folder target needs a name and only custom may have one`() {
        assertThrows<IllegalArgumentException> { FolderTarget(FolderRole.CUSTOM) }
        assertThrows<IllegalArgumentException> { FolderTarget(FolderRole.MONEY, "Money") }
        assertThrows<IllegalArgumentException> { FolderTarget.custom(" ") }
    }

    @Test
    fun `addresses are normalised and split`() {
        val a = Address.of("  Billing@Shop.Example ", " ")
        assertEquals("billing@shop.example", a.email)
        assertEquals(null, a.name)
        assertEquals("billing", a.localPart)
        assertEquals("shop.example", a.domain)
        assertThrows<IllegalArgumentException> { Address("Mixed@Case.com") }
        assertThrows<IllegalArgumentException> { Address.of("no-at-sign") }
    }

    @Test
    fun `header lookup is case-insensitive and names must be lower-case`() {
        assertEquals("<mailto:u@shop.example>", Fixtures.header.header("List-Unsubscribe"))
        assertTrue(Fixtures.header.isReply)
        assertFalse(Fixtures.header.copy(inReplyTo = null, references = emptyList()).isReply)
        assertThrows<IllegalArgumentException> { Fixtures.header.copy(headers = mapOf("List-Id" to listOf("x"))) }
    }

    @Test
    fun `server config, refs and folders reject impossible values`() {
        assertThrows<IllegalArgumentException> { ServerConfig("imap.example.com", 0, ConnectionSecurity.SSL_TLS) }
        assertThrows<IllegalArgumentException> { ServerConfig(" ", 993, ConnectionSecurity.SSL_TLS) }
        assertThrows<IllegalArgumentException> { Fixtures.ref.copy(uid = 0) }
        assertThrows<IllegalArgumentException> { Fixtures.folder.copy(uidValidity = 0) }
        assertThrows<IllegalArgumentException> { AccountId("") }
    }

    @Test
    fun `learned rules match sender or domain including subdomains, scoped by account`() {
        val domainRule = Fixtures.rule
        val other = AccountId("acc-2")
        assertTrue(domainRule.appliesTo(other, Address.of("reports@appfigures.com")))
        assertTrue(domainRule.appliesTo(other, Address.of("x@mail.appfigures.com")))
        assertFalse(domainRule.appliesTo(other, Address.of("x@notappfigures.com")))

        val senderRule =
            domainRule.copy(accountId = Fixtures.accountId, matcher = RuleMatcher.Sender("us@informap.ae"))
        assertTrue(senderRule.appliesTo(Fixtures.accountId, Address.of("US@informap.ae")))
        assertFalse(senderRule.appliesTo(other, Address.of("us@informap.ae")))
        assertFalse(senderRule.appliesTo(Fixtures.accountId, Address.of("them@informap.ae")))
    }

    @Test
    fun `rules obey the same action-folder pairing and VIP rules keep`() {
        assertThrows<IllegalArgumentException> { Fixtures.rule.copy(action = VerdictAction.DISCARD) }
        assertThrows<IllegalArgumentException> { Fixtures.rule.copy(vip = true) }
        assertThrows<IllegalArgumentException> { RuleMatcher.Domain("@bad.com") }
        assertThrows<IllegalArgumentException> { RuleMatcher.Sender("Upper@case.com") }
    }

    @Test
    fun `derived flags`() {
        assertTrue(Importance.URGENT.notifies && Importance.HIGH.notifies)
        assertFalse(Importance.NORMAL.notifies || Importance.LOW.notifies)
        assertTrue(FolderRole.DISCARDED.isDiscard && FolderRole.JUNK.isDiscard)
        assertTrue(Fixtures.signals.isBulk)
        assertFalse(Signals().isBulk)
        assertTrue(Fixtures.signals.auth.anyFail)
        assertFalse(AuthResults().anyFail)
    }
}
