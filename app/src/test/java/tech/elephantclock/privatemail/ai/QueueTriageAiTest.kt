package tech.elephantclock.privatemail.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiCall
import tech.elephantclock.privatemail.core.ai.AiClassification
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.ClassifyRequest
import tech.elephantclock.privatemail.core.ai.FakeAiEngine
import tech.elephantclock.privatemail.core.ai.queue.AiPriority
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.data.triage.AccountTriageSettings
import tech.elephantclock.privatemail.core.data.triage.FolderGuide
import tech.elephantclock.privatemail.core.data.triage.InMemoryTriageSettings
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.people.Protection
import tech.elephantclock.privatemail.core.triage.rules.Decision

class QueueTriageAiTest {
    private val account = AccountId("acc")
    private val message =
        MailItem(
            MessageHeader(
                ref = MessageRef(account, "INBOX", 1, 9),
                messageId = "<m9@x.example>",
                from = Address.of("shop@x.example"),
                subject = "Sale",
                sentAtMillis = 1,
                receivedAtMillis = 1,
            ),
        )
    private val decision = Decision.NeedsAi("no rule matched", Signals(hasListUnsubscribe = true), Protection.NONE)
    private val settings = InMemoryTriageSettings()
    private var canRun = true
    private val requests = mutableListOf<ClassifyRequest>()

    private fun engine(answer: (ClassifyRequest) -> AiResult<AiClassification>) =
        FakeAiEngine().apply {
            classifier = { _, r ->
                requests += r
                answer(r)
            }
        }

    @Test
    fun `nothing is asked when the model can't run (background or unavailable)`() =
        runTest {
            val engine = engine { error("must not be called") }
            canRun = false
            val ai = QueueTriageAi(AiQueue(engine, backgroundScope), settings, { canRun }, AiPriority.FOREGROUND)
            assertNull(ai.classify(message, decision))
            assertTrue(engine.calls.none { it == AiCall.CLASSIFY })
        }

    @Test
    fun `an answer becomes an AI verdict, and an unsure discard is kept`() =
        runTest {
            var confidence = 0.95f
            val engine =
                engine { r ->
                    AiResult.Ok(AiClassification(r.folder("discarded")!!.target, Importance.LOW, confidence, "promo"))
                }
            val queue = AiQueue(engine, backgroundScope)
            val ai = QueueTriageAi(queue, settings, { canRun }, AiPriority.BULK)
            val sure = ai.classify(message, decision)!!
            assertEquals(VerdictAction.DISCARD, sure.action)
            assertEquals(VerdictSource.AI, sure.source)
            assertEquals(
                "the rules' signals reach the prompt",
                true,
                requests
                    .single()
                    .items
                    .single()
                    .signals
                    ?.isBulk,
            )

            confidence = 0.5f
            queue.clearCache()
            assertEquals(VerdictAction.KEEP, ai.classify(message, decision)!!.action)
        }

    @Test
    fun `a failure means rules only`() =
        runTest {
            val engine =
                engine {
                    AiResult.Failed(
                        tech.elephantclock.privatemail.core.ai.AiFailure
                            .Timeout(5_000),
                    )
                }
            val ai = QueueTriageAi(AiQueue(engine, backgroundScope), settings, { canRun }, AiPriority.FOREGROUND)
            assertNull(ai.classify(message, decision))
        }

    @Test
    fun `changed folder descriptions are not answered from the cache`() =
        runTest {
            val engine =
                engine { r ->
                    AiResult.Ok(AiClassification(r.folders.first().target, Importance.NORMAL, 0.9f, "x"))
                }
            val ai = QueueTriageAi(AiQueue(engine, backgroundScope), settings, { canRun }, AiPriority.FOREGROUND)
            ai.classify(message, decision)
            ai.classify(message, decision)
            assertEquals("cached", 1, requests.size)
            settings.set(
                account,
                AccountTriageSettings(folders = listOf(FolderGuide(FolderTarget.Priority, "Only my boss"))),
            )
            ai.classify(message, decision)
            assertEquals(2, requests.size)
        }

    @Test
    fun `offered folders are priority, enabled value and custom folders, then discarded and junk`() {
        val guides =
            listOf(
                FolderGuide(FolderTarget.Priority, "Needs me"),
                FolderGuide(FolderTarget(FolderRole.MONEY), "Invoices"),
                FolderGuide(FolderTarget(FolderRole.WORK), "Tools", enabled = false),
                FolderGuide(FolderTarget.custom("Support"), "Helpdesk tickets about payments"),
                FolderGuide(FolderTarget.custom("Old"), "Off", enabled = false),
                FolderGuide(FolderTarget.custom("School"), "Kids"),
            )
        val folders = triageFolders(guides)
        assertEquals(listOf("priority", "money", "custom-1", "custom-2", "discarded", "junk"), folders.map { it.key })
        assertEquals("Support", folders[2].target.customName)
        assertEquals("Helpdesk tickets about payments", folders[2].description)
        assertEquals(DISCARD_DESCRIPTION, folders[4].description)

        val defaults = triageFolders(emptyList())
        assertEquals(
            listOf("priority", "people", "money", "work", "orders", "reports", "discarded", "junk"),
            defaults.map { it.key },
        )
        val noPriority = triageFolders(listOf(FolderGuide(FolderTarget(FolderRole.MONEY), "Invoices")))
        assertEquals("priority", noPriority.first().key)
    }
}
