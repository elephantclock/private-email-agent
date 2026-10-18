package tech.elephantclock.privatemail.core.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource

@RunWith(RobolectricTestRunner::class)
class ReadingDaoTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val reading = db.reading()
    private val other = AccountId("acc-2")

    @After
    fun tearDown() = db.close()

    private suspend fun accounts() {
        db.accounts().upsert(DbFixtures.account.toEntity(createdAtMillis = 1))
        db.accounts().upsert(DbFixtures.account.copy(id = other).toEntity(createdAtMillis = 2))
    }

    private suspend fun message(
        uid: Long,
        account: AccountId = DbFixtures.accountId,
        folder: String = "INBOX",
        verdict: Verdict? = DbFixtures.keep,
        decidedAt: Long = 100,
        seen: Boolean = true,
    ): Long {
        val h =
            DbFixtures.header(uid, folder = folder, account = account).let {
                if (seen) it else it.copy(flags = emptySet())
            }
        val id = db.messages().insertAll(listOf(messageEntity(h, DbFixtures.body(h)))).single()
        verdict?.let { db.verdicts().upsert(it.toEntity(id, decidedAt)) }
        return id
    }

    private suspend fun PagingSource<Int, MessageWithVerdict>.ids(): List<Long> =
        (load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page).data.map {
            it.message.id
        }

    @Test
    fun `priority of one account leaves out other accounts, other folders and other verdicts`() =
        runTest {
            accounts()
            val mine = message(1)
            message(2, account = other)
            message(3, folder = "Archive")
            message(4, verdict = DbFixtures.discard)
            message(5, verdict = null)
            assertEquals(listOf(mine), reading.priorityIn(DbFixtures.accountId.value).ids())
            assertEquals(
                setOf(AccountCount("acc-1", 1), AccountCount("acc-2", 1)),
                reading.priorityCounts().first().toSet(),
            )
        }

    @Test
    fun `only synced folders are offered, Inbox first, with totals and unread counts`() =
        runTest {
            accounts()
            db.folders().insertNew(
                listOf("INBOX", "Sent", "Archive", "Never synced").map {
                    FolderEntity("acc-1", it, it, 0, if (it == "Sent") SpecialUse.SENT else null, null, null)
                },
            )
            listOf("Sent", "INBOX", "Archive").forEach { db.folders().updateSyncState("acc-1", it, 7, 1, 1) }
            message(1, seen = false)
            message(2)
            message(3, folder = "Sent")
            val folders = reading.syncedFolders("acc-1").first()
            assertEquals(listOf("INBOX", "Archive", "Sent"), folders.map { it.path })
            assertEquals(listOf(2, 0, 1), folders.map { it.total })
            assertEquals(listOf(1, 0, 0), folders.map { it.unread })
        }

    @Test
    fun `triage counts since a time split filed and discarded, and count what is still in the Inbox`() =
        runTest {
            accounts()
            val file = Verdict.file(FolderTarget(FolderRole.MONEY), Importance.NORMAL, VerdictSource.RULE, "Invoice")
            message(1, verdict = file, decidedAt = 1_000)
            message(2, folder = "Money", verdict = file, decidedAt = 1_000)
            message(3, verdict = DbFixtures.discard, decidedAt = 1_000)
            message(4, verdict = Verdict.junk(VerdictSource.RULE, "Phishing"), decidedAt = 1_000, account = other)
            message(5, verdict = DbFixtures.discard, decidedAt = 10) // yesterday
            message(6, decidedAt = 1_000) // KEEP: not counted
            assertEquals(
                TriageCounts(filed = 2, discarded = 2, stillInInbox = 3),
                reading.triagedSince(500, null).first(),
            )
            assertEquals(TriageCounts(2, 1, 2), reading.triagedSince(500, "acc-1").first())
        }

    @Test
    fun `a message is followed until it is deleted`() =
        runTest {
            accounts()
            val id = message(1)
            assertEquals(
                Importance.HIGH,
                reading
                    .message(id)
                    .first()!!
                    .verdict!!
                    .importance,
            )
            assertEquals(
                setOf(MessageFlag.SEEN, MessageFlag.FLAGGED),
                reading
                    .message(id)
                    .first()!!
                    .message.flags
                    .toFlags(),
            )
            db.triage().deleteMessage(id)
            assertNull(reading.message(id).first())
        }
}
