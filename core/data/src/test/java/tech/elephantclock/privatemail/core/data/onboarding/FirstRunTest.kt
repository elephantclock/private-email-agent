package tech.elephantclock.privatemail.core.data.onboarding

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.OnboardingStep
import tech.elephantclock.privatemail.core.data.triage.AutoFile
import tech.elephantclock.privatemail.core.model.VerdictAction

/**
 * T43 against GreenMail: nothing moves on the server before Apply, Apply needs the backfill,
 * and someone the user wrote to six months ago is never discarded.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FirstRunTest {
    private val env = FirstRunTestEnv()

    @After
    fun tearDown() = env.close()

    private suspend fun seed() {
        env.addAccount()
        env.sent("olga@partner.example", daysAgo = 180) // only the backfill knows her
        env.sent("recent@partner.example", daysAgo = 3)
        env.newsletter("olga@partner.example", "Partner news from Olga")
        env.newsletter("deals@shop.example", "Weekend deals")
    }

    private suspend fun verdictFor(subject: String): VerdictAction? {
        val id =
            env.db.query("SELECT id FROM messages WHERE subject = ?", arrayOf(subject)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            } ?: return null
        return env.db
            .verdicts()
            .get(id)
            ?.action
    }

    private fun assertNothingMoved() {
        assertEquals("the mover never signed in", 0, env.moverSignIns.get())
        assertEquals(setOf("Partner news from Olga", "Weekend deals"), env.serverSubjects("INBOX").toSet())
        assertFalse("no Discarded folder was created", env.serverHasFolder("Discarded"))
        assertEquals(0, env.count("SELECT COUNT(*) FROM undo_log"))
        assertEquals(0, env.count("SELECT COUNT(*) FROM verdicts WHERE pendingMove = 1"))
    }

    @Test
    fun `nothing moves before Apply, and the review re-triages with the full Sent history`() =
        runBlocking {
            seed()
            // The app's foreground triage can run before the first run (IDLE syncs the Inbox only).
            env.repo.syncAccount(env.accountId, listOf(MailRepository.INBOX))
            env.labelOnly.drain()
            assertEquals(
                "without the backfill Olga's newsletter looks discardable",
                VerdictAction.DISCARD,
                verdictFor("Partner news from Olga"),
            )

            assertEquals(FirstRunResult.Done, env.firstRun.sort(env.accountId))

            assertEquals(VerdictAction.KEEP, verdictFor("Partner news from Olga"))
            assertEquals(VerdictAction.DISCARD, verdictFor("Weekend deals"))
            val review = env.firstRun.review(env.accountId)
            assertEquals(1, review.keepCount)
            assertEquals(1, review.discardCount)
            assertEquals(0, review.untriaged)
            assertNothingMoved()
            assertEquals(AutoFile.LABEL_ONLY, env.settings.forAccount(env.accountId).autoFile)
        }

    @Test
    fun `Apply refuses until the backfill is complete, whatever the stored switch says`() =
        runBlocking {
            seed()
            env.repo.syncAccount(env.accountId)
            env.labelOnly.drain()
            env.store.update(env.accountId) { it.copy(autoFileRequested = true) }

            assertEquals(AutoFile.LABEL_ONLY, env.settings.forAccount(env.accountId).autoFile)
            assertEquals(FirstRunResult.NotReady(BackfillStatus.PENDING), env.firstRun.apply(env.accountId))
            assertNothingMoved()

            assertEquals(BackfillResult.Complete, env.backfill.run(env.accountId))
            assertTrue(
                env.settings
                    .forAccount(env.accountId)
                    .autoFile.enabled,
            )
        }

    @Test
    fun `Apply moves the discards, keeps protected people and turns auto-file on`() =
        runBlocking {
            seed()
            assertEquals(FirstRunResult.Done, env.firstRun.sort(env.accountId))

            val result = env.firstRun.apply(env.accountId)

            assertTrue(result is FirstRunResult.Applied)
            assertEquals(1, (result as FirstRunResult.Applied).outcome.moved)
            assertEquals(listOf("Partner news from Olga"), env.serverSubjects("INBOX"))
            assertEquals(listOf("Weekend deals"), env.serverSubjects("Discarded"))
            val prefs = env.store.get(env.accountId)
            assertTrue(prefs.autoFileRequested)
            assertEquals(OnboardingStep.DONE, prefs.step)
            assertTrue(
                env.settings
                    .forAccount(env.accountId)
                    .autoFile.enabled,
            )
        }

    @Test
    fun `Apply re-checks protections even when a stale verdict says discard`() =
        runBlocking {
            seed()
            env.repo.syncAccount(env.accountId)
            env.labelOnly.drain() // Olga: DISCARD, made before the backfill
            assertEquals(BackfillResult.Complete, env.backfill.run(env.accountId))

            val result = env.firstRun.apply(env.accountId) as FirstRunResult.Applied

            assertEquals(1, result.skippedProtected)
            assertEquals(VerdictAction.KEEP, verdictFor("Partner news from Olga"))
            assertTrue("Partner news from Olga" in env.serverSubjects("INBOX"))
            assertEquals(listOf("Weekend deals"), env.serverSubjects("Discarded"))
        }

    @Test
    fun `Label only moves nothing and keeps auto-file off`() =
        runBlocking {
            seed()
            env.firstRun.sort(env.accountId)

            env.firstRun.labelOnlyDone(env.accountId)

            assertNothingMoved()
            assertEquals(OnboardingStep.DONE, env.store.get(env.accountId).step)
            assertEquals(AutoFile.LABEL_ONLY, env.settings.forAccount(env.accountId).autoFile)
        }

    @Test
    fun `a message the user keeps in the drill-down is not moved by Apply`() =
        runBlocking {
            seed()
            env.firstRun.sort(env.accountId)
            val discard =
                env.firstRun
                    .review(env.accountId)
                    .discard
                    .single()
            val item = env.firstRun.items(env.accountId, discard).single()
            assertEquals("Weekend deals", item.subject)

            env.firstRun.keep(item.id)
            val result = env.firstRun.apply(env.accountId) as FirstRunResult.Applied

            assertEquals(0, result.outcome.moved)
            assertEquals(2, env.serverSubjects("INBOX").size)
        }
}
