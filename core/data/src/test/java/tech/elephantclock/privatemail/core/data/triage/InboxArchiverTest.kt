package tech.elephantclock.privatemail.core.data.triage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.SyncTestServer
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

/** The notification's Archive (T49 follow-up) end to end: GreenMail, Room, folder locks, undo. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class InboxArchiverTest {
    private val server = SyncTestServer()
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).build()
    private val id = server.account.id
    private val repo = MailRepository(db, server.connector())
    private val archiver = InboxArchiver(db, repo, server.connector())

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    /** Three Inbox messages, synced and kept in Priority. */
    private fun setUp(withArchive: Boolean): List<MessageEntity> =
        runBlocking {
            if (withArchive) {
                server.greenMail.managers.imapHostManager
                    .createMailbox(server.user, "Archive")
            }
            db.accounts().upsert(server.account.toEntity(createdAtMillis = 1))
            listOf("one", "two", "three").forEach { server.append("INBOX", it) }
            repo.syncAccount(id)
            inbox().onEach {
                db.verdicts().upsert(
                    Verdict.keep(Importance.HIGH, VerdictSource.RULE, "VIP").toEntity(it.id, 5),
                )
            }
        }

    private suspend fun inbox(): List<MessageEntity> {
        val folder = db.folders().get(id.value, "INBOX")!!
        return db.messages().uidFlags(id.value, "INBOX", folder.uidValidity).map {
            db.messages().byServerRef(id.value, "INBOX", folder.uidValidity, it.uid)!!
        }
    }

    private fun serverSubjects(path: String) =
        server
            .folder(path)
            .messages
            .map { it.mimeMessage.subject }
            .sorted()

    @Test
    fun `archive moves the message to Archive, moves its row and logs an undo`() =
        runBlocking {
            val rows = setUp(withArchive = true)
            val two = rows.single { it.subject == "two" }
            val outcome = archiver.archive(id, two.id)
            assertTrue(outcome is ArchiveOutcome.Archived)
            assertEquals(listOf("two"), serverSubjects("Archive"))
            assertEquals(listOf("one", "three"), serverSubjects("INBOX"))
            val moved = db.messages().get(two.id)!!
            assertEquals("Archive", moved.folderPath)
            assertEquals(server.folder("Archive").uidValidity, moved.uidValidity)
            assertEquals("its verdict stays", VerdictAction.KEEP, db.verdicts().get(two.id)!!.action)
            val entries = db.undo().batch(id.value, (outcome as ArchiveOutcome.Archived).batchId)
            assertEquals(listOf(two.id), entries.map { it.localId })
            assertEquals(listOf("one", "three"), inbox().map { it.subject }.sorted())
        }

    @Test
    fun `undo through the mover brings it back to the Inbox`() =
        runBlocking {
            val two = setUp(withArchive = true).single { it.subject == "two" }
            val batch = (archiver.archive(id, two.id) as ArchiveOutcome.Archived).batchId
            val undo = TriageMover(db, repo, server.connector()).undo(server.account, batch)
            assertNotNull(undo)
            assertNull(undo!!.failure)
            assertEquals(listOf("one", "three", "two"), serverSubjects("INBOX"))
            assertEquals("INBOX", db.messages().get(two.id)!!.folderPath)
        }

    @Test
    fun `no Archive folder - nothing moves and nothing is created`() =
        runBlocking {
            val rows = setUp(withArchive = false)
            assertEquals(ArchiveOutcome.NoArchiveFolder, archiver.archive(id, rows.first().id))
            assertNull(
                server.greenMail.managers.imapHostManager
                    .getFolder(server.user, "Archive"),
            )
            assertEquals(listOf("one", "three", "two"), serverSubjects("INBOX"))
            assertEquals("INBOX", db.messages().get(rows.first().id)!!.folderPath)
        }

    @Test
    fun `another account's id, a row outside the Inbox or a removed account touch nothing`() =
        runBlocking {
            val rows = setUp(withArchive = true)
            val before = server.connects.get()
            assertEquals(ArchiveOutcome.NotInInbox, archiver.archive(AccountId("someone-else"), rows.first().id))
            assertEquals(ArchiveOutcome.NotInInbox, archiver.archive(id, 99_999))
            db.messages().moveTo(rows.first().id, "Work", 1, 1)
            assertEquals(ArchiveOutcome.NotInInbox, archiver.archive(id, rows.first().id))
            db.accounts().delete(id.value)
            assertEquals(ArchiveOutcome.NotInInbox, archiver.archive(id, rows[1].id))
            assertEquals("never signed in", before, server.connects.get())
            assertTrue(serverSubjects("Archive").isEmpty())
        }

    @Test
    fun `an account waiting for its password is not signed in to`() =
        runBlocking {
            val rows = setUp(withArchive = true)
            db.accounts().setAuthProblem(id.value, "WrongPassword")
            val before = server.connects.get()
            assertEquals(ArchiveOutcome.NeedsSignIn, archiver.archive(id, rows.first().id))
            assertEquals(before, server.connects.get())
            assertEquals(3, serverSubjects("INBOX").size)
        }
}
