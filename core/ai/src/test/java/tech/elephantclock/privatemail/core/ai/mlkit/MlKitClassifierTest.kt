package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.ClassifyRequest
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.TestData
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.model.Importance
import java.time.Duration

class MlKitClassifierTest {
    private val good =
        """{"folder":"orders","importance":"normal","confidence":0.8,"reason":"Delivery","summary":"Parcel ships today"}"""
    private var now = 0L
    private var gone = 0
    private val model = FakePromptModel()

    private fun classifier() =
        MlKitClassifier(model, timeouts = AiTimeouts(classifyMillis = 5_000), clock = { now }, onModelGone = { gone++ })

    private suspend fun MlKitClassifier.one() = classifyOne(TestData.folders, TestData.item("m1"))

    private fun failure(r: AiResult<*>) = (r as AiResult.Failed).failure

    @Test
    fun `classifies through the prompt and the parser with deterministic, short settings`() =
        runTest {
            model.answer(good)
            val r = classifier().one() as AiResult.Ok
            assertEquals(Importance.NORMAL, r.value.importance)
            assertEquals("Parcel ships today", r.value.summary)
            assertEquals(MlKitClassifier.SETTINGS, model.settingsSeen.single())
            assertEquals(0f, MlKitClassifier.SETTINGS.temperature)
            assertTrue(model.prompts.single().contains("⟪SUBJECT⟫\nSubject m1\n⟪/SUBJECT⟫"))
        }

    @Test
    fun `a batch runs one item after another and the token limit is asked once`() =
        runTest {
            model.answer(good)
            val out =
                classifier().classify(
                    ClassifyRequest(
                        TestData.folders,
                        listOf(TestData.item("a"), TestData.item("b"), TestData.item("c")),
                    ),
                )
            assertEquals(listOf("a", "b", "c"), out.map { it.key })
            assertTrue(out.all { it.result.isOk })
            assertEquals(1, model.tokenLimitCalls)
        }

    @Test
    fun `malformed or cut-off answers are invalid output`() =
        runTest {
            model.answer("I think this is an order.")
            assertTrue(failure(classifier().one()) is AiFailure.InvalidOutput)
            val c = classifier()
            model.answers.clear()
            model.answer(good)
            model.finishReason = Candidate.FinishReason.MAX_TOKENS
            assertEquals(AiFailure.InvalidOutput("answer cut off at the token limit"), failure(c.one()))
            model.answers.clear()
            model.answer(null)
            model.finishReason = null
            assertTrue(failure(c.one()) is AiFailure.InvalidOutput)
        }

    @Test
    fun `the first call of the process may take a cold start, later calls get the normal budget`() =
        runTest {
            model.answer(good)
            model.latencyMillis = 8_000 // model load
            val c = classifier()
            assertTrue("cold start within 20 s", c.one().isOk)
            assertEquals(AiFailure.Timeout(5_000), failure(c.one()))
            model.latencyMillis = 1_000
            assertTrue(c.one().isOk)
        }

    @Test
    fun `GenAI error codes map onto typed failures and never throw`() =
        runTest {
            val notReady = AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_READY))
            val cases =
                mapOf(
                    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED to notReady,
                    GenAiException.ErrorCode.NOT_AVAILABLE to notReady,
                    GenAiException.ErrorCode.NOT_SUPPORTED to
                        AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED)),
                    GenAiException.ErrorCode.REQUEST_TOO_LARGE to AiFailure.Refused("request too large"),
                    GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR to AiFailure.Refused("no response generated"),
                    GenAiException.ErrorCode.CANCELLED to AiFailure.EngineError("cancelled by AICore"),
                    GenAiException.ErrorCode.UNKNOWN to AiFailure.EngineError("GenAI error 0"),
                )
            for ((code, expected) in cases) {
                model.answers.clear()
                model.answer(GenAiException("x", null, code))
                assertEquals("code $code", expected, failure(classifier().one()))
            }
            model.answers.clear()
            model.answer(IllegalStateException("binder died"))
            assertEquals(AiFailure.EngineError("IllegalStateException: binder died"), failure(classifier().one()))
            model.answers.clear()
            model.answer(
                RuntimeException("wrapped", GenAiException("x", null, GenAiException.ErrorCode.REQUEST_TOO_LARGE)),
            )
            assertEquals(AiFailure.Refused("request too large"), failure(classifier().one()))
        }

    @Test
    fun `BUSY and the battery quota cool down without touching AICore`() =
        runTest {
            val c = classifier()
            model.answer(GenAiException("busy", null, GenAiException.ErrorCode.BUSY), good)
            assertTrue(failure(c.one()) is AiFailure.Unavailable)
            now += GenAiErrors.BUSY_COOL_DOWN_MILLIS - 1
            assertTrue(failure(c.one()) is AiFailure.Unavailable)
            assertEquals("cooling down: no second call", 1, model.prompts.size)
            now += 1
            assertTrue(c.one().isOk)

            model.answers.clear()
            model.answer(
                GenAiException(
                    "quota",
                    null,
                    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
                    Duration.ofMinutes(2),
                ),
                good,
            )
            assertTrue(failure(c.one()) is AiFailure.Unavailable)
            now += 119_999
            assertTrue(failure(c.one()) is AiFailure.Unavailable)
            now += 1
            assertTrue("AICore's retry delay is honoured", c.one().isOk)
        }

    @Test
    fun `codes that mean the model is gone ask for a new availability check`() =
        runTest {
            val c = classifier()
            model.answer(GenAiException("x", null, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE))
            c.one()
            assertEquals(1, gone)
            model.answers.clear()
            model.answer(GenAiException("x", null, GenAiException.ErrorCode.BUSY))
            c.one()
            assertEquals(1, gone)
        }

    @Test
    fun `a prompt that can't fit is refused without calling the model`() =
        runTest {
            model.limit = 300
            assertEquals(AiFailure.Refused("prompt too long"), failure(classifier().one()))
            assertTrue(model.prompts.isEmpty())
        }
}
