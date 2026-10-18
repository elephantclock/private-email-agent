package tech.elephantclock.privatemail.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.model.FolderRole

class FakeAiEngineTest {
    private val availability = FakeAiAvailability()
    private val engine = FakeAiEngine(availability)

    @Test
    fun `default classification is deterministic and keeps in priority`() =
        runTest {
            val req = ClassifyRequest(TestData.folders, listOf(TestData.item("a"), TestData.item("b")))
            val first = engine.classify(req)
            assertEquals(first, engine.classify(req))
            assertEquals(listOf("a", "b"), first.map { it.key })
            assertTrue(
                first.all {
                    it.result
                        .getOrNull()!!
                        .target.role == FolderRole.PRIORITY
                },
            )
            assertEquals(listOf("a", "b", "a", "b"), engine.classifiedKeys)
        }

    @Test
    fun `scripted results and failures win per key`() =
        runTest {
            engine.script("b", AiResult.Failed(AiFailure.InvalidOutput("not json")))
            val out = engine.classify(ClassifyRequest(TestData.folders, listOf(TestData.item("a"), TestData.item("b"))))
            assertTrue(out[0].result.isOk)
            assertEquals(AiResult.Failed(AiFailure.InvalidOutput("not json")), out[1].result)
        }

    @Test
    fun `an unavailable feature fails every call for it, other features still run`() =
        runTest {
            availability.set(AiFeature.PROMPT, ModelStatus.Downloading(50, 100))
            val out = engine.classify(ClassifyRequest(TestData.folders, listOf(TestData.item("a"))))
            assertEquals(AiResult.Failed(AiFailure.Unavailable(ModelStatus.Downloading(50, 100))), out.single().result)
            assertFalse(engine.draftReply(TestData.thread, ReplyTone.NEUTRAL, LanguageTag.ENGLISH).isOk)
            assertTrue(engine.summarize(TestData.thread).isOk)
            assertEquals(listOf(AiCall.CLASSIFY, AiCall.DRAFT_REPLY, AiCall.SUMMARIZE), engine.calls)
        }

    @Test
    fun `a slow model times out as a value, in virtual time`() =
        runTest {
            val slow = FakeAiEngine(availability, AiTimeouts(classifyMillis = 1_500))
            slow.latencyMillis = 2_000
            val out = slow.classify(ClassifyRequest(TestData.folders, listOf(TestData.item("a"))))
            assertEquals(AiResult.Failed(AiFailure.Timeout(1_500)), out.single().result)
            assertEquals(1_500, testScheduler.currentTime)
        }

    @Test
    fun `engine exceptions become failures, cancellation still propagates`() =
        runTest {
            engine.summarizer = { error("binder died") }
            val failed = engine.summarize(TestData.thread) as AiResult.Failed
            assertTrue(failed.failure is AiFailure.EngineError)

            engine.latencyMillis = 1_000
            val call = async { engine.summarize(TestData.thread) }
            runCurrent()
            call.cancel()
            var cancelled = false
            try {
                call.await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

    @Test
    fun `text features have stable defaults`() =
        runTest {
            assertEquals("Can we meet on Friday?", engine.summarize(TestData.thread).getOrNull()!!.gist)
            assertEquals(
                "[FORMAL/ru] Re: Trip",
                engine.draftReply(TestData.thread, ReplyTone.FORMAL, LanguageTag.RUSSIAN).getOrNull()!!.text,
            )
            assertEquals("Hi.", engine.rewrite("Hi. More text.", RewriteStyle.SHORTER).getOrNull()!!.text)
            assertEquals("a b", engine.proofread("a   b").getOrNull()!!.text)
            assertEquals(LanguageTag.RUSSIAN, engine.detectLanguage("Привет, как дела?").getOrNull()!!.language)
            assertEquals(LanguageTag.ENGLISH, engine.detectLanguage("Hello there").getOrNull()!!.language)
            assertEquals(LanguageTag.UNDETERMINED, engine.detectLanguage("12345").getOrNull()!!.language)
            val t = engine.translate("Привет", LanguageTag.RUSSIAN, LanguageTag.ENGLISH).getOrNull()!!
            assertEquals("[en] Привет", t.text)
        }

    @Test
    fun `language tags are validated`() {
        assertTrue(LanguageTag.isValid("pt-BR"))
        assertTrue(LanguageTag.isValid("zh-Hant-TW"))
        assertFalse(LanguageTag.isValid("e"))
        assertFalse(LanguageTag.isValid("en_US"))
        assertFalse(LanguageTag.isValid("en--US"))
        assertFalse(LanguageTag.isValid(""))
        assertEquals("pt", LanguageTag("pt-BR").primary)
    }

    @Test
    fun `availability drives rules-only mode`() =
        runTest {
            val modes = mutableListOf<TriageMode>()
            val job = launch { availability.triageMode().toList(modes) }
            runCurrent()
            availability.set(AiFeature.PROMPT, ModelStatus.Downloadable(1_000))
            runCurrent()
            assertEquals(ModelStatus.Available, availability.download(AiFeature.PROMPT))
            runCurrent()
            availability.set(AiFeature.SUMMARIZE, ModelStatus.Unavailable(UnavailableReason.NOT_READY))
            runCurrent()
            job.cancel()
            assertEquals(listOf(TriageMode.RULES_AND_AI, TriageMode.RULES_ONLY, TriageMode.RULES_AND_AI), modes)
            assertTrue(availability.isAvailable(AiFeature.PROMPT))
            assertFalse(availability.isAvailable(AiFeature.SUMMARIZE))
        }

    @Test
    fun `no-AI devices are rules only and never offer a download`() =
        runTest {
            val none = NoAiAvailability()
            assertEquals(TriageMode.RULES_ONLY, none.triageMode().first())
            AiFeature.entries.forEach { assertFalse(none.status(it).value.canRun) }
            assertFalse(none.download(AiFeature.PROMPT).canDownload)
        }

    @Test
    fun `download progress is a clamped fraction`() {
        assertEquals(0.5f, ModelStatus.Downloading(50, 100).progress)
        assertEquals(1f, ModelStatus.Downloading(500, 100).progress)
        assertEquals(null, ModelStatus.Downloading(50, null).progress)
    }

    @Test
    fun `a cancellation from the engine itself is a failure while the caller is still active`() =
        runTest {
            val r = aiCall<Unit>(1_000) { throw CancellationException("AICore cancelled the future") }
            assertTrue((r as AiResult.Failed).failure is AiFailure.EngineError)
        }
}
