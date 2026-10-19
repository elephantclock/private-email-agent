package tech.elephantclock.privatemail.core.mail.ops

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.core.model.VerdictAction

/**
 * Archive on [FakeMailbox]: Gmail (labels aren't in GreenMail) and folder choice. On Gmail every
 * message is also in All Mail; archiving only drops `\Inbox`, and nothing may ever be moved,
 * marked `\Deleted` or expunged in All Mail (Gmail would delete it).
 */
class ArchiveLogicTest {
    private val account = AccountId("acc")
    private val undoLog = InMemoryUndoLog()
    private lateinit var fake: FakeMailbox

    private val gmail = Capabilities.parse("IMAP4rev1 IDLE MOVE UIDPLUS X-GM-EXT-1 SPECIAL-USE")
    private val dovecot = Capabilities.parse("IMAP4rev1 IDLE MOVE UIDPLUS SPECIAL-USE")

    private fun engine(caps: Capabilities): MoveEngine {
        fake = FakeMailbox(caps)
        return MoveEngine(fake, undoLog, { 1_000L }, Dispatchers.Unconfined)
    }

    @After
    fun everyFolderOpenedWasClosedAndNothingLeftAllMail() {
        if (!::fake.isInitialized) return
        val opened = fake.commands.count { it.startsWith("SELECT ") || it.startsWith("EXAMINE ") }
        assertEquals(opened, fake.commands.count { it.startsWith("UNSELECT ") })
        var selected: String? = null
        for (c in fake.commands) {
            if (c.startsWith("SELECT ") || c.startsWith("EXAMINE ")) selected = c.substringAfter(' ')
            val removes = c.startsWith("UID MOVE") || c.startsWith("UID EXPUNGE") || c.contains("+FLAGS (\\Deleted)")
            assertTrue("never in All Mail: $c", !(removes && selected == ALL_MAIL))
        }
    }

    /** Gmail: messages in INBOX and (as on Gmail) in All Mail. */
    private fun seedGmail(vararg tags: String): Map<String, Long> {
        fake.folders[ALL_MAIL] = FakeMailbox.Folder(77, specialUse = SpecialUse.ALL)
        return tags.associateWith {
            fake.folder(ALL_MAIL).add("<$it@x>")
            fake.folder("INBOX").add("<$it@x>")
        }
    }

    private fun archive(
        e: MoveEngine,
        uids: Map<String, Long>,
        vararg tags: String,
    ) = runBlocking {
        e.archive(
            ArchiveRequest(
                account,
                fake.folder("INBOX").uidValidity,
                tags.map {
                    MoveItem(uids.getValue(it), "<$it@x>", 7)
                },
            ),
        )
    }

    private fun inbox() =
        fake
            .folder("INBOX")
            .messages.values
            .toList()

    @Test
    fun `Gmail archive only removes the Inbox label`() {
        val e = engine(gmail)
        val uids = seedGmail("a", "b", "c")
        val report = (archive(e, uids, "a", "c") as ArchiveResult.Archived).report
        assertNull(report.failure)
        assertEquals(listOf("UID STORE 1,3 -X-GM-LABELS (\\Inbox)"), fake.commands.filter { it.startsWith("UID ") })
        assertEquals(listOf("<b@x>"), inbox())
        assertEquals(
            listOf("<a@x>", "<b@x>", "<c@x>"),
            fake
                .folder(ALL_MAIL)
                .messages.values
                .toList(),
        )
        assertEquals(ALL_MAIL, report.destination)
        assertEquals(77L, report.destinationUidValidity)
        // Their All Mail UIDs, found by Message-ID (they were there before the archive).
        assertEquals(listOf(1L, 3L), report.moved.map { it.newUid })
        val entries = runBlocking { undoLog.batch(account, report.batchId) }
        assertTrue(entries.all { it.action == VerdictAction.FILE && it.toFolder == ALL_MAIL })
    }

    @Test
    fun `Gmail undo adds the Inbox label back and never moves out of All Mail`() {
        val e = engine(gmail)
        val uids = seedGmail("a", "b")
        val report = (archive(e, uids, "a") as ArchiveResult.Archived).report
        val undo = runBlocking { e.undo(account, report.batchId) }
        assertNull(undo.failure)
        assertTrue(fake.commands.contains("UID STORE 1 +X-GM-LABELS (\\Inbox)"))
        assertTrue(fake.commands.none { it.startsWith("UID MOVE") || it.startsWith("UID COPY") })
        assertEquals(listOf("<b@x>", "<a@x>"), inbox())
        assertEquals(2, fake.folder(ALL_MAIL).messages.size)
        assertEquals(listOf(3L), undo.restored.map { it.uid })
    }

    @Test
    fun `Gmail without All Mail over IMAP archives nothing`() {
        val e = engine(gmail)
        val uids = mapOf("a" to fake.folder("INBOX").add("<a@x>"))
        assertEquals(ArchiveResult.NoArchiveFolder, archive(e, uids, "a"))
        assertTrue(fake.commands.none { it.startsWith("UID ") || it.startsWith("CREATE") })
        assertEquals(listOf("<a@x>"), inbox())
    }

    @Test
    fun `Gmail ignores a user label called Archive`() {
        val e = engine(gmail)
        val uids = seedGmail("a")
        fake.folders["Archive"] = FakeMailbox.Folder(5, specialUse = SpecialUse.ARCHIVE)
        val report = (archive(e, uids, "a") as ArchiveResult.Archived).report
        assertEquals(ALL_MAIL, report.destination)
        assertTrue(fake.folder("Archive").messages.isEmpty())
    }

    @Test
    fun `other servers use the special-use Archive folder and never create one`() {
        val e = engine(dovecot)
        fake.folders["Archives"] = FakeMailbox.Folder(9, specialUse = SpecialUse.ARCHIVE)
        val uids = mapOf("a" to fake.folder("INBOX").add("<a@x>"), "b" to fake.folder("INBOX").add("<b@x>"))
        val report = (archive(e, uids, "b") as ArchiveResult.Archived).report
        assertEquals("Archives", report.destination)
        assertTrue(fake.commands.contains("UID MOVE 2 Archives"))
        assertTrue(fake.commands.none { it.startsWith("CREATE") })
        assertEquals(listOf("<a@x>"), inbox())
    }

    @Test
    fun `no special-use Archive and no CREATE on other servers`() {
        val e = engine(dovecot)
        fake.folders["Work"] = FakeMailbox.Folder(9)
        val uids = mapOf("a" to fake.folder("INBOX").add("<a@x>"))
        assertEquals(ArchiveResult.NoArchiveFolder, archive(e, uids, "a"))
        assertTrue(fake.commands.isEmpty())
        assertEquals(setOf("INBOX", "Work"), fake.folders.keys)
    }

    private companion object {
        const val ALL_MAIL = "[Gmail]/All Mail"
    }
}
