package tech.elephantclock.privatemail.core.data.triage

import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.DiscardDao
import tech.elephantclock.privatemail.core.data.db.MatcherType
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import java.util.Date
import java.util.Properties

/** T47 against GreenMail: restore and never-discard move real messages back and stick. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DiscardedActionsTest {
    private val env = TriageTestEnv()
    private val db get() = env.db
    private val actions get() = DiscardedActions(db, env.mover)

    @After
    fun tearDown() = env.close()

    private suspend fun discardNewsletter(autoFile: AutoFile): Long {
        env.setUp(autoFile)
        env.deliver(NEWSLETTER)
        env.repo.syncAccount(env.accountId)
        env.orchestrator().drain()
        val row =
            db
                .discarded()
                .discarded()
                .first()
                .single()
        assertEquals(VerdictAction.DISCARD, row.action)
        return row.id
    }

    @Test
    fun `restore moves a discarded message back to the Inbox and keeps it there`() =
        runBlocking {
            val id = discardNewsletter(AutoFile.ALL)
            assertTrue(MESSAGE_ID in env.serverMessageIds("Discarded"))

            val report = actions.restore(listOf(id))

            assertEquals(RestoreReport(restored = 1), report.copy(errors = emptyList()))
            assertTrue(MESSAGE_ID in env.serverMessageIds("INBOX"))
            assertFalse(MESSAGE_ID in env.serverMessageIds("Discarded"))
            val row = checkNotNull(db.messages().get(id))
            assertEquals(MailRepository.INBOX, row.folderPath)
            val verdict = checkNotNull(db.verdicts().get(id))
            assertEquals(VerdictAction.KEEP, verdict.action)
            assertEquals(VerdictSource.USER, verdict.source)
            assertTrue(
                db
                    .discarded()
                    .discarded()
                    .first()
                    .isEmpty(),
            )

            // Triage leaves the user's KEEP alone: a later drain and sync don't discard it again.
            env.repo.syncAccount(env.accountId)
            env.orchestrator().drain()
            assertTrue(MESSAGE_ID in env.serverMessageIds("INBOX"))
            assertEquals(VerdictSource.USER, db.verdicts().get(id)?.source)
        }

    @Test
    fun `restore of a label-only discard changes only the verdict`() =
        runBlocking {
            val id = discardNewsletter(AutoFile.LABEL_ONLY)
            assertTrue(MESSAGE_ID in env.serverMessageIds("INBOX"))
            // Label only: nothing may sign in to move anything.
            env.moveConnector = { error("restore of an Inbox message must not connect") }

            val report = actions.restore(listOf(id))

            assertEquals(1, report.restored)
            assertEquals(VerdictAction.KEEP, db.verdicts().get(id)?.action)
            assertTrue(MESSAGE_ID in env.serverMessageIds("INBOX"))
            assertTrue(env.serverMessageIds("Discarded").isEmpty())
        }

    @Test
    fun `a failed move leaves the message discarded and visible in the digest`() =
        runBlocking {
            val id = discardNewsletter(AutoFile.ALL)
            env.moveConnector = { MailResult.Failed(AuthError.PortBlockedOrTimeout("localhost", 993)) }

            val report = actions.restore(listOf(id))

            assertEquals(0, report.restored)
            assertEquals(1, report.failed)
            assertEquals(VerdictAction.DISCARD, db.verdicts().get(id)?.action)
            assertEquals(
                listOf(id),
                db
                    .discarded()
                    .discarded()
                    .first()
                    .map { it.id },
            )
            assertTrue(MESSAGE_ID in env.serverMessageIds("Discarded"))
        }

    @Test
    fun `never discard stores a sender rule, restores, and keeps the sender's next mail`() =
        runBlocking {
            val id = discardNewsletter(AutoFile.ALL)

            val (rule, report) = actions.neverDiscard(env.accountId, Address.of(SENDER), listOf(id))

            assertEquals(1, report.restored)
            assertTrue(MESSAGE_ID in env.serverMessageIds("INBOX"))
            val stored = db.learnedRules().forAccount(env.accountId.value).single { it.id == rule.id }
            assertEquals(MatcherType.SENDER, stored.matcherType)
            assertEquals(SENDER, stored.matcherValue)
            assertEquals(VerdictAction.KEEP, stored.action)
            assertEquals(env.accountId.value, stored.accountId)

            // The next newsletter from the same sender stays in the Inbox.
            env.deliver(NEWSLETTER)
            env.repo.syncAccount(env.accountId)
            env.orchestrator().drain()
            assertTrue(
                db
                    .discarded()
                    .discarded()
                    .first()
                    .isEmpty(),
            )
            assertFalse(MESSAGE_ID in env.serverMessageIds("Discarded"))
        }

    @Test
    fun `alerts held back for failed authentication are listed for the inbox badge`() =
        runBlocking {
            env.setUp(AutoFile.LABEL_ONLY)
            // A forged VIP (the rules engine lowers it) and a stranger the AI rates URGENT whose DKIM
            // fails (the orchestrator lowers it): neither notifies, both are listed.
            env.deliver("91-learned-vip-forged.eml")
            env.server.folder("INBOX").appendMessage(dkimFailStranger(), Flags(), Date())
            env.repo.syncAccount(env.accountId)
            env.orchestrator(urgentAi).drain()

            assertTrue("no notification", env.alerts.isEmpty())
            val held = db.discarded().suppressedAlerts().first()
            assertEquals(setOf("sarah.chen@bigclient.example", STRANGER), held.mapNotNull { it.fromEmail }.toSet())
            // Pins the wording of the engine and the orchestrator that the badge query depends on.
            val reasons = held.associate { it.fromEmail to checkNotNull(db.verdicts().get(it.id)).reason }
            assertTrue(reasons.toString(), reasons.getValue(STRANGER).endsWith(DiscardDao.SUPPRESSED_ALERT_SUFFIX))
            assertTrue(
                reasons.toString(),
                reasons.getValue("sarah.chen@bigclient.example").endsWith(DiscardDao.UNVERIFIED_VIP_SUFFIX),
            )
        }

    private val urgentAi = TriageAi { _, _ -> Verdict.keep(Importance.URGENT, VerdictSource.AI, "Looks urgent", 0.9f) }

    private fun dkimFailStranger() =
        MimeMessage(Session.getInstance(Properties())).apply {
            setHeader("Authentication-Results", "mx.ourco.example; spf=pass smtp.mailfrom=x.example; dkim=fail")
            setFrom(InternetAddress(STRANGER, "Pat Doe"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, TriageTestEnv.EMAIL)
            subject = "Question about the contract"
            setText("Could you call me today?")
            saveChanges()
        }

    private companion object {
        const val NEWSLETTER = "37-newsletter-mailchimp-en.eml"
        const val SENDER = "news@trailgear.example"
        const val MESSAGE_ID = "<golden-37@trailgear.example>"
        const val STRANGER = "pat@stranger.example"
    }
}
