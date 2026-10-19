package tech.elephantclock.privatemail.core.data.onboarding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.triage.RelationshipRepository
import tech.elephantclock.privatemail.core.model.Address

/** The carried-over safety item of T43, against GreenMail: 12 months of Sent headers, resumable. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SentHistoryBackfillTest {
    private val env = FirstRunTestEnv(pageSize = 7)

    @After
    fun tearDown() = env.close()

    /** Two messages older than the window, then 25 over the last ~11 months, oldest first (as a mailbox fills). */
    private suspend fun seedAndSync() {
        env.addAccount()
        env.sent("ancient@partner.example", daysAgo = 400)
        env.sent("ancient2@partner.example", daysAgo = 380)
        repeat(25) { env.sent("person$it@partner.example", daysAgo = 2 + (24 - it) * 13) }
        env.repo.syncAccount(env.accountId)
    }

    private fun sentRows() = env.count("SELECT COUNT(*) FROM messages WHERE folderPath = 'Sent'")

    @Test
    fun `stores 12 months of Sent headers, read-only and without bodies`() =
        runBlocking {
            seedAndSync()
            val synced = sentRows()
            assertTrue("the 30-day sync stores only recent Sent mail", synced in 1..3)
            assertEquals(BackfillStatus.PENDING, env.backfill.status(env.accountId))

            val result = env.backfill.run(env.accountId)

            assertEquals(BackfillResult.Complete, result)
            assertEquals(BackfillStatus.COMPLETE, env.backfill.status(env.accountId))
            assertEquals("every message in the window, none outside it", 25, sentRows())
            assertEquals(
                "headers only: no body text stored for backfilled rows",
                synced,
                env.count("SELECT COUNT(*) FROM messages WHERE folderPath = 'Sent' AND snippet != ''"),
            )
            assertEquals(0, env.count("SELECT COUNT(*) FROM messages WHERE fromEmail LIKE 'ancient%'"))
            // Read-only: the server's Sent folder is unchanged.
            assertEquals(27, env.serverSubjects("Sent").size)

            val index = RelationshipRepository(env.db).index(env.accountId, setOf(FirstRunTestEnv.EMAIL))
            assertTrue(
                "someone written to ~10 months ago is protected",
                index.hasSentTo(Address.of("person0@partner.example")),
            )
            assertFalse(index.hasSentTo(Address.of("ancient@partner.example")))
        }

    @Test
    fun `a cancelled backfill leaves rows and cursor in step, and resumes where it stopped`() =
        runBlocking {
            seedAndSync()
            var pages = 0
            try {
                env.backfill.run(env.accountId) { _, _ ->
                    pages++
                    if (pages == 2) throw CancellationException("user left")
                }
            } catch (_: CancellationException) {
                // expected
            }
            val entry = checkNotNull(env.store.get(env.accountId).backfill["Sent"])
            assertFalse(entry.done)
            assertEquals(14, entry.stored)
            assertEquals(BackfillStatus.PENDING, env.backfill.status(env.accountId))
            // Rows for both committed pages are there (the synced recent ones overlap the first page).
            assertEquals(14, sentRows())

            val seen = mutableListOf<Int>()
            assertEquals(BackfillResult.Complete, env.backfill.run(env.accountId) { done, _ -> seen += done })
            assertEquals("resumed after the cursor, not from the top", listOf(21, 25), seen)
            assertEquals("no duplicates", 25, sentRows())
            assertEquals(
                25,
                env.count("SELECT COUNT(DISTINCT messageIdHeader) FROM messages WHERE folderPath = 'Sent'"),
            )
        }

    @Test
    fun `a UIDVALIDITY change on Sent closes the gate again`() =
        runBlocking {
            seedAndSync()
            assertEquals(BackfillResult.Complete, env.backfill.run(env.accountId))
            // What the sync records when the server resets the folder (it then drops the old rows).
            env.db.folders().updateSyncState(env.server.account.id.value, "Sent", 999_999, 1, 1)
            assertEquals(BackfillStatus.PENDING, env.backfill.status(env.accountId))
        }

    @Test
    fun `an account without a Sent folder never opens the gate`() =
        runBlocking {
            env.addAccount()
            env.repo.syncAccount(env.accountId)
            assertEquals(BackfillResult.NoSentFolder, env.backfill.run(env.accountId))
            assertEquals(BackfillStatus.NO_SENT_FOLDER, env.backfill.status(env.accountId))
        }

    @Test
    fun `waits for the sync when Sent was never synced`() =
        runBlocking {
            env.addAccount()
            env.sent("someone@partner.example", daysAgo = 100)
            env.db.folders().insertNew(
                listOf(
                    tech.elephantclock.privatemail.core.data.db.FolderEntity(
                        env.accountId.value,
                        "Sent",
                        "Sent",
                        uidValidity = 0,
                        specialUse = tech.elephantclock.privatemail.core.model.SpecialUse.SENT,
                        role = null,
                        description = null,
                    ),
                ),
            )
            assertTrue(env.backfill.run(env.accountId) is BackfillResult.Interrupted)
            assertEquals(0, sentRows())
        }
}
