package tech.elephantclock.privatemail.core.mail.sync

import com.icegreen.greenmail.store.MailFolder
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.support.ScriptedImapServer
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.MessageFlag
import java.util.Properties

class ChangeFetcherTest {
    private val servers = TestServers()
    private val fetcher = ChangeFetcher()
    private val connections = mutableListOf<ImapConnection>()

    @After
    fun tearDown() {
        connections.forEach { runCatching { it.close() } }
        servers.close()
    }

    private fun connect(port: Int = servers.imapsPort): ImapConnection {
        val result =
            ImapClient(CertificatePins.None, TestCert.LOCALHOST.trustedBy())
                .connect(ImapConfig("localhost", port, ConnectionSecurity.SSL_TLS, TestServers.fast), TestServers.good)
        return ((result as MailResult.Ok).value).also { connections += it }
    }

    private fun inbox(count: Int): MailFolder =
        servers.greenMail.managers.imapHostManager.getInbox(servers.user).apply {
            repeat(count) { appendMessage(SyncFixtures.plain(it), Flags(), null) }
        }

    /** Another mail client, signed in read-write over plain IMAP: marks, flags, expunges. */
    private fun otherClient(block: (Folder) -> Unit) {
        val store = Session.getInstance(Properties()).getStore("imap")
        store.connect("localhost", servers.imapPort, TestServers.EMAIL, TestServers.PASSWORD)
        try {
            val folder = store.getFolder("INBOX").apply { open(Folder.READ_WRITE) }
            block(folder)
            folder.close(true)
        } finally {
            store.close()
        }
    }

    private fun Folder.byUid(uid: Long) = (this as UIDFolder).getMessageByUID(uid)

    @Test
    fun `without CONDSTORE every flag is fetched and expunged UIDs are missing from presentUids`() {
        val inbox = inbox(5)
        otherClient { f ->
            f.byUid(2).setFlag(Flags.Flag.SEEN, true)
            f.byUid(4).setFlag(Flags.Flag.FLAGGED, true)
            f.byUid(3).setFlag(Flags.Flag.DELETED, true) // expunged on close(true)
        }
        val changes = fetcher.fetch(connect(), "INBOX", KnownMessages(inbox.uidValidity, 1, 5, highestModSeq = null))!!

        assertEquals(setOf(1L, 2L, 4L, 5L), changes.presentUids)
        assertFalse(changes.incremental)
        assertNull(changes.highestModSeq)
        val flags = changes.flagUpdates.associate { it.uid to it.flags }
        assertEquals(setOf(1L, 2L, 4L, 5L), flags.keys)
        assertEquals(setOf(MessageFlag.SEEN), flags[2])
        assertEquals(setOf(MessageFlag.FLAGGED), flags[4])
        assertEquals(emptySet<MessageFlag>(), flags[1])
    }

    @Test
    fun `only the stored UID range is asked about and nothing is changed on the server`() {
        val inbox = inbox(6)
        val changes = fetcher.fetch(connect(), "INBOX", KnownMessages(inbox.uidValidity, 2, 4, null))!!
        assertEquals(setOf(2L, 3L, 4L), changes.presentUids)
        assertEquals(setOf(2L, 3L, 4L), changes.flagUpdates.map { it.uid }.toSet())
        // EXAMINE only: reading flags never marks anything \Seen.
        assertTrue(inbox.messages.none { it.isSet(Flags.Flag.SEEN) })
    }

    @Test
    fun `a UIDVALIDITY change returns null so stale UIDs are never compared`() {
        val inbox = inbox(2)
        assertNull(fetcher.fetch(connect(), "INBOX", KnownMessages(inbox.uidValidity + 1, 1, 2, null)))
    }

