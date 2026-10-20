package tech.elephantclock.privatemail.core.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.DbFixtures
import tech.elephantclock.privatemail.core.data.db.messageEntity
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.model.Address

@RunWith(RobolectricTestRunner::class)
class BriefRepositoryTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val repo = BriefRepository(db, io = Dispatchers.Unconfined)
    private var computeCalls = 0
    private val fetched = mutableListOf<Long>()
    private val fullText: suspend (Long) -> String? = { id ->
        fetched += id
        "Full text of $id\n\nOn Mon, Anna <a@x.example> wrote:\n> quoted"
    }
    private val compute: suspend (BriefThread) -> BriefOutcome = { t ->
        computeCalls++
        BriefOutcome.Ready(ThreadBrief("gist ${t.key.latest}", "waiting_on_you"))
    }

    @Before
    fun setUp() =
        runTest {
            db.accounts().upsert(DbFixtures.account.toEntity(createdAtMillis = 1))
        }

    @After
    fun tearDown() = db.close()

    /** Inserts a message; ids and references as a mail client would set them. */
    private suspend fun insert(
        uid: Long,
        messageId: String?,
        references: List<String> = emptyList(),
        inReplyTo: String? = references.lastOrNull(),
        folder: String = "INBOX",
        from: String = "anna@client.example",
        at: Long = uid * 1_000,
        text: String = "Preview $uid",
    ): Long {
        val h =
            DbFixtures.header(uid, folder = folder, from = from, receivedAt = at).copy(
                messageId = messageId,
                references = references,
                inReplyTo = inReplyTo,
                from = Address.of(from, null),
            )
        return db.messages().insertAll(listOf(messageEntity(h, DbFixtures.body(h, text)))).single()
    }

    @Test
    fun `the thread is found through References, deduplicated and ordered`() =
        runTest {
            val root = insert(1, "<root@x>")
            insert(2, "<r1_x@me>", listOf("<root@x>"), folder = "Sent", from = "me@example.com")
            val copy = insert(3, "<r1_x@me>", listOf("<root@x>"), folder = "All Mail", from = "me@example.com")
            val last = insert(4, "<r2@x>", listOf("<root@x>", "<r1_x@me>"))
            insert(5, "<other@x>", listOf("<unrelated@x>")) // another thread
            insert(6, "<lookalike@x>", listOf("<root@xy>")) // LIKE must not match a longer id
            val thread = repo.thread(root, fullText)!!
            assertEquals(listOf(1L, 2L, 4L), thread.messages.map { it.localId })
            assertTrue(copy !in thread.messages.map { it.localId })
            assertEquals(listOf(false, true, false), thread.messages.map { it.fromUser })
            assertEquals(BriefKey(DbFixtures.accountId.value, "<root@x>", "<r2@x>"), thread.key)
            // Opening any message of the thread gives the same key.
            assertEquals(thread.key, repo.key(last))
        }

    @Test
    fun `LIKE wildcards in Message-IDs are escaped`() =
        runTest {
            val a = insert(1, "<a_b%c@x>")
            insert(2, "<reply@x>", listOf("<a_b%c@x>"))
            insert(3, "<wild@x>", listOf("<aXbYc@x>"))
            assertEquals(listOf(1L, 2L), repo.thread(a, fullText)!!.messages.map { it.localId })
        }

    @Test
    fun `newest messages get the full text, quotes removed, older ones use the preview`() =
        runTest {
            insert(1, "<root@x>", text = "Old preview\n> quoted")
            insert(2, "<r1@x>", listOf("<root@x>"))
            val last = insert(3, "<r2@x>", listOf("<root@x>"))
            val thread = repo.thread(last, fullText)!!
            assertEquals(listOf(2L, 3L), fetched)
            assertEquals("Old preview", thread.messages[0].text)
            assertEquals("Full text of 3", thread.messages[2].text)
        }

    @Test
    fun `a failed fetch falls back to the preview`() =
        runTest {
            val id = insert(1, "<root@x>", text = "Stored preview")
            assertEquals(
                "Stored preview",
                repo
                    .thread(id) { null }!!
                    .messages
                    .single()
                    .text,
            )
        }

    @Test
    fun `a message without ids is a thread of one`() =
        runTest {
            val id = insert(1, null, inReplyTo = null)
            insert(2, null, inReplyTo = null)
            val thread = repo.thread(id, fullText)!!
            assertEquals(listOf(id), thread.messages.map { it.localId })
            assertEquals("local:$id", thread.key.root)
        }

    @Test
    fun `briefs are cached per thread state and only on success`() =
        runTest {
            val id = insert(1, "<root@x>")
            val failing: suspend (BriefThread) -> BriefOutcome = {
                computeCalls++
                BriefOutcome.Failed(BriefFailure.TIMEOUT)
            }
            assertEquals(BriefOutcome.Failed(BriefFailure.TIMEOUT), repo.brief(id, fullText, failing))
            assertEquals(
                BriefOutcome.Ready(ThreadBrief("gist <root@x>", "waiting_on_you")),
                repo.brief(id, fullText, compute),
            )
            val again = repo.brief(id, fullText, compute) as BriefOutcome.Ready
            assertTrue(again.cached)
            assertEquals(2, computeCalls)
        }

    @Test
    fun `a cached brief needs no server fetch, even when the server hangs`() =
        runTest {
            insert(1, "<root@x>")
            val last = insert(2, "<r1@x>", listOf("<root@x>"))
            repo.brief(last, fullText, compute)
            val fetchesBefore = fetched.size
            val hanging: suspend (Long) -> String? = { awaitCancellation() }
            val again = repo.brief(last, hanging, compute)
            assertEquals(BriefOutcome.Ready(ThreadBrief("gist <r1@x>", "waiting_on_you"), cached = true), again)
            assertEquals(fetchesBefore, fetched.size)
            assertEquals(1, computeCalls)
        }

    @Test
    fun `a new message in the thread invalidates the brief`() =
        runTest {
            val id = insert(1, "<root@x>")
            val before = repo.keys(id).first()
            repo.brief(id, fullText, compute)
            insert(2, "<r1@x>", listOf("<root@x>"))
            val after = repo.keys(id).first()
            assertNotEquals(before, after)
            val fresh = repo.brief(id, fullText, compute) as BriefOutcome.Ready
            assertEquals(false, fresh.cached)
            assertEquals("gist <r1@x>", fresh.brief.gist)
            // The older state's brief was dropped when the newer one was stored.
            assertNull(repo.cached(before!!))
        }

    @Test
    fun `a missing message gives no brief`() =
        runTest {
            assertEquals(BriefOutcome.Failed(BriefFailure.NO_MESSAGE), repo.brief(999, fullText, compute))
            assertNull(repo.keys(999).first())
        }
}
