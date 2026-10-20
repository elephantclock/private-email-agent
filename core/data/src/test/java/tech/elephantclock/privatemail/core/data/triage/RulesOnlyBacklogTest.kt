package tech.elephantclock.privatemail.core.data.triage

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.FolderEntity
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.messageEntity
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

/** The foreground AI drain's entry point: provisional rules-only verdicts get a second, AI look. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RulesOnlyBacklogTest {
    private val env = TriageTestEnv()
    private val db = env.db
    private val account = env.accountId
    private var uid = 1L

    @After
    fun tearDown() = env.close()

    private suspend fun setUp(autoFile: AutoFile = AutoFile.LABEL_ONLY) {
        env.setUp(autoFile)
        db.accounts().upsert(env.server.account.toEntity(createdAtMillis = 1))
        db.folders().upsert(listOf(FolderEntity(account.value, "Sent", "Sent", 7, SpecialUse.SENT, null, null)))
    }

    private suspend fun store(
        from: String,
        received: Long,
        folder: String = MailRepository.INBOX,
        to: List<String> = listOf(TriageTestEnv.EMAIL),
    ): MessageEntity {
        val ref = MessageRef(account, folder, 7, uid++)
        val header =
            MessageHeader(
                ref = ref,
                messageId = "<b$uid@x.example>",
                from = Address.of(from),
                subject = "Hello",
                sentAtMillis = received,
                receivedAtMillis = received,
                to = to.map { Address.of(it) },
            )
        val id = db.messages().insertAll(listOf(messageEntity(header, MessageBody(ref, "", false)))).single()
        return checkNotNull(db.messages().get(id))
    }

    private val money =
        TriageAi { _, _ ->
            Verdict.file(
                FolderTarget(FolderRole.MONEY),
                Importance.NORMAL,
                VerdictSource.AI,
                "AI: money",
                0.9f,
            )
        }

    @Test
    fun `rules-only verdicts are re-classified newest first, in batches, and the rest are left alone`() =
        runBlocking {
            setUp()
            val rows = (1..5).map { store("person$it@stranger.example", received = 1_000L * it) }
            val orchestrator = env.orchestrator() // background drain: no AI, provisional verdicts
            assertEquals(5, orchestrator.drain().triaged)

            val seen = mutableListOf<Long>()
            val ai =
                TriageAi { m, d ->
                    seen += m.header.receivedAtMillis
                    money.classify(m, d)
                }
            val first = orchestrator.reclassifyRulesOnly(BacklogCursor.START, 2, ai)
            assertEquals(2, first.considered)
            assertEquals(2, first.report.byAi)
            assertEquals(listOf(5_000L, 4_000L), seen)
            val second = orchestrator.reclassifyRulesOnly(first.next, 2, ai)
            assertEquals(listOf(5_000L, 4_000L, 3_000L, 2_000L), seen)
            val third = orchestrator.reclassifyRulesOnly(second.next, 2, ai)
            assertEquals(1, third.considered)
            assertEquals(0, orchestrator.reclassifyRulesOnly(third.next, 2, ai).considered)
            rows.forEach {
                val v = checkNotNull(db.verdicts().get(it.id))
                assertEquals(VerdictSource.AI, v.source)
                assertEquals(FolderRole.MONEY, v.targetRole)
            }
            assertEquals(
                "nothing provisional is left",
                0,
                orchestrator.reclassifyRulesOnly(BacklogCursor.START, 10, ai).considered,
            )
        }

    @Test
    fun `when the AI still can't answer the fallback stays, untouched`() =
        runBlocking {
            setUp()
            val row = store("person@stranger.example", received = 1_000)
            val orchestrator = env.orchestrator()
            orchestrator.drain()
            val before = checkNotNull(db.verdicts().get(row.id))
            val pass = orchestrator.reclassifyRulesOnly(BacklogCursor.START, 5, TriageAi.UNAVAILABLE)
            assertEquals(1, pass.considered)
            assertEquals(0, pass.report.byAi)
            assertEquals(before, db.verdicts().get(row.id))
        }

    @Test
    fun `a verdict the user set, rule verdicts and mail outside the Inbox are never revisited`() =
        runBlocking {
            setUp()
            val corrected = store("person@stranger.example", received = 3_000)
            val ruled = store("noreply@github.com", received = 2_000)
            val filed = store("other@stranger.example", received = 1_000, folder = "Money")
            val orchestrator = env.orchestrator()
            orchestrator.drain()
            db.verdicts().upsert(
                Verdict.keep(Importance.HIGH, VerdictSource.USER, "You kept it", 1f).toEntity(corrected.id, 1),
            )
            db.verdicts().upsert(
                Verdict.keep(Importance.NORMAL, VerdictSource.RULE, "A rule", 0f).toEntity(ruled.id, 1),
            )
            db.verdicts().upsert(
                Verdict
                    .keep(Importance.NORMAL, VerdictSource.RULE, "x${TriageOrchestrator.RULES_ONLY_MARK}", 0f)
                    .toEntity(filed.id, 1),
            )
            val pass = orchestrator.reclassifyRulesOnly(BacklogCursor.START, 10, money)
            assertEquals(0, pass.considered)
            assertEquals(VerdictSource.USER, db.verdicts().get(corrected.id)?.source)
        }

    @Test
    fun `a user correction made while the AI was thinking wins`() =
        runBlocking {
            setUp()
            val row = store("person@stranger.example", received = 1_000)
            val orchestrator = env.orchestrator()
            orchestrator.drain()
            val slow =
                TriageAi { m, d ->
                    db.verdicts().upsert(
                        Verdict.keep(Importance.HIGH, VerdictSource.USER, "Mine", 1f).toEntity(row.id, 2),
                    )
                    money.classify(m, d)
                }
            val pass = orchestrator.reclassifyRulesOnly(BacklogCursor.START, 10, slow)
            assertEquals(1, pass.considered)
            assertEquals(0, pass.report.triaged)
            assertEquals(VerdictSource.USER, db.verdicts().get(row.id)?.source)
        }

    @Test
    fun `AI verdicts still pass the protections and auto-file`() =
        runBlocking {
            setUp(AutoFile.LABEL_ONLY)
            store(TriageTestEnv.EMAIL, received = 10, folder = "Sent", to = listOf("anna@friends.example"))
            val friend = store("anna@friends.example", received = 2_000)
            val stranger = store("bob@stranger.example", received = 1_000)
            val orchestrator = env.orchestrator()
            orchestrator.drain()
            val discard = TriageAi { _, _ -> Verdict.discard(VerdictSource.AI, "AI: marketing", 0.95f) }
            val pass = orchestrator.reclassifyRulesOnly(BacklogCursor.START, 10, discard)
            assertEquals(2, pass.report.byAi)
            val kept = checkNotNull(db.verdicts().get(friend.id))
            assertEquals(VerdictAction.KEEP, kept.action)
            assertTrue(kept.reason, kept.reason.startsWith("Kept: you have written to this sender"))
            val gone = checkNotNull(db.verdicts().get(stranger.id))
            assertEquals(VerdictAction.DISCARD, gone.action)
            assertEquals("label only: nothing moves", false, gone.pendingMove)
        }

    @Test
    fun `a fallback the rules now decide gets the rule's verdict and leaves the backlog`() =
        runBlocking {
            setUp()
            val row = store("boss@stranger.example", received = 1_000)
            val orchestrator = env.orchestrator()
            orchestrator.drain()
            // The user made the sender a VIP since: the rules decide, the AI isn't asked.
            env.settings.set(account, env.settings.forAccount(account).copy(vips = setOf("boss@stranger.example")))
            val pass = orchestrator.reclassifyRulesOnly(BacklogCursor.START, 10, TriageAi.UNAVAILABLE)
            assertEquals(1, pass.report.triaged)
            assertEquals(0, pass.report.byAi)
            val v = checkNotNull(db.verdicts().get(row.id))
            assertEquals(Importance.URGENT, v.importance)
            assertEquals(0, orchestrator.reclassifyRulesOnly(BacklogCursor.START, 10, TriageAi.UNAVAILABLE).considered)
        }
}
