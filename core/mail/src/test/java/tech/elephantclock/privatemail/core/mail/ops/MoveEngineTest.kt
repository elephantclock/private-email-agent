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
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.byMessageId
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.messageIds
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.stored
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.uids
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.VerdictAction
import java.util.concurrent.TimeUnit

/**
 * Against GreenMail (which has MOVE and UIDPLUS), with the engine told the server has
 * MOVE; only UIDPLUS (COPY + UID EXPUNGE); or neither (COPY + mark, no expunge).
 */
@RunWith(Parameterized::class)
class MoveEngineTest(
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
    private var now = TimeUnit.DAYS.toMillis(100)
    private var connection: ImapConnection? = null

    @After
    fun tearDown() {
        runCatching { connection?.close() }
        servers.close()
    }

    private fun imap() = connection ?: Mailboxes.connect(servers).also { connection = it }

    private val expunges = mode != "COPY only"

    private fun engine(): MoveEngine {
        val real = imap().capabilities
        val caps: Capabilities =
            when (mode) {
                "MOVE" -> real
                "COPY+UIDPLUS" -> real.copy(move = false)
                else -> real.copy(move = false, uidPlus = false)
            }
        return MoveEngine(imap(), undoLog, clock = { now }, capabilities = caps)
    }

    private val inbox: MailFolder get() = Mailboxes.folder(servers, "INBOX")

    /** m1..m[n] in INBOX, plus a message another client marked \Deleted (must never be expunged by us). */
    private fun seed(n: Int = 5): Map<String, Long> {
        val tags = (1..n).map { "m$it" }
        val uids = Mailboxes.seed(inbox, tags).zip(tags).associate { (uid, tag) -> tag to uid }
        val canary = Mailboxes.seed(inbox, listOf("canary"), Flags(Flags.Flag.DELETED)).single()
        return uids + ("canary" to canary)
    }

    private fun id(tag: String) = "<$tag@test.example>"

    private fun item(
        uids: Map<String, Long>,
        tag: String,
    ) = MoveItem(uids.getValue(tag), id(tag), localId = uids.getValue(tag) * 10)

    private fun discard(
        uids: Map<String, Long>,
        vararg tags: String,
    ) = runBlocking {
        engine().move(
            MoveRequest(
                account,
                "INBOX",
                inbox.uidValidity,
                tags.map {
                    item(uids, it)
                },
                FolderTarget.Discarded,
                VerdictAction.DISCARD,
            ),
        )
    }

    /** INBOX message ids not marked \Deleted (what a user sees), and the ones marked. */
    private fun MailFolder.visible() =
        uids()
            .filter {
                !stored(it).isSet(Flags.Flag.DELETED)
            }.map { stored(it).mimeMessage.messageID }

    private fun MailFolder.marked() =
        uids()
            .filter {
                stored(it).isSet(Flags.Flag.DELETED)
            }.map { stored(it).mimeMessage.messageID }

    private fun assertCanaryAlive() = assertTrue("foreign \\Deleted message survives", id("canary") in inbox.marked())

    @Test
    fun `discard moves exactly the requested UIDs into a new Discarded folder`() {
        val uids = seed()
        val report = discard(uids, "m2", "m4")
        assertNull(report.failure)
        assertEquals("Discarded", report.destination)
        val discarded = Mailboxes.folder(servers, "Discarded")
        assertEquals(listOf(id("m2"), id("m4")), discarded.messageIds())
        assertEquals(listOf(id("m1"), id("m3"), id("m5")), inbox.visible())
        assertCanaryAlive()
        if (expunges) {
            assertEquals(listOf(id("canary")), inbox.marked())
        } else {
            assertEquals(listOf(id("m2"), id("m4"), id("canary")), inbox.marked())
        }
        assertEquals(!expunges, report.markedOnly)
        // New UIDs are known (COPYUID, or the Message-ID lookup when MOVE sends none).
        assertEquals(
            report.moved.map { discarded.byMessageId(it.item.messageIdHeader!!)!!.uid },
            report.moved.map { it.newUid },
        )
    }

    @Test
    fun `every batch is in the undo log with the destination's UIDVALIDITY`() {
        val uids = seed()
        val report = discard(uids, "m1", "m3")
        val entries = runBlocking { undoLog.batch(account, report.batchId) }
        val discarded = Mailboxes.folder(servers, "Discarded")
        assertEquals(listOf(uids["m1"], uids["m3"]), entries.map { it.fromUid })
        assertTrue(entries.all { it.toUidValidity == discarded.uidValidity && it.toFolder == "Discarded" })
        assertEquals(discarded.uids(), entries.map { it.toUid })
        assertEquals(listOf(uids.getValue("m1") * 10, uids.getValue("m3") * 10), entries.map { it.localId })
        assertTrue(entries.all { it.action == VerdictAction.DISCARD && it.createdAtMillis == now })
    }

    @Test
    fun `filing creates the value folder`() {
        val uids = seed()
        val report =
            runBlocking {
                engine().move(
                    MoveRequest(
                        account,
                        "INBOX",
                        inbox.uidValidity,
                        listOf(item(uids, "m5")),
                        FolderTarget(FolderRole.WORK),
                        VerdictAction.FILE,
                    ),
                )
            }
        assertEquals("Work", report.destination)
        assertEquals(listOf(id("m5")), Mailboxes.folder(servers, "Work").messageIds())
        assertCanaryAlive()
    }

    @Test
    fun `undo puts the batch back and touches nothing else`() {
        val uids = seed()
        Mailboxes.seed(Mailboxes.folder(servers, "Discarded"), listOf("older-discard"))
        val report = discard(uids, "m2", "m4")
        val undo = runBlocking { engine().undo(account, report.batchId) }
        assertNull(undo.failure)
        assertEquals(2, undo.restored.size)
        assertTrue(undo.notFound.isEmpty())
        assertEquals(listOf(id("m1"), id("m2"), id("m3"), id("m4"), id("m5")), inbox.visible().sorted())
        val discarded = Mailboxes.folder(servers, "Discarded")
        assertEquals(listOf(id("older-discard")), discarded.visible())
        assertCanaryAlive()
        assertTrue(runBlocking { undoLog.batch(account, report.batchId) }.isEmpty())
        // The reported UIDs are where the messages are now.
        undo.restored.forEach { r ->
            assertEquals(r.entry.messageIdHeader, inbox.stored(r.uid!!).mimeMessage.messageID)
        }
    }

    @Test
    fun `missing UIDs are reported, never guessed`() {
        val uids = seed()
        val request =
            MoveRequest(
                account,
                "INBOX",
                inbox.uidValidity,
                listOf(item(uids, "m1"), MoveItem(9_999, "<gone@test.example>")),
                FolderTarget.Discarded,
                VerdictAction.DISCARD,
            )
        val report = runBlocking { engine().move(request) }
        assertEquals(listOf(9_999L), report.missing)
        assertEquals(listOf(id("m1")), report.moved.map { it.item.messageIdHeader })
    }

    @Test
    fun `a changed UIDVALIDITY moves nothing`() {
        val uids = seed()
        val request =
            MoveRequest(
                account,
                "INBOX",
                inbox.uidValidity + 1,
                listOf(item(uids, "m1")),
                FolderTarget.Discarded,
                VerdictAction.DISCARD,
            )
        val report = runBlocking { engine().move(request) }
        assertEquals(TransferFailure.StaleUidValidity, report.failure)
        assertTrue(report.moved.isEmpty())
        assertEquals(listOf(id("m1"), id("m2"), id("m3"), id("m4"), id("m5")), inbox.visible())
        assertTrue(runBlocking { undoLog.all() }.isEmpty())
    }

    @Test
    fun `250 messages go in batches, the neighbours stay`() {
        val tags = (1..260).map { "b$it" }
        val uids = Mailboxes.seed(inbox, tags).zip(tags).associate { (uid, tag) -> tag to uid }
        val chosen = tags.filterIndexed { i, _ -> i % 26 != 0 }
        assertEquals(250, chosen.size)
        val report = discard(uids, *chosen.toTypedArray())
        assertEquals(250, report.moved.size)
        assertEquals(250, Mailboxes.folder(servers, "Discarded").uids().size)
        assertEquals(tags.filterIndexed { i, _ -> i % 26 == 0 }.map { id(it) }, inbox.visible())
        assertEquals(250, runBlocking { undoLog.all() }.size)
    }

    @Test
    fun `purge moves only messages discarded 30 days ago, and only to an existing Trash`() {
        val uids = seed()
        discard(uids, "m1", "m2", "m3")
        val discarded = Mailboxes.folder(servers, "Discarded")
        val inDiscarded = { tag: String -> MoveItem(discarded.byMessageId(id(tag))!!.uid, id(tag)) }
        val day = TimeUnit.DAYS.toMillis(1)
        val records =
            listOf(
                DiscardedMessage(inDiscarded("m1"), now - 31 * day),
                DiscardedMessage(inDiscarded("m2"), now - 29 * day),
                // m3: no record, so never purged.
                DiscardedMessage(MoveItem(9_999, "<gone@test.example>"), now - 40 * day),
            )
        val noTrash = runBlocking { engine().purgeDiscarded(discarded.uidValidity, records) }
        assertTrue(noTrash.noTrash)
        assertFalse(Mailboxes.exists(servers, "Trash"))

        servers.createFolder("Trash")
        val report = runBlocking { engine().purgeDiscarded(discarded.uidValidity, records) }
        assertNull(report.failure)
        assertEquals("Trash", report.trash)
        assertEquals(listOf(id("m1")), Mailboxes.folder(servers, "Trash").messageIds())
        // Tells the caller which records point at nothing any more, so it can drop them.
        assertEquals(listOf(9_999L), report.missing)
        assertEquals(listOf(id("m2"), id("m3")), discarded.visible())
        assertCanaryAlive()
    }

    @Test
    fun `undo is offered for 7 days`() {
        val uids = seed()
        val report = discard(uids, "m1")
        now += TimeUnit.DAYS.toMillis(8)
        val undo = runBlocking { engine().undo(account, report.batchId) }
        assertEquals(1, undo.expired.size)
        assertTrue(undo.restored.isEmpty())
        assertEquals(listOf(id("m1")), Mailboxes.folder(servers, "Discarded").visible())
        assertEquals(1, runBlocking { engine().purgeUndoLog() })
        assertTrue(runBlocking { undoLog.all() }.isEmpty())
    }
}
