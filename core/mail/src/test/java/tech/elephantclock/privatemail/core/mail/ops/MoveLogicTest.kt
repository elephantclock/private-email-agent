package tech.elephantclock.privatemail.core.mail.ops

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.Namespace
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.core.model.VerdictAction

/**
 * Engine logic on [FakeMailbox]. Gmail's X-GM-LABELS is not in GreenMail, so the Gmail path
 * is only checked here (command strings and sequence), not against a real server.
 */
class MoveLogicTest {
    private val account = AccountId("acc")
    private val undoLog = InMemoryUndoLog()
    private lateinit var fake: FakeMailbox

    private val gmail = Capabilities.parse("IMAP4rev1 IDLE MOVE UIDPLUS X-GM-EXT-1 SPECIAL-USE")
    private val dovecot = Capabilities.parse("IMAP4rev1 IDLE MOVE UIDPLUS")

    private fun engine(
        caps: Capabilities,
        namespace: Namespace = Namespace("", '/'),
    ): MoveEngine {
        fake = FakeMailbox(caps, namespace)
        return MoveEngine(fake, undoLog, { 1_000L }, Dispatchers.Unconfined)
    }

    @After
    fun everyFolderOpenedWasClosedWithoutExpunge() {
        if (!::fake.isInitialized) return
        val opened = fake.commands.count { it.startsWith("SELECT ") || it.startsWith("EXAMINE ") }
        assertEquals(opened, fake.commands.count { it.startsWith("UNSELECT ") })
    }

    private fun seed(vararg tags: String) = tags.associateWith { fake.folder("INBOX").add("<$it@x>") }

    private fun items(
        uids: Map<String, Long>,
        vararg tags: String,
    ) = tags.map { MoveItem(uids.getValue(it), "<$it@x>") }

    private fun move(
        e: MoveEngine,
        items: List<MoveItem>,
        target: FolderTarget = FolderTarget.Discarded,
        action: VerdictAction = VerdictAction.DISCARD,
        from: String = "INBOX",
    ) = runBlocking { e.move(MoveRequest(account, from, fake.folder(from).uidValidity, items, target, action)) }

    @Test
    fun `Gmail discard adds the label, then removes Inbox, and nothing else`() {
        val e = engine(gmail)
        val uids = seed("a", "b", "c")
        val report = move(e, items(uids, "a", "c"))
        val writes = fake.commands.filter { it.startsWith("UID ") }
        assertEquals(
            listOf(
                "UID STORE 1,3 +X-GM-LABELS (\"Discarded\")",
                "UID STORE 1,3 -X-GM-LABELS (\\Inbox)",
            ),
            writes,
        )
        assertEquals(
            listOf("<b@x>"),
            fake
                .folder("INBOX")
                .messages.values
                .toList(),
        )
        assertEquals(
            listOf("<a@x>", "<c@x>"),
            fake
                .folder("Discarded")
                .messages.values
                .toList(),
        )
        // No COPYUID from label changes: new UIDs come from the Message-ID lookup.
        assertEquals(listOf(1L, 2L), report.moved.map { it.newUid })
        assertTrue(!report.markedOnly)
    }

    @Test
    fun `Gmail labels go in batches of 100`() {
        val e = engine(gmail)
        val tags = (1..250).map { "m$it" }.toTypedArray()
        val uids = seed(*tags)
        move(e, items(uids, *tags))
        assertEquals(3, fake.commands.count { it.contains("+X-GM-LABELS") })
        assertEquals(3, fake.commands.count { it.contains("-X-GM-LABELS") })
        assertTrue(fake.commands.any { it.startsWith("UID STORE 1:100 +X-GM-LABELS") })
        assertTrue(fake.commands.any { it.startsWith("UID STORE 201:250 -X-GM-LABELS") })
    }

    @Test
    fun `Gmail junk and moves from other labels use UID MOVE`() {
        val e = engine(gmail)
        fake.folders["[Gmail]/Spam"] = FakeMailbox.Folder(9, specialUse = SpecialUse.JUNK)
        val uids = seed("a", "b")
        move(e, items(uids, "a"), FolderTarget.Junk, VerdictAction.JUNK)
        assertTrue(fake.commands.contains("UID MOVE 1 [Gmail]/Spam"))
        fake.folders["Work"] = FakeMailbox.Folder(10).apply { add("<w@x>") }
        move(e, listOf(MoveItem(1, "<w@x>")), FolderTarget.Discarded, from = "Work")
        assertTrue(fake.commands.contains("UID MOVE 1 Discarded"))
        assertTrue(fake.commands.none { it.contains("X-GM-LABELS") })
    }

    @Test
    fun `Gmail undo moves the message out of the label back to the Inbox`() {
        val e = engine(gmail)
        val uids = seed("a", "b")
        val report = move(e, items(uids, "a"))
        val undo = runBlocking { e.undo(account, report.batchId) }
        assertNull(undo.failure)
        assertTrue(fake.commands.contains("UID MOVE 1 INBOX"))
        assertEquals(
            listOf("<b@x>", "<a@x>"),
            fake
                .folder("INBOX")
                .messages.values
                .toList(),
        )
        assertTrue(fake.folder("Discarded").messages.isEmpty())
    }

