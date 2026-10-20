package tech.elephantclock.privatemail.feature.detail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.FakeAiAvailability
import tech.elephantclock.privatemail.core.ai.FakeAiEngine
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.ThreadSummary
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.ai.safety.SafeAiEngine
import tech.elephantclock.privatemail.core.data.repo.BriefFailure
import tech.elephantclock.privatemail.core.data.repo.BriefKey
import tech.elephantclock.privatemail.core.data.repo.BriefMessage
import tech.elephantclock.privatemail.core.data.repo.BriefOutcome
import tech.elephantclock.privatemail.core.data.repo.BriefThread
import tech.elephantclock.privatemail.core.data.repo.ThreadBrief

/** The app's mapping between core:data briefs and core:ai summaries, through the real queue and safety wrapper. */
class AiBriefSourceTest {
    private val thread =
        BriefThread(
            BriefKey("acc", "<root@x>", "<r2@x>"),
            "Invoice",
            listOf(
                BriefMessage(1, "anna@client.example", "Anna", 1_000, "Please pay. Card 4111 1111 1111 1111.", false),
                BriefMessage(2, "not-an-address", null, 2_000, "Done.", true),
            ),
        )

    @Test
    fun `the model gets a redacted thread through the foreground queue`() =
        runTest {
            val fake = FakeAiEngine()
            var seen: AiThread? = null
            fake.summarizer = { t ->
                seen = t
                AiResult.Ok(ThreadSummary("Anna asked for payment.", "resolved", null, listOf("Nothing to do")))
            }
            val queue = AiQueue(SafeAiEngine(fake), backgroundScope)
            val outcome = queue.foreground { it.summarize(thread.toAiThread()) }.toOutcome()
            assertEquals(
                BriefOutcome.Ready(ThreadBrief("Anna asked for payment.", "resolved", null, listOf("Nothing to do"))),
                outcome,
            )
            val text = seen!!.messages.first().text
            assertFalse("card number must not reach the model: $text", "4111 1111 1111 1111" in text)
            assertEquals("anna@client.example", seen!!.messages[0].from?.email)
            assertEquals(null, seen!!.messages[1].from) // an invalid address is dropped, not a crash
            assertTrue(seen!!.messages[1].fromUser)
        }

    @Test
    fun `failures map onto brief failures`() {
        fun of(f: AiFailure) = (AiResult.Failed(f) as AiResult<ThreadSummary>).toOutcome()
        assertEquals(
            BriefOutcome.Failed(BriefFailure.UNAVAILABLE),
            of(AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_READY))),
        )
        assertEquals(BriefOutcome.Failed(BriefFailure.TIMEOUT), of(AiFailure.Timeout(1)))
        assertEquals(BriefOutcome.Failed(BriefFailure.INVALID), of(AiFailure.InvalidOutput("x")))
        assertEquals(BriefOutcome.Failed(BriefFailure.REFUSED), of(AiFailure.Refused("x")))
        assertEquals(BriefOutcome.Failed(BriefFailure.ERROR), of(AiFailure.EngineError("x")))
    }

    @Test
    fun `an unavailable summarizer fails instead of hanging`() =
        runTest {
            val fake = FakeAiEngine(FakeAiAvailability(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED)))
            val queue = AiQueue(SafeAiEngine(fake), backgroundScope)
            assertEquals(
                BriefOutcome.Failed(BriefFailure.UNAVAILABLE),
                queue.foreground { it.summarize(thread.toAiThread()) }.toOutcome(),
            )
        }

    @Test
    fun `unknown status keys show no status chip`() {
        assertEquals(null, ThreadBrief("g", "urgent").toState().status)
        assertEquals(ThreadStatus.INFO, ThreadBrief("g", "info").toState().status)
    }
}