    @Test
    fun `with CONDSTORE only messages changed since the stored mod-sequence are fetched`() {
        ScriptedImapServer().use { server ->
            server.messages +=
                listOf(
                    ScriptedImapServer.Msg(1, "", 3),
                    ScriptedImapServer.Msg(2, "\\Seen", 12),
                    ScriptedImapServer.Msg(4, "\\Seen \\Flagged", 15),
                )
            server.highestModSeq = 15
            val known = KnownMessages(uidValidity = 7, lowestUid = 1, highestUid = 4, highestModSeq = 10)
            val changes = fetcher.fetch(connect(server.port), "INBOX", known)!!

            assertTrue(changes.incremental)
            assertEquals(15L, changes.highestModSeq)
            assertEquals(setOf(1L, 2L, 4L), changes.presentUids) // 3 was expunged
            assertEquals(
                mapOf(2L to setOf(MessageFlag.SEEN), 4L to setOf(MessageFlag.SEEN, MessageFlag.FLAGGED)),
                changes.flagUpdates.associate { it.uid to it.flags },
            )
            assertTrue(server.commands.toString(), server.commands.any { it.startsWith("EXAMINE INBOX (CONDSTORE)") })
            assertTrue(server.commands.toString(), server.commands.any { it.endsWith("(FLAGS) (CHANGEDSINCE 10)") })
            assertReadOnly(server.commands)
        }
    }

    @Test
    fun `with CONDSTORE and an unchanged mod-sequence no flags are fetched at all`() {
        ScriptedImapServer().use { server ->
            server.messages += ScriptedImapServer.Msg(1, "\\Seen", 9)
            server.highestModSeq = 9
            val changes = fetcher.fetch(connect(server.port), "INBOX", KnownMessages(7, 1, 1, highestModSeq = 9))!!
            assertTrue(changes.incremental)
            assertEquals(emptyList<FlagUpdate>(), changes.flagUpdates)
            assertFalse(server.commands.toString(), server.commands.any { "FLAGS" in it })
        }
    }

    @Test
    fun `with CONDSTORE but no stored mod-sequence it falls back to all flags and records the new one`() {
        ScriptedImapServer().use { server ->
            server.messages += ScriptedImapServer.Msg(1, "\\Seen", 4)
            server.highestModSeq = 4
            val changes = fetcher.fetch(connect(server.port), "INBOX", KnownMessages(7, 1, 1, null))!!
            assertFalse(server.commands.toString(), server.commands.any { "CHANGEDSINCE" in it })
            assertFalse(changes.incremental)
            assertEquals(4L, changes.highestModSeq)
            assertEquals(listOf(FlagUpdate(1, setOf(MessageFlag.SEEN))), changes.flagUpdates)
            assertReadOnly(server.commands)
        }
    }

    @Test
    fun `CONDSTORE without ENABLE falls back to a plain EXAMINE and all flags`() {
        ScriptedImapServer(capabilities = "IMAP4rev1 AUTH=PLAIN CONDSTORE").use { server ->
            server.messages += ScriptedImapServer.Msg(1, "\\Flagged", 4)
            server.highestModSeq = 4
            val changes = fetcher.fetch(connect(server.port), "INBOX", KnownMessages(7, 1, 1, highestModSeq = 2))!!
            assertFalse(changes.incremental)
            assertNull(changes.highestModSeq)
            assertEquals(listOf(FlagUpdate(1, setOf(MessageFlag.FLAGGED))), changes.flagUpdates)
            assertTrue(
                server.commands.toString(),
                server.commands.none { "CONDSTORE" in it || it.startsWith("ENABLE") },
            )
        }
    }

    private fun assertReadOnly(commands: List<String>) {
        val allowed =
            setOf(
                "CAPABILITY",
                "AUTHENTICATE",
                "ENABLE",
                "EXAMINE",
                "UID",
                "FETCH",
                "NOOP",
                "CLOSE",
                "UNSELECT",
                "LOGOUT",
            )
        commands.forEach { c ->
            assertTrue("unexpected command: $c", c.substringBefore(' ').uppercase() in allowed)
            val words = c.uppercase().split(' ').take(2)
            assertTrue("write command: $c", words.none { it in setOf("STORE", "EXPUNGE", "COPY", "MOVE", "SELECT") })
        }
    }
}
