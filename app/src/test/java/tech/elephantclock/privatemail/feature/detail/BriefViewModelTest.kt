package tech.elephantclock.privatemail.feature.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.FakeAiAvailability
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.data.repo.BriefFailure
import tech.elephantclock.privatemail.core.data.repo.BriefOutcome
import tech.elephantclock.privatemail.core.data.repo.ThreadBrief
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures

class FakeBriefSource : BriefSource {
    val key = MutableStateFlow<Any?>("thread@1")
    var answer: suspend () -> BriefOutcome = { BriefOutcome.Ready(BRIEF) }
    var calls = 0

    override fun changes(messageId: Long): Flow<Any?> = key

    override suspend fun brief(messageId: Long): BriefOutcome {
        calls++
        return answer()
    }

    companion object {
        val BRIEF =
            ThreadBrief(
                gist = "The client wants a proposal for 12 hotels and a call next week.",
                status = "waiting_on_you",
                deadline = "2026-10-24",
                nextSteps = listOf("Send the proposal", "Offer two call times"),
            )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BriefViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()
    private lateinit var source: FakeDetailSource
    private val briefs = FakeBriefSource()
    private val unavailable = ModelStatus.Unavailable(UnavailableReason.NOT_READY)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeDetailSource(dir = temp.root)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(ai: FakeAiAvailability = FakeAiAvailability()) =
        DetailViewModel(4, source, ai, ReadingFixtures.times, briefs, briefTimeoutMillis = 30_000)

    @Test
    fun `loading, then the brief replaces nothing of the rules text`() =
        runTest {
            val gate = CompletableDeferred<BriefOutcome>()
            briefs.answer = { gate.await() }
            val vm = vm()
            backgroundScope.launch { vm.watchBrief() }
            runCurrent()
            assertEquals(ThreadBriefState.Loading, vm.state.value.thread)
            assertFalse(vm.state.value.showNextSteps)
            gate.complete(BriefOutcome.Ready(FakeBriefSource.BRIEF))
            runCurrent()
            val s = vm.state.value
            assertEquals(
                ThreadBriefState.Ready(
                    FakeBriefSource.BRIEF.gist,
                    ThreadStatus.WAITING_ON_YOU,
                    "2026-10-24",
                    listOf("Send the proposal", "Offer two call times"),
                ),
                s.thread,
            )
            assertTrue(s.showNextSteps)
            // The rules-only text is kept for when the brief goes away again.
            assertEquals("Wants a proposal and a call next week; 12 properties.", s.brief)
        }

    @Test
    fun `a slow model times out to the rules text, never a spinner forever`() =
        runTest {
            briefs.answer = { awaitCancellation() }
            val vm = vm()
            backgroundScope.launch { vm.watchBrief() }
            runCurrent()
            assertEquals(ThreadBriefState.Loading, vm.state.value.thread)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(ThreadBriefState.Fallback, vm.state.value.thread)
            assertFalse(vm.state.value.showNextSteps)
        }

    @Test
    fun `a failed call falls back`() =
        runTest {
            briefs.answer = { BriefOutcome.Failed(BriefFailure.INVALID) }
            val vm = vm()
            backgroundScope.launch { vm.watchBrief() }
            runCurrent()
            assertEquals(ThreadBriefState.Fallback, vm.state.value.thread)
        }

    @Test
    fun `AI unavailable means no call, and the screen says why`() =
        runTest {
            val vm = vm(FakeAiAvailability(unavailable))
            backgroundScope.launch { vm.watchBrief() }
            runCurrent()
            assertEquals(ThreadBriefState.Off, vm.state.value.thread)
            assertFalse(vm.state.value.aiAvailable)
            assertEquals(0, briefs.calls)
        }

    @Test
    fun `either the Prompt or the Summarization feature is enough, and a later availability starts the brief`() =
        runTest {
            val ai = FakeAiAvailability(unavailable)
            val vm = vm(ai)
            backgroundScope.launch { vm.watchBrief() }
            runCurrent()
            ai.set(AiFeature.PROMPT, ModelStatus.Available)
            runCurrent()
            assertTrue(vm.state.value.aiAvailable)
            assertTrue(vm.state.value.thread is ThreadBriefState.Ready)
            assertEquals(1, briefs.calls)
        }

    @Test
    fun `a new message in the thread asks again`() =
        runTest {
            val vm = vm()
            backgroundScope.launch { vm.watchBrief() }
            runCurrent()
            assertEquals(1, briefs.calls)
            briefs.answer = { BriefOutcome.Ready(FakeBriefSource.BRIEF.copy(gist = "Now they also ask for prices.")) }
            briefs.key.value = "thread@2"
            runCurrent()
            assertEquals(2, briefs.calls)
            assertEquals("Now they also ask for prices.", (vm.state.value.thread as ThreadBriefState.Ready).gist)
        }

    @Test
    fun `without a brief source the screen stays rules-only`() =
        runTest {
            val vm = DetailViewModel(4, source, FakeAiAvailability(), ReadingFixtures.times)
            vm.watchBrief() // returns at once
            assertEquals(ThreadBriefState.Off, vm.state.value.thread)
        }
}
