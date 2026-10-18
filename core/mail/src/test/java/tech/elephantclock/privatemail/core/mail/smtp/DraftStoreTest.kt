package tech.elephantclock.privatemail.core.mail.smtp

import jakarta.mail.Flags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.support.Mailboxes
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.messageIds
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.stored
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.uids
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.model.Address

class DraftStoreTest {
    private val servers = TestServers()
    private val store = DraftStore(clock = { 1_000L })
    private var connection: ImapConnection? = null

    @After
    fun tearDown() {
        runCatching { connection?.close() }
        servers.close()
    }

    private fun imap() = connection ?: Mailboxes.connect(servers).also { connection = it }

    private val noUidPlus get() = imap().capabilities.copy(uidPlus = false)

    private val draft =
        OutgoingMessage(
            from = Address.of(TestServers.EMAIL),
            subject = "Plan",
            text = "first version",
            to = listOf(Address.of("bob@example.org")),
        )

    /** Drafts with a neighbouring draft and a message another client marked \Deleted (must survive). */
    private fun draftsWithNeighbours(): Pair<Long, Long> {
        val drafts = Mailboxes.folder(servers, "Drafts")
        val neighbour = Mailboxes.seed(drafts, listOf("neighbour"), Flags(Flags.Flag.DRAFT)).single()
        val canary = Mailboxes.seed(drafts, listOf("canary"), Flags(Flags.Flag.DELETED)).single()
        return neighbour to canary
    }

    @Test
    fun `a draft is saved with Draft and Seen, creating Drafts when missing`() {
        val saved = store.save(imap(), draft)
        assertEquals(OldDraft.NONE, saved.previous)
        assertEquals("Drafts", saved.stored.folderPath)
        val drafts = Mailboxes.folder(servers, "Drafts")
        val stored = drafts.stored(requireNotNull(saved.stored.uid))
        assertTrue(stored.isSet(Flags.Flag.DRAFT))
        assertTrue(stored.isSet(Flags.Flag.SEEN))
        assertEquals(draft.messageId, stored.mimeMessage.messageID)
        assertEquals(drafts.uidValidity, saved.stored.uidValidity)
    }

    @Test
    fun `replacing a draft removes exactly the old one`() {
        val (neighbour, canary) = draftsWithNeighbours()
        val first = store.save(imap(), draft)
        val second = store.save(imap(), draft.copy(text = "second version"), replacing = first.stored)
        assertEquals(OldDraft.REMOVED, second.previous)
        val drafts = Mailboxes.folder(servers, "Drafts")
        assertEquals(listOf(neighbour, canary, second.stored.uid), drafts.uids())
        assertTrue("foreign \\Deleted message survives", drafts.stored(canary).isSet(Flags.Flag.DELETED))
        assertEquals("second version", (drafts.stored(second.stored.uid!!).mimeMessage.content as String).trim())
    }

    @Test
    fun `without UIDPLUS the old draft is only marked, nothing is expunged`() {
        val (neighbour, canary) = draftsWithNeighbours()
        val first = store.save(imap(), draft, capabilities = noUidPlus)
        // Found by Message-ID among the new UIDs, since APPENDUID is ignored.
        assertNotNull(first.stored.uid)
        val second = store.save(imap(), draft.copy(text = "v2"), first.stored, capabilities = noUidPlus)
        assertEquals(OldDraft.MARKED_DELETED, second.previous)
        val drafts = Mailboxes.folder(servers, "Drafts")
        assertEquals(listOf(neighbour, canary, first.stored.uid, second.stored.uid), drafts.uids())
        assertTrue(drafts.stored(first.stored.uid!!).isSet(Flags.Flag.DELETED))
        assertTrue(!drafts.stored(second.stored.uid!!).isSet(Flags.Flag.DELETED))
        assertTrue(!drafts.stored(neighbour).isSet(Flags.Flag.DELETED))
    }

    @Test
    fun `a stale reference is never deleted`() {
        val first = store.save(imap(), draft)
        val stale = first.stored.copy(uidValidity = first.stored.uidValidity + 1)
        val second = store.save(imap(), draft.copy(text = "v2"), replacing = stale)
        assertEquals(OldDraft.KEPT, second.previous)
        assertEquals(2, Mailboxes.folder(servers, "Drafts").uids().size)
    }

    @Test
    fun `a UID that now holds another message is never deleted`() {
        val (neighbour, _) = draftsWithNeighbours()
        val first = store.save(imap(), draft)
        // A reference claiming the neighbour's UID but our Message-ID: the server says it's someone else's.
        val wrong = first.stored.copy(uid = neighbour)
        assertEquals(OldDraft.KEPT, store.delete(imap(), wrong))
        assertTrue(neighbour in Mailboxes.folder(servers, "Drafts").uids())
    }

    @Test
    fun `a draft that lost its Draft flag is kept`() {
        val first = store.save(imap(), draft)
        Mailboxes.folder(servers, "Drafts").stored(first.stored.uid!!).setFlag(Flags.Flag.DRAFT, false)
        assertEquals(OldDraft.KEPT, store.delete(imap(), first.stored))
        assertEquals(listOf(draft.messageId), Mailboxes.folder(servers, "Drafts").messageIds())
    }

    @Test
    fun `deleting a draft that is already gone says so`() {
        val first = store.save(imap(), draft)
        assertEquals(OldDraft.REMOVED, store.delete(imap(), first.stored))
        assertEquals(OldDraft.GONE, store.delete(imap(), first.stored))
    }
}