    @Test
    fun `INBOX-prefixed servers get INBOX dot folders`() {
        val e = engine(dovecot, Namespace("INBOX", '.'))
        val uids = seed("a", "b")
        assertEquals("INBOX.Discarded", move(e, items(uids, "a")).destination)
        val custom = move(e, items(uids, "b"), FolderTarget.custom("Clients.ACME"), VerdictAction.FILE)
        assertEquals("INBOX.Clients-ACME", custom.destination)
        assertTrue(fake.commands.contains("CREATE INBOX.Discarded"))
    }

    @Test
    fun `existing folders are reused, Junk uses the special-use folder`() {
        val e = engine(dovecot)
        fake.folders["work"] = FakeMailbox.Folder(5)
        fake.folders["Spam"] = FakeMailbox.Folder(6, specialUse = SpecialUse.JUNK)
        val uids = seed("a", "b")
        assertEquals("work", move(e, items(uids, "a"), FolderTarget(FolderRole.WORK), VerdictAction.FILE).destination)
        assertEquals("Spam", move(e, items(uids, "b"), FolderTarget.Junk, VerdictAction.JUNK).destination)
        assertTrue(fake.commands.none { it.startsWith("CREATE") })
    }

    @Test
    fun `undo never trusts a UID from before a UIDVALIDITY change`() {
        val e = engine(dovecot)
        val uids = seed("a")
        val report = move(e, items(uids, "a"))
        val entry = runBlocking { undoLog.batch(account, report.batchId) }.single()
        assertEquals(1L, entry.toUid)
        // The server rebuilt Discarded: UID 1 is now someone else's message.
        fake.folder("Discarded").apply {
            uidValidity = 777
            messages.clear()
            nextUid = 1
            add("<other@x>")
            add("<a@x>")
        }
        val undo = runBlocking { e.undo(account, report.batchId) }
        assertEquals(
            listOf("<other@x>"),
            fake
                .folder("Discarded")
                .messages.values
                .toList(),
        )
        assertEquals(
            listOf("<a@x>"),
            fake
                .folder("INBOX")
                .messages.values
                .toList(),
        )
        assertEquals(1, undo.restored.size)
    }

    @Test
    fun `undo leaves a copy alone when it can't tell which one is ours`() {
        val e = engine(dovecot)
        val uids = seed("a")
        val report = move(e, items(uids, "a"))
        fake.folder("Discarded").apply {
            uidValidity = 777
            messages.clear()
            add("<a@x>")
            add("<a@x>")
        }
        val undo = runBlocking { e.undo(account, report.batchId) }
        assertEquals(1, undo.notFound.size)
        assertEquals(2, fake.folder("Discarded").messages.size)
        assertTrue(fake.folder("INBOX").messages.isEmpty())
        assertTrue(fake.commands.none { it.startsWith("UID MOVE") && it.endsWith("INBOX") })
    }

    @Test
    fun `without MOVE or UIDPLUS nothing is ever expunged`() {
        val e = engine(Capabilities.parse("IMAP4rev1"))
        val uids = seed("a", "b")
        val report = move(e, items(uids, "a"))
        assertTrue(report.markedOnly)
        assertEquals(
            listOf("UID COPY 1 Discarded", "UID STORE 1 +FLAGS (\\Deleted)"),
            fake.commands.filter {
                it.startsWith("UID ")
            },
        )
        // The copy was found by Message-ID (no COPYUID without UIDPLUS).
        assertEquals(1L, report.moved.single().newUid)
        runBlocking { e.undo(account, report.batchId) }
        assertTrue(fake.commands.none { it.contains("EXPUNGE") })
        assertEquals(setOf<Long>(), fake.folder("INBOX").deleted)
        assertEquals(setOf(1L), fake.folder("Discarded").deleted)
    }

    @Test
    fun `MOVE without COPYUID still learns the new UIDs`() {
        val e = engine(dovecot)
        fake.sendCopyUid = false
        val uids = seed("a", "b")
        fake.folders["Discarded"] = FakeMailbox.Folder(50, nextUid = 40)
        val report = move(e, items(uids, "a", "b"))
        assertEquals(listOf(40L, 41L), report.moved.map { it.newUid })
    }

    @Test
    fun `a duplicate Message-ID in one batch leaves those new UIDs unknown`() {
        val e = engine(dovecot)
        fake.sendCopyUid = false
        val a1 = fake.folder("INBOX").add("<dup@x>")
        val a2 = fake.folder("INBOX").add("<dup@x>")
        val report = move(e, listOf(MoveItem(a1, "<dup@x>"), MoveItem(a2, "<dup@x>")))
        assertEquals(listOf(null, null), report.moved.map { it.newUid })
    }

    @Test
    fun `undo only removes a destination copy it can prove is the one it made`() {
        val e = engine(Capabilities.parse("IMAP4rev1"))
        val uids = seed("a")
        val report = move(e, items(uids, "a"))
        // Discarded was rebuilt: the one "<a@x>" there may be the user's own older copy.
        fake.folder("Discarded").apply {
            uidValidity = 777
            messages.clear()
            deleted.clear()
            add("<a@x>")
        }
        val undo = runBlocking { e.undo(account, report.batchId) }
        assertEquals(1, undo.restored.size)
        assertEquals(setOf<Long>(), fake.folder("INBOX").deleted)
        assertEquals("unverified copy untouched", setOf<Long>(), fake.folder("Discarded").deleted)
        assertEquals(1, undo.leftCopies.size)
        assertEquals(
            listOf("<a@x>"),
            fake
                .folder("Discarded")
                .messages.values
                .toList(),
        )
    }
}
