package tech.elephantclock.privatemail.core.data.triage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.DbFixtures
import tech.elephantclock.privatemail.core.data.db.FolderEntity
import tech.elephantclock.privatemail.core.data.db.messageEntity
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.SpecialUse

@RunWith(RobolectricTestRunner::class)
class RelationshipRepositoryTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).build()
    private val repo = RelationshipRepository(db)
    private val account = DbFixtures.accountId
    private val own = setOf("me@example.com")
    private var uid = 1L

    @After
    fun tearDown() = db.close()

    private suspend fun sent(
        to: List<String>,
        cc: List<String> = emptyList(),
        folder: String = "Sent",
        messageId: String = "<own-$uid@example.com>",
    ) {
        val h: MessageHeader =
            DbFixtures.header(uid++, folder = folder).copy(
                from = Address.of("me@example.com"),
                to = to.map { Address.of(it) },
                cc = cc.map { Address.of(it) },
                messageId = messageId,
            )
        db.messages().insertAll(listOf(messageEntity(h, DbFixtures.body(h))))
    }

    private fun setUp() =
        runBlocking {
            db.accounts().upsert(DbFixtures.account.toEntity(createdAtMillis = 1))
            db.folders().upsert(listOf(FolderEntity(account.value, "Sent", "Sent", 7, SpecialUse.SENT, null, null)))
        }

    @Test
    fun `the index comes from the stored Sent folder`() =
        runBlocking {
            setUp()
            sent(listOf("anna+work@partner.example"), cc = listOf("me@example.com"))
            sent(listOf("bob@partner.example", "anna@partner.example"), messageId = "<thread-1@example.com>")
            sent(listOf("carol@elsewhere.example"), folder = "INBOX") // not Sent: ignored
            val index = repo.index(account, own, vips = setOf("vip@x.example"))

            assertEquals(2, index.sentStats(Address.of("anna@partner.example"))?.count)
            assertTrue(index.hasSentTo(Address.of("bob@partner.example")))
            assertFalse("own address never counts", index.hasSentTo(Address.of("me@example.com")))
            assertFalse(index.hasSentTo(Address.of("carol@elsewhere.example")))
            assertTrue(index.isVip(Address.of("vip@x.example")))
            assertEquals(listOf("partner.example"), index.frequentDomains())
            val reply = DbFixtures.header(99).copy(inReplyTo = "<thread-1@example.com>", references = emptyList())
            assertTrue(index.repliedInThread(reply))
        }

    @Test
    fun `the index is rebuilt only when Sent or the settings change`() =
        runBlocking {
            setUp()
            sent(listOf("anna@partner.example"))
            val first = repo.index(account, own)
            assertSame(first, repo.index(account, own))
            sent(listOf("dave@new.example"))
            val second = repo.index(account, own)
            assertNotSame(first, second)
            assertTrue(second.hasSentTo(Address.of("dave@new.example")))
            assertNotSame(second, repo.index(account, own, vips = setOf("vip@x.example")))
        }

    @Test
    fun `no Sent folder gives an empty index`() =
        runBlocking {
            db.accounts().upsert(DbFixtures.account.toEntity(createdAtMillis = 1))
            assertEquals(0, repo.index(account, own).sentToCount)
        }
}
