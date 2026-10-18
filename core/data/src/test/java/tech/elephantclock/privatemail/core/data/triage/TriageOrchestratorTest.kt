package tech.elephantclock.privatemail.core.data.triage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TriageOrchestratorTest {
    private val env = TriageTestEnv()
    private val db = env.db
    private val account = env.accountId
    private var uid = 1L

    @After
    fun tearDown() = env.close()

    private suspend fun withAccount(createdAt: Long = 1) {
        db.accounts().upsert(env.server.account.toEntity(createdAtMillis = createdAt))
        db.folders().upsert(listOf(FolderEntity(account.value, "Sent", "Sent", 7, SpecialUse.SENT, null, null)))
    }

    /** Stores one message directly (no server). */
    private suspend fun store(
        from: String,
        folder: String = MailRepository.INBOX,
        subject: String = "Hello",
        to: List<String> = listOf(TriageTestEnv.EMAIL),
        received: Long = System.currentTimeMillis(),
        flags: Set<MessageFlag> = emptySet(),
        headers: Map<String, List<String>> = emptyMap(),
        messageId: String = "<m$uid@x.example>",
    ): MessageEntity {
        val ref = MessageRef(account, folder, 7, uid++)
        val header =
            MessageHeader(
                ref = ref,
                messageId = messageId,
                from = Address.of(from),
                subject = subject,
                sentAtMillis = received,
                receivedAtMillis = received,
                to = to.map { Address.of(it) },
                flags = flags,
                headers = headers,
            )
        val id = db.messages().insertAll(listOf(messageEntity(header, MessageBody(ref, "", false)))).single()
        return checkNotNull(db.messages().get(id))
    }

    private suspend fun verdictOf(row: MessageEntity) = db.verdicts().get(row.id)

    @Test
    fun `a drain ends even with mail outside the Inbox and no AI, and leaves rules-only mail in Priority`() =
        runBlocking {
            env.setUp(AutoFile.LABEL_ONLY)
            withAccount()
            val inbox = (1..120).map { store("person$it@stranger.example") }
            val sent = store(TriageTestEnv.EMAIL, folder = "Sent", to = listOf("x@y.example"))
            val report = withTimeout(10_000) { env.orchestrator().drain() }
            assertEquals(120, report.triaged)
            inbox.forEach {
                val v = checkNotNull(verdictOf(it))
                assertEquals(VerdictAction.KEEP, v.action)
                assertEquals("provisional: the AI queue may classify it later", 0f, v.confidence)
                assertTrue(v.reason, v.reason.endsWith("(AI unavailable)"))
            }
            assertNull("Sent mail is not triaged", verdictOf(sent))
            assertEquals(0, env.orchestrator().drain().triaged)
        }

    @Test
    fun `an AI that throws counts as unavailable`() =
        runBlocking {
            env.setUp(AutoFile.LABEL_ONLY)
            withAccount()
            val row = store("person@stranger.example")
            val report = env.orchestrator(TriageAi { _, _ -> error("model crashed") }).drain()
            assertEquals(0, report.byAi)
            assertEquals(VerdictAction.KEEP, verdictOf(row)?.action)
        }

    @Test
    fun `the AI can't discard or junk someone the user wrote to, and its verdict is used otherwise`() =
        runBlocking {
            env.setUp(AutoFile.LABEL_ONLY)
            withAccount()
            store(TriageTestEnv.EMAIL, folder = "Sent", to = listOf("anna+work@friends.example"))
            val friend = store("anna@friends.example", subject = "Dinner?")
            val stranger = store("bob@stranger.example", subject = "Dinner?")
            val ai = TriageAi { _, _ -> Verdict.junk(VerdictSource.AI, "The model says junk", 0.8f) }
            val report = env.orchestrator(ai).drain()
            assertEquals(2, report.byAi)
            assertEquals(VerdictAction.KEEP, verdictOf(friend)?.action)
            assertTrue(verdictOf(friend)!!.reason.startsWith("Kept: you have written to this sender"))
            assertEquals(VerdictAction.JUNK, verdictOf(stranger)?.action)
            assertEquals(VerdictSource.AI, verdictOf(stranger)?.source)
        }

    @Test
    fun `alerts only for new unread Inbox mail that passes authentication`() =
        runBlocking {
            val now = System.currentTimeMillis()
            env.setUp(AutoFile.LABEL_ONLY)
            withAccount(createdAt = now - 60_000)
            env.settings.set(account, AccountTriageSettings(vips = setOf("boss@ourco.example")))
            val fresh = store("boss@ourco.example", subject = "Call me")
            store("boss@ourco.example", subject = "Read already", flags = setOf(MessageFlag.SEEN))
            store("boss@ourco.example", subject = "Backfill", received = now - 3_600_000)
            // Fails authentication, but not badly enough for Junk.
            val failed = "mx.ourco.example; spf=pass smtp.mailfrom=x; dkim=fail; dmarc=none"
            val code =
                store(
                    "noreply@bank.example",
                    subject = "Your verification code is 482193",
                    headers = mapOf("authentication-results" to listOf(failed)),
                )
            env.orchestrator().drain()
            assertEquals(listOf(fresh.id), env.alerts.map { it.localId })
            assertEquals(Importance.URGENT, env.alerts.single().importance)
            val codeVerdict = checkNotNull(verdictOf(code))
            assertEquals(Importance.NORMAL, codeVerdict.importance)
            assertTrue(codeVerdict.reason, codeVerdict.reason.endsWith("(no alert: failed authentication)"))
        }

    @Test
    fun `a move that fails stays pending and goes through on the next drain`() =
        runBlocking {
            env.setUp(AutoFile.ALL)
            env.deliver(NEWSLETTER)
            env.repo.syncAccount(account)
            env.moveConnector = { MailResult.Failed(AuthError.PortBlockedOrTimeout("localhost", 993)) }
            val first = env.orchestrator().drain()
            assertEquals(1, first.moves.failed)
            val row = db.triage().pendingMoves(account.value, MailRepository.INBOX, 10).single()
            assertEquals(VerdictAction.DISCARD, row.verdict?.action)

            env.moveConnector = env.server.connector()
            val second = env.orchestrator().drain()
            assertEquals(1, second.moves.moved)
            val moved = checkNotNull(db.messages().get(row.message.id))
            assertEquals("Discarded", moved.folderPath)
            assertNotNull(moved.movedAtMillis)
            assertEquals(false, verdictOf(moved)?.pendingMove)
            assertTrue(env.serverMessageIds("INBOX").isEmpty())
        }

    @Test
    fun `a wrong password stops moves until the user fixes it, instead of retrying the sign-in`() =
        runBlocking {
            env.setUp(AutoFile.ALL)
            env.deliver(NEWSLETTER)
            env.repo.syncAccount(account)
            var signIns = 0
            env.moveConnector = {
                signIns++
                MailResult.Failed(AuthError.WrongCredentials)
            }
            env.orchestrator().drain()
            assertEquals(1, signIns)
            assertEquals("WrongCredentials", db.accounts().get(account.value)?.authProblem)

            env.deliver(NEWSLETTER.replace("37", "38").replace("mailchimp-en", "sendgrid-de"))
            db.accounts().setAuthProblem(account.value, null)
            env.repo.syncAccount(account)
            db.accounts().setAuthProblem(account.value, "WrongCredentials")
            val report = env.orchestrator().drain()
            assertEquals("no sign-in while the account waits for the user", 1, signIns)
            assertEquals(2, report.moves.failed)
            assertEquals(2, db.triage().pendingMoves(account.value, MailRepository.INBOX, 10).size)
        }

    @Test
    fun `a pending move is dropped when the user turned auto-file off`() =
        runBlocking {
            env.setUp(AutoFile.ALL)
            env.deliver(NEWSLETTER)
            env.repo.syncAccount(account)
            env.moveConnector = { MailResult.Failed(AuthError.PortBlockedOrTimeout("localhost", 993)) }
            env.orchestrator().drain()
            env.moveConnector = env.server.connector()
            env.settings.set(account, env.settings.forAccount(account).copy(autoFile = AutoFile.LABEL_ONLY))
            assertEquals(
                0,
                env
                    .orchestrator()
                    .drain()
                    .moves.moved,
            )
            assertTrue(db.triage().pendingMoves(account.value, MailRepository.INBOX, 10).isEmpty())
            assertEquals(1, env.serverMessageIds("INBOX").size)
        }

    @Test
    fun `discarded mail goes to Trash after 30 days, using the stored discard time`() =
        runBlocking {
            env.setUp(AutoFile.ALL)
            env.server.folder("Trash")
            env.deliver(NEWSLETTER)
            env.repo.syncAccount(account)
            assertEquals(
                1,
                env
                    .orchestrator()
                    .drain()
                    .moves.moved,
            )
            assertEquals(0, env.mover.purgeDiscarded(env.server.account))

            val later =
                TriageMover(db, env.repo, env.server.connector(), clock = {
                    System.currentTimeMillis() +
                        31 * DAY
                })
            assertEquals(1, later.purgeDiscarded(env.server.account))
            assertEquals(1, env.serverMessageIds("Trash").size)
            assertTrue(env.serverMessageIds("Discarded").isEmpty())
            assertTrue("the row is dropped", db.triage().discardedBefore(account.value, Long.MAX_VALUE).isEmpty())
        }

    @Test
    fun `run triages mail as the sync stores it`() =
        runBlocking {
            env.setUp(AutoFile.LABEL_ONLY)
            val job = launch(Dispatchers.Default) { env.orchestrator().run() }
            delay(200) // subscribed
            env.deliver(NEWSLETTER)
            env.repo.syncAccount(account)
            withTimeout(5_000) {
                while (db.triage().untriagedIn(MailRepository.INBOX, 1).isNotEmpty() || noVerdicts()) delay(50)
            }
            job.cancelAndJoin()
        }

    private suspend fun noVerdicts(): Boolean =
        db
            .messages()
            .byServerRef(account.value, "INBOX", db.folders().get(account.value, "INBOX")?.uidValidity ?: 0, 1)
            ?.let { verdictOf(it) == null } ?: true

    private companion object {
        const val NEWSLETTER = "37-newsletter-mailchimp-en.eml"
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
