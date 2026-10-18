package tech.elephantclock.privatemail.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.mail.ops.UndoEntry
import tech.elephantclock.privatemail.core.model.VerdictAction

@RunWith(RobolectricTestRunner::class)
class TriageDaoTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).build()

    @After
    fun tearDown() = db.close()

    private fun withAccount() = runBlocking { db.accounts().upsert(DbFixtures.account.toEntity(createdAtMillis = 1)) }

    @Test
    fun `the undo log round-trips every field, with or without a local id`() =
        runBlocking {
            withAccount()
            val log = RoomUndoLog(db)
            val entry =
                UndoEntry(
                    DbFixtures.accountId,
                    "b1",
                    null,
                    VerdictAction.JUNK,
                    "INBOX",
                    7,
                    3,
                    "Junk",
                    11,
                    5,
                    "<m@x>",
                    100,
                )
            log.record(listOf(entry, entry.copy(localId = 9, fromUid = 4, toUid = null, createdAtMillis = 200)))
            val back = log.batch(DbFixtures.accountId, "b1")
            assertEquals(listOf(entry, entry.copy(localId = 9, fromUid = 4, toUid = null, createdAtMillis = 200)), back)
            assertEquals(1, log.purgeOlderThan(150))
            log.deleteBatch(DbFixtures.accountId, "b1")
            assertEquals(emptyList<UndoEntry>(), log.batch(DbFixtures.accountId, "b1"))
        }

    @Test
    fun `a UIDVALIDITY reset keeps rows already under the new generation`() =
        runBlocking {
            withAccount()
            val old = db.messages().insertAll(listOf(DbFixtures.entity(1))).single() // uidValidity 7
            val moved = db.messages().insertAll(listOf(DbFixtures.entity(2).copy(uidValidity = 9))).single()
            db.verdicts().upsert(DbFixtures.discard.toEntity(moved, 1))
            db.messages().deleteStaleGenerations(DbFixtures.accountId.value, "INBOX", uidValidity = 9)
            assertNull(db.messages().get(old))
            assertNotNull(db.messages().get(moved))
            assertNotNull(db.verdicts().get(moved))
        }

    @Test
    fun `recordMove stores the new location and the move time`() =
        runBlocking {
            withAccount()
            val id = db.messages().insertAll(listOf(DbFixtures.entity(1))).single()
            db.triage().recordMove(id, "Discarded", 11, 77, movedAtMillis = 5_000)
            val row = db.messages().get(id)!!
            assertEquals(
                listOf("Discarded", 11L, 77L, 5_000L),
                listOf(row.folderPath, row.uidValidity, row.uid, row.movedAtMillis),
            )
            db.verdicts().upsert(DbFixtures.discard.toEntity(id, 1))
            assertEquals(listOf(id), db.triage().discardedBefore(DbFixtures.accountId.value, 5_000).map { it.id })
            assertEquals(
                emptyList<Long>(),
                db.triage().discardedBefore(DbFixtures.accountId.value, 4_999).map { it.id },
            )
        }
}
