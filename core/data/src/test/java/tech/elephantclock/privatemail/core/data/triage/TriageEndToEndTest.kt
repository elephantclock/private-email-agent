package tech.elephantclock.privatemail.core.data.triage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.VerdictEntity
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

/**
 * T24 end to end: 20 golden fixtures delivered to GreenMail, synced, triaged with a hostile
 * fake AI (it always says "discard") and acted on.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TriageEndToEndTest {
    private val env = TriageTestEnv()
    private val hostileAi = TriageAi { _, _ -> Verdict.discard(VerdictSource.AI, "The model says discard", 0.9f) }

    @After
    fun tearDown() = env.close()

    private fun prepare(autoFile: AutoFile) =
        runBlocking {
            env.setUp(autoFile)
            env.seedSent()
            FIXTURES.forEach(env::deliver)
            env.repo.syncAccount(env.accountId)
        }

    private suspend fun stored(): Map<String, Pair<MessageEntity, VerdictEntity?>> {
        val rows = db().triage().untriagedIn(MailRepository.INBOX, 1000)
        assertTrue("every Inbox message has a verdict", rows.isEmpty())
        return FIXTURES.associateWith { f ->
            val row = checkNotNull(byMessageId(messageId(f))) { "no row for $f" }
            row to db().verdicts().get(row.id)
        }
    }

    private fun db() = env.db

    private suspend fun byMessageId(id: String): MessageEntity? = allRows().firstOrNull { it.messageIdHeader == id }

    private suspend fun allRows(): List<MessageEntity> =
        db()
            .query("SELECT id FROM messages", null)
            .use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }.mapNotNull { db().messages().get(it) }

    @Test
    fun `verdicts match the golden file, moves follow them and protected mail is never thrown away`() =
        runBlocking {
            prepare(AutoFile.ALL)
            assertTrue(
                "Sent is synced for the relationship index",
                db().triage().sentFolders(env.accountId.value).isNotEmpty(),
            )

            val report = env.orchestrator(hostileAi).drain()
            assertEquals(FIXTURES.size, report.triaged)
            assertEquals(0, report.moves.failed)

            val result = stored()
            for ((fixture, pair) in result) {
                val (row, v) = pair
                val verdict = checkNotNull(v) { fixture }
                val want = env.expected.getValue(fixture)
                if (want["decision"]?.jsonPrimitive?.content == "needs-ai") {
                    val protected =
                        want
                            .getValue("protected")
                            .jsonPrimitive.content
                            .toBoolean()
                    val expectedAction = if (protected) VerdictAction.KEEP else VerdictAction.DISCARD
                    assertEquals("$fixture: hostile AI vs protection", expectedAction, verdict.action)
                } else {
                    assertEquals(fixture, want.getValue("action").jsonPrimitive.content, verdict.action.name)
                    assertEquals(fixture, want.getValue("target").jsonPrimitive.content, verdict.targetRole.name)
                    assertEquals(fixture, want.getValue("importance").jsonPrimitive.content, verdict.importance.name)
                }
                assertFalse("$fixture: no move left pending", verdict.pendingMove)
                if (verdict.action == VerdictAction.KEEP) {
                    assertEquals("$fixture stays in the Inbox", "INBOX", row.folderPath)
                    assertEquals(null, row.movedAtMillis)
                } else {
                    assertTrue("$fixture moved: ${row.folderPath}", row.folderPath != "INBOX")
                    assertNotNull("$fixture: move time recorded", row.movedAtMillis)
                }
            }

            // On the server: Discarded and Junk hold nothing the user wrote to, replied to or marked VIP.
            val protectedIds =
                FIXTURES
                    .filter { f ->
                        val want = env.expected.getValue(f)
                        want["protected"]?.jsonPrimitive?.content == "true" ||
                            want["action"]?.jsonPrimitive?.content == "KEEP"
                    }.map(::messageId)
                    .toSet()
            val thrownAway = env.serverMessageIds("Discarded") + env.serverMessageIds("Junk")
            assertTrue(
                "protected mail discarded: ${thrownAway intersect protectedIds}",
                (
                    thrownAway intersect
                        protectedIds
                ).isEmpty(),
            )
            val kept =
                result
                    .filterValues { it.second?.action == VerdictAction.KEEP }
                    .keys
                    .map(::messageId)
                    .toSet()
            assertEquals(kept, env.serverMessageIds("INBOX"))

            // Undo entries for every move, with the destination's UIDVALIDITY.
            val moved = result.values.count { it.second?.action != VerdictAction.KEEP }
            val undo = undoEntries()
            assertEquals(moved, undo.size)
            assertTrue(undo.all { it.toUidValidity > 0 && it.localId != null })

            // Alerts: the VIP and the security code / duplicate charge, not the forged VIP.
            val alerted = env.alerts.map { a -> result.entries.first { it.value.first.id == a.localId }.key }.toSet()
            assertEquals(setOf(VIP, OTP, DUPLICATE_CHARGE), alerted)
            assertEquals(Importance.URGENT, env.alerts.first { it.subject.startsWith("Board deck") }.importance)

            // The first sync of a folder triage moved mail into keeps those rows and their verdicts.
            val discardedBefore = result.filterValues { it.second?.action == VerdictAction.DISCARD }.size
            env.repo.syncAccount(env.accountId, listOf("Discarded", "Junk", "Money"))
            val after = stored()
            assertEquals(discardedBefore, after.values.count { it.second?.action == VerdictAction.DISCARD })
            assertEquals(result.mapValues { it.value.first.id }, after.mapValues { it.value.first.id })
        }

    @Test
    fun `label only by default - verdicts are stored but nothing moves`() =
        runBlocking {
            prepare(AutoFile.LABEL_ONLY)
            val report = env.orchestrator(hostileAi).drain()
            assertEquals(FIXTURES.size, report.triaged)
            assertEquals(MoveOutcome(), report.moves)
            val result = stored()
            assertTrue(result.values.all { it.first.folderPath == "INBOX" && it.second?.pendingMove == false })
            assertEquals(FIXTURES.map(::messageId).toSet(), env.serverMessageIds("INBOX"))
            assertTrue(undoEntries().isEmpty())
            assertTrue(env.serverMessageIds("Discarded").isEmpty() && env.serverMessageIds("Junk").isEmpty())
        }

    @Test
    fun `undo puts a batch back and the next drain leaves it alone`() =
        runBlocking {
            prepare(AutoFile.ALL)
            val report = env.orchestrator(hostileAi).drain()
            val batch = report.moves.batchIds.first()
            val entries = db().undo().batch(env.accountId.value, batch)
            val undone = checkNotNull(env.mover.undo(env.server.account, batch))
            assertEquals(entries.size, undone.restored.size)
            entries.forEach { e ->
                val row = checkNotNull(db().messages().get(e.localId!!))
                assertEquals("INBOX", row.folderPath)
                assertEquals(null, row.movedAtMillis)
                // Back in Priority: the user's undo overrides the verdict.
                val verdict = checkNotNull(db().verdicts().get(row.id))
                assertEquals(VerdictAction.KEEP, verdict.action)
                assertEquals(VerdictSource.USER, verdict.source)
            }
            assertEquals(
                0,
                env
                    .orchestrator(hostileAi)
                    .drain()
                    .moves.moved,
            )
            entries.forEach { assertTrue(env.serverMessageIds("INBOX").contains(it.messageIdHeader)) }
        }

    private suspend fun undoEntries() =
        db()
            .query("SELECT batchId FROM undo_log", null)
            .use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }.flatMap { db().undo().batch(env.accountId.value, it) }

    private fun messageId(fixture: String): String = "<golden-${fixture.substringBefore('-')}@${domainOf(fixture)}>"

    private fun domainOf(fixture: String): String {
        val text = requireNotNull(javaClass.getResourceAsStream("/fixtures/$fixture")).use { it.reader().readText() }
        return Regex("Message-ID: <golden-\\d+@([^>]+)>").find(text)!!.groupValues[1]
    }

    private companion object {
        const val VIP = "05-people-vip.eml"
        const val OTP = "58-otp-en.eml"
        const val DUPLICATE_CHARGE = "17-duplicate-payment-alert.eml"

        val FIXTURES =
            listOf(
                "01-people-ru-colleague.eml",
                "04-people-en-replied-thread.eml",
                VIP,
                "06-people-vip-forged.eml",
                "08-people-es-plus-tag.eml",
                "10-people-contact-newsletter.eml",
                "11-invoice-en-noreply.eml",
                DUPLICATE_CHARGE,
                "21-order-shipped-en.eml",
                "28-github-review.eml",
                "37-newsletter-mailchimp-en.eml",
                "43-marketing-free-delivery.eml",
                "46-learned-discard-domain.eml",
                "47-learned-discard-blocked-by-sent-to.eml",
                "48-learned-file-custom.eml",
                OTP,
                "64-phish-paypal-typo.eml",
                "71-phish-forged-lower-auth-results.eml",
                "74-phish-lookalike-frequent-domain.eml",
                // Junk only when the account's trusted authserv-id setting is applied.
                "73-phish-trusted-below-forged.eml",
            )
    }
}
