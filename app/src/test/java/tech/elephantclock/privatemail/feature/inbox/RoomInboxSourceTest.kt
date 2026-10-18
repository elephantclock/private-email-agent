package tech.elephantclock.privatemail.feature.inbox

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.AccountSyncResult
import tech.elephantclock.privatemail.core.data.repo.FolderSyncResult
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.SyncReport
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource
import java.io.IOException

/** The real Room paging sources behind the lists, over more rows than one page. */
@RunWith(RobolectricTestRunner::class)
class RoomInboxSourceTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val repository = MailRepository(db, connector = { MailResult.Failed(AuthError.WrongCredentials) })
    private val source = RoomInboxSource(db, repository)

    @After
    fun tearDown() = db.close()

    private suspend fun seed(
        count: Int,
        account: tech.elephantclock.privatemail.core.model.Account,
        firstId: Long,
        folder: String = "INBOX",
    ) {
        val entities =
            (0 until count).map { i ->
                ReadingFixtures.entity(
                    firstId + i,
                    "Sender $i",
                    "Subject $i",
                    "Preview $i",
                    minutesAgo = i.toLong(),
                    account,
                    folder,
                )
            }
        db.messages().insertAll(entities)
        db.verdicts().upsertAll(
            entities.map { Verdict.keep(Importance.NORMAL, VerdictSource.RULE, "test").toEntity(it.id, 0) },
        )
    }

    @Test
    fun `priority pages through every kept Inbox message, newest first, and filters by account`() =
        runTest {
            ReadingFixtures.accounts.forEach { db.accounts().upsert(it) }
            seed(120, ReadingFixtures.work, firstId = 1)
            seed(10, ReadingFixtures.home, firstId = 1_000)
            seed(5, ReadingFixtures.work, firstId = 2_000, folder = "Archive")

            val all = source.priority(null).asSnapshot { scrollTo(index = 129) }
            assertEquals(130, all.size)
            assertEquals(all.sortedByDescending { it.message.receivedAtMillis }, all)

            val home = source.priority("acc-home").asSnapshot()
            assertEquals((1_000L until 1_010).toSet(), home.map { it.message.id }.toSet())

            val archive = source.folder("acc-work", "Archive").asSnapshot()
            assertEquals(5, archive.size)
        }

    @Test
    fun `search uses the safe FTS query and ignores text that is only punctuation`() =
        runTest {
            ReadingFixtures.accounts.forEach { db.accounts().upsert(it) }
            seed(3, ReadingFixtures.work, firstId = 1)
            assertEquals(listOf(2L), source.search("subj* 1\"").map { it.message.id })
            assertEquals(emptyList<Long>(), source.search("\"*()").map { it.message.id })
        }

    @Test
    fun `sync reports become refresh outcomes`() {
        val id = AccountId("a")
        assertEquals(RefreshOutcome.DONE, SyncReport(listOf(AccountSyncResult.Synced(id, emptyList()))).outcome())
        assertEquals(
            RefreshOutcome.NEEDS_SIGN_IN,
            SyncReport(listOf(AccountSyncResult.SignInFailed(id, AuthError.WrongCredentials))).outcome(),
        )
        assertEquals(
            RefreshOutcome.PARTLY_FAILED,
            SyncReport(
                listOf(AccountSyncResult.Synced(id, listOf(FolderSyncResult("INBOX", error = IOException("reset"))))),
            ).outcome(),
        )
    }
}
