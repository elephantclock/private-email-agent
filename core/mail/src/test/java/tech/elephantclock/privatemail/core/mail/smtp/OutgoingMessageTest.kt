package tech.elephantclock.privatemail.core.mail.smtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef

class OutgoingMessageTest {
    private val me = Address.of("me@izi.example", "Me")
    private val bob = Address.of("bob@example.org")

    private fun reply(parent: ReplyParent?) = OutgoingMessage(me, "Re: Hi", "Thanks", to = listOf(bob), parent = parent)

    @Test
    fun `reply references are the parent's references plus its Message-ID`() {
        val m = reply(ReplyParent("<p@x>", references = listOf("<root@x>", "<mid@x>"), inReplyTo = "<mid@x>"))
        assertEquals("<p@x>", m.inReplyTo)
        assertEquals(listOf("<root@x>", "<mid@x>", "<p@x>"), m.references)
    }

    @Test
    fun `without references the parent's In-Reply-To starts the chain`() {
        assertEquals(listOf("<a@x>", "<p@x>"), reply(ReplyParent("<p@x>", inReplyTo = "<a@x>")).references)
        assertEquals(listOf("<p@x>"), reply(ReplyParent("<p@x>")).references)
    }

    @Test
    fun `duplicates are dropped and long chains keep the root and the newest ids`() {
        assertEquals(listOf("<a@x>", "<p@x>"), reply(ReplyParent("<p@x>", listOf("<a@x>", "<p@x>"))).references)
        val long = (1..40).map { "<m$it@x>" }
        val refs = reply(ReplyParent("<p@x>", long)).references
        assertEquals(OutgoingMessage.MAX_REFERENCES, refs.size)
        assertEquals("<m1@x>", refs.first())
        assertEquals("<p@x>", refs.last())
        assertEquals("<m23@x>", refs[1])
    }

    @Test
    fun `a new message has no threading headers`() {
        val m = reply(null)
        assertNull(m.inReplyTo)
        assertTrue(m.references.isEmpty())
    }

    @Test
    fun `parent from a synced header cleans up the ids`() {
        val header =
            MessageHeader(
                ref = MessageRef(AccountId("a"), "INBOX", 1, 1),
                messageId = "<p@x>",
                from = bob,
                subject = "Hi",
                sentAtMillis = null,
                receivedAtMillis = 0,
                inReplyTo = "<a@x> (comment)",
                references = listOf("<root@x>", "garbage", "<a@x>"),
            )
        assertEquals(ReplyParent("<p@x>", listOf("<root@x>", "<a@x>"), "<a@x>"), ReplyParent.of(header))
        assertNull(ReplyParent.of(header.copy(messageId = null)))
    }

    @Test
    fun `Message-IDs use the sender's domain, never the device`() {
        val id = reply(null).messageId
        assertTrue(id, id.matches(Regex("<[0-9a-f-]{36}@izi\\.example>")))
        assertNotEquals(id, reply(null).messageId)
    }

    @Test
    fun `header injection through the subject or an address is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            OutgoingMessage(me, "Hi\r\nBcc: evil@x.example", "t", to = listOf(bob))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OutgoingMessage(me, "Hi", "t", to = listOf(Address("bob@x.example\r\nbcc:evil@x.example")))
        }
    }

    @Test
    fun `the fingerprint changes with every field`() {
        val base = reply(ReplyParent("<p@x>"))
        val variants =
            listOf(
                base.copy(subject = "Re: Hi!"),
                base.copy(text = "Thanks."),
                base.copy(to = listOf(Address.of("eve@example.org"))),
                base.copy(cc = listOf(bob)),
                base.copy(bcc = listOf(bob)),
                base.copy(from = Address.of("me@izi.example", "Other name")),
                base.copy(parent = ReplyParent("<q@x>")),
                base.copy(messageId = "<other@izi.example>"),
            )
        variants.forEach { assertNotEquals(it.toString(), base.fingerprint(), it.fingerprint()) }
        assertEquals(base.fingerprint(), base.copy().fingerprint())
    }

    @Test
    fun `Gmail and Microsoft keep their own Sent copy`() {
        val plain = Capabilities.parse("IMAP4rev1 MOVE")
        assertTrue(SentPolicy.providerSavesSent("gmail", "smtp.gmail.com", plain))
        assertTrue(SentPolicy.providerSavesSent(null, "SMTP.Office365.com", plain))
        assertTrue(SentPolicy.providerSavesSent(null, "mail.example.com", Capabilities.parse("X-GM-EXT-1")))
        assertTrue(!SentPolicy.providerSavesSent("fastmail", "smtp.fastmail.com", plain))
        assertTrue(!SentPolicy.providerSavesSent(null, "mail.izi.travel", null))
    }
}
