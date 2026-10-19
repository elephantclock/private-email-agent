package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.Flags
import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.model.AccountId
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit

/** The Sent backfill (T43) on the wire: EXAMINE and envelopes only, never a body, newest first, resumable. */
class SentHistoryFetcherTest {
    private val servers = TestServers()
    private val wire = ByteArrayOutputStream()
    private val now = System.currentTimeMillis()
    private lateinit var connection: ImapConnection

    @After
    fun tearDown() {
        runCatching { connection.close() }
        servers.close()
    }

    /** A plain-IMAP connection whose protocol trace is captured in [wire]. */
    private fun connect(): ImapConnection {
        val session =
            Session.getInstance(Properties()).apply {
                debug = true
                debugOut = PrintStream(wire, true)
            }
        val store = session.getStore("imap") as IMAPStore
        store.connect("localhost", servers.imapPort, TestServers.EMAIL, TestServers.PASSWORD)
        return ImapConnection(store).also { connection = it }
    }

    private fun seedSent(count: Int) {
        servers.createFolder("Sent")
        val sent =
            servers.greenMail.managers.imapHostManager
                .getFolder(servers.user, "Sent")
        // Oldest first, as a mailbox fills; one message is outside the 12-month window.
        sent.appendMessage(SyncFixtures.plain(999), Flags(), Date(now - TimeUnit.DAYS.toMillis(400)))
        repeat(count) { i ->
            sent.appendMessage(SyncFixtures.plain(i), Flags(), Date(now - TimeUnit.DAYS.toMillis((count - i) * 10L)))
        }
    }

    @Test
    fun `pages newest first with envelopes only, and resumes below the cursor`() {
        seedSent(12)
        val c = connect()
        val fetcher = SentHistoryFetcher(clock = { now }, pageSize = 5)
        val account = AccountId("acc")

        val first = fetcher.page(c, account, "Sent", null)
        val second = fetcher.page(c, account, "Sent", first.next)
        val third = fetcher.page(c, account, "Sent", second.next)

        assertEquals(12, first.total)
        assertEquals(listOf(5, 5, 2), listOf(first, second, third).map { it.headers.size })
        assertEquals(listOf(7, 2, 0), listOf(first, second, third).map { it.remaining })
        val uids = (first.headers + second.headers + third.headers).map { it.ref.uid }
        assertEquals("newest first, no repeats", uids.sortedDescending(), uids)
        assertEquals(12, uids.toSet().size)
        assertTrue(first.headers.all { it.to.isNotEmpty() && it.messageId != null })

        // Protocol lines only (Angus' own "DEBUG" lines name its settings, e.g. ignorebodystructuresize).
        val trace =
            wire
                .toString(Charsets.UTF_8)
                .uppercase()
                .lines()
                .filterNot { it.startsWith("DEBUG") }
                .joinToString("\n")
        assertTrue("read-only", "EXAMINE" in trace)
        assertFalse("never SELECT", " SELECT " in trace)
        assertTrue("ENVELOPE" in trace)
        for (body in listOf("BODY[", "BODY.PEEK[", "BODYSTRUCTURE", "RFC822.HEADER", "RFC822.TEXT", "RFC822)")) {
            assertFalse("no body or header fetch ($body)", body in trace)
        }
        // Nothing on the server changed.
        val stored =
            servers.greenMail.managers.imapHostManager
                .getFolder(servers.user, "Sent")
                .messages
        assertTrue(stored.none { it.flags.contains(Flags.Flag.SEEN) })
    }

    @Test
    fun `a cursor from another UIDVALIDITY starts again from the top`() {
        seedSent(3)
        val c = connect()
        val fetcher = SentHistoryFetcher(clock = { now }, pageSize = 2)
        val page = fetcher.page(c, AccountId("acc"), "Sent", SentHistoryCursor(uidValidity = 1, belowUid = 2))
        assertEquals(2, page.headers.size)
        assertEquals(1, page.remaining)
    }
}
