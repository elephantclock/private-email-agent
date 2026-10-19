package tech.elephantclock.privatemail.core.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jakarta.mail.Flags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.read.FullText
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MessageContentRepositoryTest {
    private val server = SyncTestServer()
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .build()
    private val id = server.account.id
    private val content = MessageContentRepository(db, server.connector())

    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private suspend fun syncedMessageId(): Long {
        db.accounts().upsert(server.account.toEntity(createdAtMillis = 1))
        server.append("INBOX", "Quarterly report")
        MailRepository(db, server.connector()).syncAccount(id)
        return db
            .messages()
            .untriaged(10)
            .single()
            .id
    }

    private fun seenOnServer(): Boolean {
        var seen = false
        server.otherClient { folder, _ -> seen = folder.getMessage(1).flags.contains(Flags.Flag.SEEN) }
        return seen
    }

    @Test
    fun `the full text is fetched once, then served from memory, and the message stays unread`() =
        runBlocking {
            val messageId = syncedMessageId()
            val before = server.connects.get()
            assertEquals(ContentResult.Ok(FullText("Body of Quarterly report", false, false)), content.body(messageId))
            assertEquals(ContentResult.Ok(FullText("Body of Quarterly report", false, false)), content.body(messageId))
            assertEquals(1, server.connects.get() - before)
            assertFalse(seenOnServer())
        }

    @Test
    fun `a message moved away since the sync is reported as gone`() =
        runBlocking {
            val messageId = syncedMessageId()
            server.otherClient { folder, _ ->
                folder.getMessage(1).setFlag(Flags.Flag.DELETED, true)
                folder.expunge()
            }
            assertEquals(ContentResult.Gone, content.body(messageId))
            assertEquals(ContentResult.Gone, content.attachment(messageId, "2", File(temp.root, "x")))
        }

    @Test
    fun `an account waiting for its password is not contacted`() =
        runBlocking {
            val messageId = syncedMessageId()
            db.accounts().setAuthProblem(id.value, "WrongCredentials")
            val before = server.connects.get()
            assertEquals(ContentResult.NeedsSignIn, content.body(messageId))
            assertEquals(before, server.connects.get())
        }

    @Test
    fun `a wrong password is recorded so the next tap doesn't try again`() =
        runBlocking {
            val messageId = syncedMessageId()
            val wrong = MessageContentRepository(db, server.connector(password = "nope"))
            assertEquals(ContentResult.Failed(AuthError.WrongCredentials), wrong.body(messageId))
            assertTrue(db.accounts().get(id.value)!!.authProblem != null)
        }
}
