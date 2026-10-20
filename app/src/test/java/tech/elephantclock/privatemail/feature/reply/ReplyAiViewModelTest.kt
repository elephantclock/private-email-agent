package tech.elephantclock.privatemail.feature.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiCall
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.FakeAiAvailability
import tech.elephantclock.privatemail.core.ai.FakeAiEngine
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.NoAiAvailability
import tech.elephantclock.privatemail.core.ai.RewriteStyle
import tech.elephantclock.privatemail.core.ai.ThreadMessage
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.model.Address

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyAiViewModelTest {
    private val source = FakeReplySource()
    private val ai = FakeReplyAi()
    private val availability = FakeAiAvailability(ModelStatus.Available)
    private val material =
        ReplyMaterial(
            AiThread("Встреча", listOf(ThreadMessage(Address.of("ivan@example.ru", "Иван"), 1L, "Можем во вторник?"))),
            "Hi Ann,\nThanks!\nSam",
        )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        replyAi: ReplyAi = ai,
        avail: tech.elephantclock.privatemail.core.ai.AiAvailability = availability,
    ) = ReplyViewModel(4, source, avail, replyAi, { material }, LanguageTag.ENGLISH)

    private val ReplyViewModel.ui get() = state.value.ai

    @Test
    fun `tools follow availability, rewrite and proofread fall back to the Prompt API`() =
        runTest {
            val none = vm(NoReplyAi, NoAiAvailability())
            assertFalse(none.ui.anyGenerate)
            assertFalse(none.ui.translateAvailable)

            availability.setAll(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED))
            val vm = vm()
            assertFalse(vm.ui.anyGenerate)
            assertFalse(vm.ui.translateAvailable) // translation is Gemini Nano too
            availability.set(AiFeature.PROMPT, ModelStatus.Available)
            assertTrue(vm.ui.draftAvailable && vm.ui.rewriteAvailable && vm.ui.proofreadAvailable)
            assertTrue(vm.ui.translateAvailable)
            availability.set(AiFeature.PROMPT, ModelStatus.Downloading())
            availability.set(AiFeature.REWRITE, ModelStatus.Available)
            assertTrue(vm.ui.rewriteAvailable)
            assertFalse(vm.ui.draftAvailable || vm.ui.proofreadAvailable || vm.ui.translateAvailable)
        }

    @Test
    fun `a draft is written in the sender's language with the style sample, and can be undone`() =
        runTest {
            val vm = vm()
            vm.setBody("my notes")
            vm.runAi(AiAction.DRAFT)
            val (_, language, style) = ai.draftArgs!!
            assertEquals(LanguageTag.RUSSIAN, language)
            assertEquals("Hi Ann,\nThanks!\nSam", style)
            assertEquals("AI: Можем во вторник?", vm.state.value.body)
            assertEquals(AiAction.DRAFT, vm.ui.lastChange)
            vm.undoAi()
            assertEquals("my notes", vm.state.value.body)
            assertNull(vm.ui.undo)
        }

    @Test
    fun `rewrites replace the text, and a manual edit makes the change the user's own`() =
        runTest {
            val vm = vm()
            vm.setBody("Thanks a lot for your message")
            vm.runAi(AiAction.SHORTER)
            assertEquals("rewrite:${RewriteStyle.SHORTER}", ai.calls.last())
            assertEquals("AI: Thanks a lot for your message", vm.state.value.body)
            assertEquals("Thanks a lot for your message", vm.ui.undo)
            vm.setBody("AI: Thanks a lot for your message!")
            assertNull(vm.ui.undo)
            vm.undoAi()
            assertEquals("AI: Thanks a lot for your message!", vm.state.value.body)
            vm.runAi(AiAction.PROOFREAD)
            assertEquals("proofread", ai.calls.last())
        }

    @Test
    fun `typing while the model works is never overwritten`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            ai.gate = gate
            val vm = vm()
            vm.setBody("Thanks")
            vm.runAi(AiAction.FORMAL)
            assertEquals(AiAction.FORMAL, vm.ui.working)
            vm.setBody("Thanks, see you Tuesday") // the editor stays usable
            vm.runAi(AiAction.SHORTER) // one action at a time
            gate.complete(Unit)
            assertEquals("Thanks, see you Tuesday", vm.state.value.body)
            assertTrue(vm.ui.skipped)
            assertNull(vm.ui.working)
            assertEquals(1, ai.calls.count { it.startsWith("rewrite") })
        }

    @Test
    fun `nothing changes behind the send confirmation, and only the confirmed text is sent`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            ai.gate = gate
            val vm = vm()
            vm.setBody("Tuesday works.")
            vm.runAi(AiAction.FRIENDLIER)
            vm.requestSend()
            val pending = vm.state.value.pending!!
            gate.complete(Unit)
            assertEquals("Tuesday works.", vm.state.value.body)
            assertEquals("Tuesday works.", pending.text)
            assertTrue(vm.ui.skipped)
            ai.gate = null
            vm.runAi(AiAction.SHORTER) // refused while the dialog is open
            assertEquals(1, ai.calls.count { it.startsWith("rewrite") })
            assertTrue(source.sends.isEmpty()) // AI never sends
        }

    @Test
    fun `a failure keeps the text and offers retry`() =
        runTest {
            val vm = vm()
            vm.setBody("Hello")
            ai.answer = { FakeReplyAi.failure }
            vm.runAi(AiAction.SHORTER)
            assertEquals("Hello", vm.state.value.body)
            assertEquals(AiAction.SHORTER, vm.ui.failed)
            assertFalse(vm.ui.failedUnavailable)
            ai.answer = {
                tech.elephantclock.privatemail.core.ai.AiResult
                    .Ok("Hi")
            }
            vm.retryAi()
            assertEquals("Hi", vm.state.value.body)
            assertNull(vm.ui.failed)
        }

    @Test
    fun `a Russian draft's preview shows the English text, stale after an edit`() =
        runTest {
            val vm = vm()
            vm.setBody("Здравствуйте, Иван!\n\nВо вторник удобно.")
            vm.translatePreview()
            assertEquals(
                TranslationUi.Ready("Hello, Ivan!\n\nTuesday works.", LanguageTag.RUSSIAN),
                vm.ui.translation,
            )
            vm.setBody("Здравствуйте, Иван!")
            assertEquals(true, (vm.ui.translation as TranslationUi.Ready).stale)
            vm.runAi(AiAction.FORMAL) // an AI change refreshes a shown preview
            assertFalse((vm.ui.translation as TranslationUi.Ready).stale)
        }

    @Test
    fun `undo refreshes a shown translation preview`() =
        runTest {
            ai.answer = {
                tech.elephantclock.privatemail.core.ai.AiResult
                    .Ok("Во вторник удобно.")
            }
            val vm = vm()
            vm.setBody("Здравствуйте, Иван!")
            vm.translatePreview()
            vm.runAi(AiAction.FORMAL)
            assertEquals(TranslationUi.Ready("Tuesday works.", LanguageTag.RUSSIAN), vm.ui.translation)
            vm.undoAi()
            assertEquals("Здравствуйте, Иван!", vm.state.value.body)
            assertEquals(TranslationUi.Ready("Hello, Ivan!", LanguageTag.RUSSIAN), vm.ui.translation)
        }

    @Test
    fun `the preview goes through the AI queue, and is off while the Prompt API can't run`() =
        runTest {
            val engine = FakeAiEngine(availability)
            val queued = QueuedReplyAi(AiQueue(engine, CoroutineScope(UnconfinedTestDispatcher(testScheduler))))
            val vm = vm(queued)
            vm.setBody("Во вторник удобно.")
            vm.translatePreview()
            advanceUntilIdle()
            assertEquals(TranslationUi.Ready("[en] Во вторник удобно.", LanguageTag.RUSSIAN), vm.ui.translation)
            assertEquals(listOf(AiCall.DETECT_LANGUAGE, AiCall.TRANSLATE), engine.calls)

            availability.set(AiFeature.PROMPT, ModelStatus.Unavailable(UnavailableReason.NOT_READY))
            assertFalse(vm.ui.translateAvailable)
            vm.translatePreview() // ignored
            advanceUntilIdle()
            assertEquals(2, engine.calls.size)
        }

    @Test
    fun `a refused language shows as unsupported`() =
        runTest {
            val engine = FakeAiEngine(availability)
            engine.translator = { _, _, _ -> AiResult.Failed(AiFailure.Refused("language can't be translated")) }
            val vm = vm(QueuedReplyAi(AiQueue(engine, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))))
            vm.setBody("Во вторник удобно.")
            vm.translatePreview()
            advanceUntilIdle()
            assertEquals(TranslationUi.Unsupported(LanguageTag.RUSSIAN), vm.ui.translation)
        }

    @Test
    fun `English drafts and unknown languages need no translation`() =
        runTest {
            val vm = vm()
            vm.setBody("Tuesday works.")
            vm.translatePreview()
            assertEquals(TranslationUi.SameLanguage, vm.ui.translation)
            vm.setBody("ok")
            vm.translatePreview()
            assertEquals(TranslationUi.Undetermined, vm.ui.translation)
            assertFalse("translate" in ai.calls)
        }
}
