package tech.elephantclock.privatemail.feature.reply

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.FolderEntity
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures

/** Style sample and parent text for drafts, from the real schema. */
@RunWith(RobolectricTestRunner::class)
class ReplyAiSourceTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @After
    fun tearDown() = db.close()

    @Test
    fun `quoted history and signatures are removed`() {
        assertEquals(
            "Sounds good.",
            QuoteText.strip("Sounds good.\n\nOn Mon, 1 Jan 2026, Ann <a@x.example> wrote:\n> Lunch?"),
        )
        assertEquals("Да, давайте.", QuoteText.strip("Да, давайте.\n\n> Встречаемся?\nИван Петров пишет:\nстарое"))
        assertEquals("New text", QuoteText.strip("> old\nNew text\n-----Original Message-----\nold"))
        assertEquals("Hi Ann,\nYes.", QuoteText.dropSignature("Hi Ann,\nYes.\n-- \nSam Lee\n+44 20 7946 0000"))
    }

    @Test
    fun `the style sample takes a few recent snippets, capped and redacted`() {
        val sent =
            listOf(
                "Hi Ann,\nYour code is fine: verification code 482913. Talk soon,\nSam",
                "ok",
                "Hi Ann,\nYour code is fine: verification code 482913. Talk soon,\nSam",
                "Dear Mr Petrov,\nthank you for the offer. " + "x".repeat(600),
                "Hello team,\nplease find the notes below.\nSam\n-- \nSam Lee, phone 123",
                "Fourth one that should not be used at all, it is old.",
            )
        val sample = StyleSample.select(sent)!!
        assertFalse(sample.contains("482913"))
        assertTrue(sample.contains("⟦CODE_1⟧"))
        assertFalse(sample.contains("Fourth one"))
        assertFalse(sample.contains("Sam Lee, phone"))
        assertEquals(3, sample.split("\n---\n").size)
        assertTrue(sample.length <= StyleSample.MAX_CHARS)
        assertNull(StyleSample.select(listOf("ok", "")))
    }

    @Test
    fun `material is the parent's text and the account's Sent snippets, newest first`() =
        runTest {
            ReadingFixtures.accounts.forEach { db.accounts().upsert(it) }
            val work = ReadingFixtures.work
            db.folders().upsert(
                listOf(
                    FolderEntity(work.id.value, "Sent Items", "Sent", 1, SpecialUse.SENT, null, null),
                    FolderEntity(work.id.value, "INBOX", "Inbox", 1, SpecialUse.INBOX, null, null),
                ),
            )
            db.messages().insertAll(
                listOf(
                    ReadingFixtures.entity(1, "Ivan", "Встреча", "Можем во вторник?\n> старое", 5, work),
                    ReadingFixtures.entity(
                        2,
                        "Sam",
                        "Re: a",
                        "Older sent reply from me, kind regards",
                        60,
                        work,
                        "Sent Items",
                    ),
                    ReadingFixtures.entity(
                        3,
                        "Sam",
                        "Re: b",
                        "Newest sent reply from me, best wishes",
                        10,
                        work,
                        "Sent Items",
                    ),
                    ReadingFixtures.entity(4, "Other", "c", "Inbox mail is not my writing style here", 1, work),
                ),
            )
            val m = RoomReplyAiSource(db).material(1)!!
            assertEquals("Встреча", m.thread.subject)
            assertEquals(
                "Можем во вторник?",
                m.thread.messages
                    .single()
                    .text,
            )
            assertEquals(
                "Newest sent reply from me, best wishes\n---\nOlder sent reply from me, kind regards",
                m.styleSample,
            )
            assertNull(RoomReplyAiSource(db).material(99))
        }
}
