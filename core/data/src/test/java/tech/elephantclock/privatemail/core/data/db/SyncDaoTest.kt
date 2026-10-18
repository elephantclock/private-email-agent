package tech.elephantclock.privatemail.core.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.model.FolderRole

/** The DAO parts the sync (T14) relies on. */
@RunWith(RobolectricTestRunner::class)
class SyncDaoTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val messages = db.messages()

    @After
    fun tearDown() = db.close()

    private suspend fun withAccount() = db.accounts().upsert(DbFixtures.account.toEntity(createdAtMillis = 1))

    private suspend fun priorityUids(): List<Long> =
        (messages.priority().load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page)
            .data
            .map { it.message.uid }

    @Test
    fun `Priority is KEEP and still in the Inbox`() =
        runTest {
            withAccount()
            val (inInbox, filedLater) = messages.insertAll(listOf(DbFixtures.entity(1), DbFixtures.entity(2)))
            val elsewhere =
                messages
                    .insertAll(
                        listOf(
                            messageEntity(
                                DbFixtures.header(3, folder = "Archive"),
                                DbFixtures.body(DbFixtures.header(3)),
                            ),
                        ),
                    ).single()
            listOf(inInbox, filedLater, elsewhere).forEach { db.verdicts().upsert(DbFixtures.keep.toEntity(it, 1)) }
            assertEquals(listOf(2L, 1L), priorityUids())

            // Filed out of the Inbox (by the user elsewhere, or T17): it keeps its verdict but leaves Priority.
            messages.moveTo(filedLater, "Archive", 7, 20)
            assertEquals(listOf(1L), priorityUids())
            assertEquals(DbFixtures.keep, db.verdicts().get(filedLater)!!.toModel())
        }

    @Test
    fun `deleteUids splits large deletes under the bound-parameter limit`() =
        runTest {
            withAccount()
            messages.insertAll((1L..2_000L).map { DbFixtures.entity(it) })
            val chunks = mutableListOf<Int>()
            val counting =
                object : MessageDao by messages {
                    override suspend fun deleteUidChunk(
                        accountId: String,
                        folderPath: String,
                        uidValidity: Long,
                        uids: List<Long>,
                    ): Int {
                        chunks += uids.size
                        return messages.deleteUidChunk(accountId, folderPath, uidValidity, uids)
                    }
                }
            val removed = counting.deleteUids("acc-1", "INBOX", 7, (1L..1_950L).toList())
            assertEquals(1_950, removed)
            assertEquals(listOf(900, 900, 150), chunks)
            assertEquals((1_951L..2_000L).toList(), messages.uidFlags("acc-1", "INBOX", 7).map { it.uid }.sorted())
            // Only this folder generation: same UIDs under another UIDVALIDITY are untouched.
            assertEquals(0, messages.deleteUids("acc-1", "INBOX", 8, listOf(1_960L)))
            assertEquals(0, messages.deleteUids("acc-1", "INBOX", 7, emptyList()))
        }

    @Test
    fun `listing folders again keeps their role, description and sync state`() =
        runTest {
            withAccount()
            val folders = db.folders()
            folders.insertNew(listOf(FolderEntity("acc-1", "Work", "Work", 0, null, null, null)))
            folders.upsert(
                listOf(folders.get("acc-1", "Work")!!.copy(role = FolderRole.WORK, description = "Colleagues")),
            )
            folders.updateSyncState("acc-1", "Work", 9, 40, 123)
            folders.updateModSeq("acc-1", "Work", 77)

            folders.insertNew(listOf(FolderEntity("acc-1", "Work", "Work", 0, null, null, null)))
            val work = folders.get("acc-1", "Work")!!
            assertEquals(FolderRole.WORK, work.role)
            assertEquals("Colleagues", work.description)
            assertEquals(listOf(9L, 40L, 77L), listOf(work.uidValidity, work.highestUid, work.highestModSeq))
        }

    @Test
    fun `sync state is only saved for a folder row that exists`() =
        runTest {
            withAccount()
            db.folders().updateSyncState("acc-1", "INBOX", 9, 40, 123)
            assertNull(db.folders().get("acc-1", "INBOX"))
        }

    @Test
    fun `an auth problem is recorded and cleared by saving the account again`() =
        runTest {
            withAccount()
            db.accounts().setAuthProblem("acc-1", "WrongCredentials")
            assertEquals("WrongCredentials", db.accounts().get("acc-1")!!.authProblem)
            withAccount() // T41: the user re-entered the password and the account is saved again
            assertNull(db.accounts().get("acc-1")!!.authProblem)
            assertTrue(db.accounts().get("acc-1") != null)
        }
}
