package tech.elephantclock.privatemail.core.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jakarta.mail.Flags
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import tech.elephantclock.privatemail.core.data.db.toFlags
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource

@RunWith(RobolectricTestRunner::class)
// GreenMail's TLS server needs the JDK's TLS provider; Robolectric's Conscrypt rejects its cipher list.
@ConscryptMode(ConscryptMode.Mode.OFF)
class MailRepositoryTest {
    private val server = SyncTestServer()
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).build()
    private val id = server.account.id
    private val repo = MailRepository(db, server.connector())

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun withAccount() = runBlocking { db.accounts().upsert(server.account.toEntity(createdAtMillis = 1)) }

    private suspend fun stored(path: String = "INBOX"): List<MessageEntity> =
        db.messages().uidFlags(id.value, path, db.folders().get(id.value, path)!!.uidValidity).map {
            db.messages().byServerRef(id.value, path, db.folders().get(id.value, path)!!.uidValidity, it.uid)!!
        }

    private suspend fun synced() = repo.syncAccount(id) as AccountSyncResult.Synced

    @Test
    fun `first sync stores the Inbox, saves the sync state and announces the new messages`() =
        runBlocking {
            withAccount()
            repeat(3) { server.append("INBOX", "Hello $it") }
            val announced = async(Dispatchers.Default) { repo.newMessages.first() }
            delay(100) // subscribed before the sync emits

            val result = synced().folders.single()
            assertEquals(FolderSyncResult(path = "INBOX", added = 3), result)
            val folder = db.folders().get(id.value, "INBOX")!!
            assertEquals(3L, folder.highestUid)
            assertTrue(folder.uidValidity > 0)
            assertEquals(listOf("Hello 0", "Hello 1", "Hello 2"), stored().map { it.subject })
            val event = withTimeout(2_000) { announced.await() }
            assertEquals(stored().map { it.id }.sorted(), event.messageIds.sorted())
            // Read-only: nothing was marked \Seen on the server.
            assertTrue(server.folder("INBOX").messages.none { it.isSet(Flags.Flag.SEEN) })
        }

    @Test
    fun `incremental sync stores only new mail and announces only it`() =
        runBlocking {
            withAccount()
            server.append("INBOX", "Old")
            synced()
            assertEquals(0, synced().folders.single().added)

            server.append("INBOX", "New")
            val announced = async(Dispatchers.Default) { repo.newMessages.first() }
            delay(100)
            assertEquals(1, synced().folders.single().added)
            val newId = withTimeout(2_000) { announced.await() }.messageIds.single()
            assertEquals("New", db.messages().get(newId)!!.subject)
            assertEquals(2, stored().size)
        }

    @Test
    fun `flags changed and messages expunged by another client are reconciled`() =
        runBlocking {
            withAccount()
            repeat(4) { server.append("INBOX", "M$it") }
            synced()
            val expunged = stored().single { it.uid == 3L }
            db.verdicts().upsert(Verdict.keep(Importance.HIGH, VerdictSource.RULE, "r").toEntity(expunged.id, 1))

            server.otherClient { _, uids ->
                uids.getMessageByUID(1).setFlag(Flags.Flag.SEEN, true)
                uids.getMessageByUID(2).setFlag(Flags.Flag.FLAGGED, true)
                uids.getMessageByUID(3).setFlag(Flags.Flag.DELETED, true)
            }
            val result = synced().folders.single()
            assertEquals(2, result.flagsChanged)
            assertEquals(1, result.removed)
            assertNull(result.error)

            val byUid = stored().associateBy { it.uid }
            assertEquals(setOf(1L, 2L, 4L), byUid.keys)
            assertEquals(setOf(MessageFlag.SEEN), byUid.getValue(1).flags.toFlags())
            assertEquals(setOf(MessageFlag.FLAGGED), byUid.getValue(2).flags.toFlags())
            assertEquals(emptySet<MessageFlag>(), byUid.getValue(4).flags.toFlags())
            assertNull(db.verdicts().get(expunged.id))

            // Nothing changed since: nothing is rewritten.
            val again = synced().folders.single()
            assertEquals(0, again.flagsChanged)
            assertEquals(0, again.removed)
        }

    @Test
    fun `a UIDVALIDITY change replaces everything stored for the folder`() =
        runBlocking {
            withAccount()
            server.append("Work", "Before")
            synced()
            db.folders().upsert(listOf(db.folders().get(id.value, "Work")!!.copy(role = FolderRole.WORK)))
            assertEquals(1, synced().folders.single { it.path == "Work" }.added)

            server.greenMail.managers.imapHostManager
                .deleteMailbox(server.user, "Work")
            Thread.sleep(1_100) // GreenMail derives UIDVALIDITY from the creation time
            server.append("Work", "After")
            val result = synced().folders.single { it.path == "Work" }
            assertTrue(result.reset)
            assertEquals(listOf("After"), stored("Work").map { it.subject })
            assertNull(db.folders().get(id.value, "Work")!!.highestModSeq)
        }

    @Test
    fun `folders with a triage role are synced, others are only listed`() =
        runBlocking {
            withAccount()
            server.append("Work", "Report")
            server.append("Random", "Ignored")
            assertEquals(listOf("INBOX"), synced().folders.map { it.path })
            assertNotNull(db.folders().get(id.value, "Random"))

            db.folders().upsert(listOf(db.folders().get(id.value, "Work")!!.copy(role = FolderRole.WORK)))
            assertEquals(listOf("INBOX", "Work"), synced().folders.map { it.path })
            assertEquals(listOf("Report"), stored("Work").map { it.subject })
        }

    @Test
    fun `one broken folder never stops the others`() =
        runBlocking {
            withAccount()
            server.append("INBOX", "Fine")
            synced()
            // A role folder that no longer exists on the server.
            db.folders().upsert(
                listOf(db.folders().get(id.value, "INBOX")!!.copy(path = "Gone", role = FolderRole.ORDERS)),
            )
            server.append("INBOX", "Still arrives")
            val folders = synced().folders.associateBy { it.path }
            assertNotNull(folders.getValue("Gone").error)
            assertEquals(1, folders.getValue("INBOX").added)
        }

    @Test
    fun `a wrong password is recorded and the account is not retried in the background`() =
        runBlocking {
            withAccount()
            val bad = MailRepository(db, server.connector(password = "wrong-canary-Pa55"))
            val report = bad.syncAll()
            assertEquals(AccountSyncResult.SignInFailed(id, AuthError.WrongCredentials), report.accounts.single())
            assertFalse(report.shouldRetry)
            assertEquals("WrongCredentials", db.accounts().get(id.value)!!.authProblem)

            val before = server.connects.get()
            assertTrue(bad.syncAll().accounts.single() is AccountSyncResult.Skipped)
            assertEquals(before, server.connects.get())
        }

    @Test
    fun `an unreachable server asks for a retry and blocks nothing`() =
        runBlocking {
            withAccount()
            server.greenMail.stop()
            val report = repo.syncAll()
            assertTrue(report.accounts.single() is AccountSyncResult.SignInFailed)
            assertTrue(report.shouldRetry)
            assertNull(db.accounts().get(id.value)!!.authProblem)
        }

    @Test
    fun `a sync waits while someone else holds the folder lock`() =
        runBlocking {
            withAccount()
            server.append("INBOX", "M")
            val sync = CompletableDeferred<AccountSyncResult>()
            repo.withFolderLock(id, "INBOX") {
                launch(Dispatchers.IO) { sync.complete(repo.syncAccount(id)) }
                delay(1_000)
                assertFalse(sync.isCompleted)
                assertNull(db.folders().get(id.value, "INBOX")?.lastSyncMillis)
            }
            val result = withTimeout(5_000) { sync.await() } as AccountSyncResult.Synced
            assertEquals(1, result.folders.single().added)
        }

    @Test
    fun `mail delivered while watching the Inbox is stored within 2 s`() =
        runBlocking {
            withAccount()
            server.append("INBOX", "Before")
            val watch = launch(Dispatchers.IO) { repo.watchInboxes() }
            withTimeout(5_000) { while (db.folders().get(id.value, "INBOX")?.highestUid != 1L) delay(50) }
            delay(500) // idling now

            val announced = async(Dispatchers.Default) { repo.newMessages.first() }
            delay(100)
            val started = System.currentTimeMillis()
            server.user.deliver(server.message("While idling"))
            val event = withTimeout(2_000) { announced.await() }
            assertTrue(System.currentTimeMillis() - started < 2_000)
            assertEquals("While idling", db.messages().get(event.messageIds.single())!!.subject)
            watch.cancelAndJoin()
        }
}
