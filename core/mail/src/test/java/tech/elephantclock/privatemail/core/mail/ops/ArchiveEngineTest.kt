package tech.elephantclock.privatemail.core.mail.ops

import com.icegreen.greenmail.store.MailFolder
import jakarta.mail.Flags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.support.Mailboxes
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.messageIds
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.stored
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.uids
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.VerdictAction

/**
 * Archive (T49 follow-up) against GreenMail, with MOVE; only UIDPLUS; or neither. GreenMail
 * doesn't advertise SPECIAL-USE, so its "Archive" folder is found by name (FolderMapper's
 * fallback; a `\Archive` attribute wins over a name where a server sends one).
 */
@RunWith(Parameterized::class)
class ArchiveEngineTest(
    private val mode: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes() = listOf("MOVE", "COPY+UIDPLUS", "COPY only")
    }

    private val servers = TestServers()
    private val account = AccountId("acc")
    private val undoLog = InMemoryUndoLog()
    private var connection: ImapConnection? = null

    @After
    fun tearDown() {
        runCatching { connection?.close() }
        servers.close()
    }

    private fun imap() = connection ?: Mailboxes.connect(servers).also { connection = it }

    private fun engine(): MoveEngine {
        val real = imap().capabilities
        val caps: Capabilities =
            when (mode) {
                "MOVE" -> real
                "COPY+UIDPLUS" -> real.copy(move = false)
                else -> real.copy(move = false, uidPlus = false)
            }
        return MoveEngine(imap(), undoLog, clock = { 1_000L }, capabilities = caps)
    }

    private val inbox: MailFolder get() = Mailboxes.folder(servers, "INBOX")

    private fun id(tag: String) = "<$tag@test.example>"

    /** m1..m4 in INBOX plus another client's `\Deleted` message, which we must never expunge. */
    private fun seed(): Map<String, Long> {
        val tags = listOf("m1", "m2", "m3", "m4")
        val uids = Mailboxes.seed(inbox, tags).zip(tags).associate { (uid, tag) -> tag to uid }
        val canary = Mailboxes.seed(inbox, listOf("canary"), Flags(Flags.Flag.DELETED)).single()
        return uids + ("canary" to canary)
    }

    private fun archive(
        uids: Map<String, Long>,
        vararg tags: String,
        uidValidity: Long = inbox.uidValidity,
    ) = runBlocking {
        engine().archive(
            ArchiveRequest(
                account,
                uidValidity,
                tags.map { MoveItem(uids.getValue(it), id(it), uids.getValue(it) * 10) },
            ),
        )
    }

    private fun MailFolder.visible() =
        uids().filter { !stored(it).isSet(Flags.Flag.DELETED) }.map { stored(it).mimeMessage.messageID }

    private fun assertCanaryAlive() {
        val canary = inbox.uids().map { inbox.stored(it) }.single { it.mimeMessage.messageID == id("canary") }
        assertTrue("foreign \\Deleted message survives", canary.isSet(Flags.Flag.DELETED))
    }

    @Test
    fun `archive moves exactly the requested UIDs into the existing Archive folder`() {
        servers.createFolder("Archive")
        Mailboxes.seed(Mailboxes.folder(servers, "Archive"), listOf("old-archived"))
        val uids = seed()
        val result = archive(uids, "m1", "m3") as ArchiveResult.Archived
        val report = result.report
        assertNull(report.failure)
        assertEquals("Archive", report.destination)
        val archived = Mailboxes.folder(servers, "Archive")
        assertEquals(listOf(id("old-archived"), id("m1"), id("m3")), archived.messageIds())
        assertEquals(listOf(id("m2"), id("m4")), inbox.visible())
        assertCanaryAlive()
        assertEquals(archived.uidValidity, report.destinationUidValidity)
        assertEquals(
            report.moved.map { m ->
                archived.uids().first {
                    archived.stored(it).mimeMessage.messageID ==
                        m.item.messageIdHeader
                }
            },
            report.moved.map { it.newUid },
        )
        val entries = runBlocking { undoLog.batch(account, report.batchId) }
        assertEquals(listOf(uids["m1"], uids["m3"]), entries.map { it.fromUid })
        assertTrue(
            entries.all { it.action == VerdictAction.FILE && it.toFolder == "Archive" && it.fromFolder == "INBOX" },
        )
    }

    @Test
    fun `undo brings an archived message back to the Inbox`() {
        servers.createFolder("Archive")
        val uids = seed()
        val report = (archive(uids, "m2") as ArchiveResult.Archived).report
        val undo = runBlocking { engine().undo(account, report.batchId) }
        assertNull(undo.failure)
        assertEquals(1, undo.restored.size)
        assertEquals(listOf(id("m1"), id("m2"), id("m3"), id("m4")), inbox.visible().sorted())
        assertTrue(Mailboxes.folder(servers, "Archive").visible().isEmpty())
        assertCanaryAlive()
    }

    @Test
    fun `no Archive folder - typed result, nothing created, nothing moved`() {
        val uids = seed()
        val before = imap().listFolders().map { it.path }.sorted()
        val result = archive(uids, "m1", "m2")
        assertEquals(ArchiveResult.NoArchiveFolder, result)
        assertEquals(before, imap().listFolders().map { it.path }.sorted())
        assertFalse(Mailboxes.exists(servers, "Archive"))
        assertFalse(Mailboxes.exists(servers, "Archives"))
        assertEquals(listOf(id("m1"), id("m2"), id("m3"), id("m4")), inbox.visible())
        assertCanaryAlive()
        assertTrue(runBlocking { undoLog.all() }.isEmpty())
    }

    @Test
    fun `a changed UIDVALIDITY archives nothing`() {
        servers.createFolder("Archive")
        val uids = seed()
        val report = (archive(uids, "m1", uidValidity = inbox.uidValidity + 1) as ArchiveResult.Archived).report
        assertEquals(TransferFailure.StaleUidValidity, report.failure)
        assertTrue(report.moved.isEmpty())
        assertTrue(Mailboxes.folder(servers, "Archive").messageIds().isEmpty())
        assertEquals(4, inbox.visible().size)
    }

    @Test
    fun `a UID that is gone is reported missing and its neighbours stay`() {
        servers.createFolder("Archive")
        val uids = seed() + ("ghost" to 999L)
        val report = (archive(uids, "m4", "ghost") as ArchiveResult.Archived).report
        assertEquals(listOf(999L), report.missing)
        assertEquals(listOf(id("m4")), Mailboxes.folder(servers, "Archive").messageIds())
        assertEquals(listOf(id("m1"), id("m2"), id("m3")), inbox.visible())
        assertCanaryAlive()
    }
}
