package tech.elephantclock.privatemail.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address

/** Account colours and names (T52): stable, and kept when onboarding saves the account again. */
@RunWith(RobolectricTestRunner::class)
class AccountDaoTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).build()
    private val dao = db.accounts()

    @After
    fun tearDown() = db.close()

    private fun entity(
        id: String,
        createdAt: Long = 1,
    ) = DbFixtures.account
        .copy(id = AccountId(id), email = Address.of("$id@example.com"), displayName = "$id@example.com")
        .toEntity(createdAt)

    @Test
    fun `re-saving an account (new password) keeps the user's name and colour, and clears the problem`() =
        runBlocking {
            dao.upsert(entity("a"))
            dao.rename("a", "Work")
            dao.setColor("a", 3)
            dao.setAuthProblem("a", "WrongPassword")
            // What AddAccountViewModel saves: display name = address, default colour.
            dao.upsert(entity("a"))
            val saved = dao.get("a")!!
            assertEquals("Work", saved.displayName)
            assertEquals(3, saved.colorIndex)
            assertNull(saved.authProblem)
        }

    @Test
    fun `an account the user never renamed takes the new display name`() =
        runBlocking {
            dao.upsert(entity("a"))
            dao.upsert(entity("a").copy(displayName = "Personal"))
            assertEquals("Personal", dao.get("a")!!.displayName)
        }

    @Test
    fun `new accounts get the first free colour, also after one is removed`() =
        runBlocking {
            listOf("a", "b", "c").forEach { dao.upsert(entity(it)) }
            assertEquals(listOf(0, 1, 2), listOf("a", "b", "c").map { dao.get(it)!!.colorIndex })
            dao.delete("b")
            assertEquals("others keep theirs", listOf(0, 2), listOf("a", "c").map { dao.get(it)!!.colorIndex })
            dao.upsert(entity("d"))
            assertEquals(1, dao.get("d")!!.colorIndex)
        }

    @Test
    fun `with every colour taken, the least used comes next`() {
        assertEquals(0, AccountDao.freeColor(emptyList()))
        assertEquals(2, AccountDao.freeColor(listOf(0, 1, 3)))
        assertEquals(1, AccountDao.freeColor(listOf(0, 1, 2, 3, 4, 0, 2)))
    }

    @Test
    fun `last sync is the newest folder sync, null before the first`() =
        runBlocking {
            dao.upsert(entity("a"))
            dao.upsert(entity("b"))
            db.folders().upsert(
                listOf(
                    FolderEntity("a", "INBOX", "Inbox", 1, null, null, null, lastSyncMillis = 100),
                    FolderEntity("a", "Sent", "Sent", 1, null, null, null, lastSyncMillis = 300),
                    FolderEntity("b", "INBOX", "Inbox", 1, null, null, null),
                ),
            )
            val syncs = dao.lastSyncs().first().associate { it.accountId to it.lastSyncMillis }
            assertEquals(mapOf("a" to 300L, "b" to null), syncs)
        }
}
