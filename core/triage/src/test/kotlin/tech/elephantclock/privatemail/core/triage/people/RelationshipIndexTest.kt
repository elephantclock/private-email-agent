package tech.elephantclock.privatemail.core.triage.people

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.triage.TestMail
import tech.elephantclock.privatemail.core.triage.TestMail.mail

class RelationshipIndexTest {
    private fun sent(
        vararg to: String,
        at: Long = 1_000,
        id: String? = null,
        cc: List<String> = emptyList(),
    ) = mail(from = TestMail.me.email, to = to.map { Address.of(it) }, messageId = id).header.let { h ->
        h.copy(sentAtMillis = at, cc = cc.map { Address.of(it) })
    }

    @Test
    fun `counts recipients with the last date`() {
        val index =
            RelationshipIndex
                .Builder()
                .addSent(sent("anna@example.org", at = 1_000))
                .addSent(sent("Anna@Example.org", "bob@example.org", at = 3_000))
                .addSent(sent("carol@other.example", at = 2_000, cc = listOf("anna@example.org")))
                .build()
        assertEquals(RelationshipIndex.SentStats(3, 3_000), index.sentStats(Address.of("anna@example.org")))
        assertEquals(RelationshipIndex.SentStats(1, 3_000), index.sentStats(Address.of("bob@example.org")))
        assertTrue(index.hasSentTo(Address.of("carol@other.example")))
        assertFalse(index.hasSentTo(Address.of("dave@example.org")))
        assertEquals(3, index.sentToCount)
    }

    @Test
    fun `the same recipient twice in one message counts once`() {
        val index = RelationshipIndex.Builder().addSent(sent("a@x.example", cc = listOf("A@x.example"))).build()
        assertEquals(1, index.sentStats(Address.of("a@x.example"))?.count)
    }

    @Test
    fun `plus tags are ignored both ways`() {
        val index = RelationshipIndex.Builder().addSent(sent("anna+work@example.org")).build()
        assertTrue(index.hasSentTo(Address.of("anna@example.org")))
        assertTrue(index.hasSentTo(Address.of("anna+other@example.org")))
        assertFalse(index.hasSentTo(Address.of("annabel@example.org")))
    }

    @Test
    fun `the user's own addresses never count as sent-to`() {
        val index =
            RelationshipIndex
                .Builder(
                    listOf(TestMail.me),
                ).addSent(sent("me@izi.travel", "anna@example.org"))
                .build()
        assertFalse(index.hasSentTo(TestMail.me))
        assertTrue(index.hasSentTo(Address.of("anna@example.org")))
    }

    @Test
    fun `replied threads by in-reply-to and references`() {
        val index =
            RelationshipIndex
                .Builder()
                .addSent(sent("a@x.example", id = "<Mine-1@izi.travel>"))
                .addOwnMessageId(" <mine-2@izi.travel> ")
                .build()
        assertTrue(index.repliedInThread(mail(inReplyTo = "<mine-1@izi.travel>").header))
        assertTrue(index.repliedInThread(mail(inReplyTo = "<other@x> <mine-2@izi.travel>").header))
        assertTrue(index.repliedInThread(mail(references = listOf("<root@x>", "<MINE-2@izi.travel>")).header))
        assertFalse(index.repliedInThread(mail(inReplyTo = "<root@x>", references = listOf("<a@b>")).header))
        assertFalse(index.repliedInThread(mail(inReplyTo = "<>", references = listOf("", "  ")).header))
        assertFalse(RelationshipIndex.EMPTY.repliedInThread(mail(inReplyTo = "<mine-1@izi.travel>").header))
    }

    @Test
    fun `vip list`() {
        val index =
            RelationshipIndex
                .Builder()
                .addVip("Boss@Company.example")
                .addVip("not-an-address")
                .build()
        assertTrue(index.isVip(Address.of("boss@company.example")))
        assertFalse(index.isVip(Address.of("other@company.example")))
    }

    @Test
    fun `frequent registrable domains, most used first`() {
        val index =
            RelationshipIndex
                .Builder()
                .addSentStats("a@mail.izi.travel", 3, 1)
                .addSentStats("b@izi.travel", 2, 1)
                .addSentStats("c@shop.example", 2, 1)
                .addSentStats("d@once.example", 1, 1)
                .addSentStats("ignored@zero.example", 0, 1)
                .build()
        assertEquals(listOf("izi.travel", "shop.example"), index.frequentDomains())
        assertEquals(listOf("izi.travel"), index.frequentDomains(limit = 1))
        assertEquals(listOf("izi.travel", "shop.example", "once.example"), index.frequentDomains(minCount = 1))
        assertNull(index.sentStats(Address.of("ignored@zero.example")))
    }
}
