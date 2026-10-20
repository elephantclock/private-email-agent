package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason

class MlKitAvailabilityTest {
    private val model = FakePromptModel(status = FeatureStatus.DOWNLOADING).answer("OK")
    private var foreground = true
    private var version: Long? = 1
    private var now = 0L

    private val availability =
        MlKitAvailability(model, {
            foreground
        }, { version }, probeTimeoutMillis = 20_000, probeRetryMillis = 600_000, clock = { now })

    private val prompt get() = availability.status(AiFeature.PROMPT).value

    @Test
    fun `every ML Kit feature status maps onto a model status`() {
        assertEquals(ModelStatus.Available, MlKitAvailability.statusOf(FeatureStatus.AVAILABLE))
        assertEquals(ModelStatus.Downloadable(), MlKitAvailability.statusOf(FeatureStatus.DOWNLOADABLE))
        assertEquals(ModelStatus.Downloading(), MlKitAvailability.statusOf(FeatureStatus.DOWNLOADING))
        assertEquals(
            ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED),
            MlKitAvailability.statusOf(FeatureStatus.UNAVAILABLE),
        )
        assertEquals(ModelStatus.Unavailable(UnavailableReason.NOT_READY), MlKitAvailability.statusOf(-42))
    }

    @Test
    fun `a stale DOWNLOADING status becomes AVAILABLE when the model answers, and the answer is cached`() =
        runTest {
            availability.refresh()
            assertEquals(ModelStatus.Available, prompt)
            assertEquals(1, model.warmups)
            assertEquals(MlKitAvailability.PROBE_SETTINGS, model.settingsSeen.single())
            availability.refresh()
            assertEquals(ModelStatus.Available, prompt)
            assertEquals("cached", 1, availability.probes)
        }

    @Test
    fun `AVAILABLE and UNAVAILABLE are trusted without a probe`() =
        runTest {
            model.status = FeatureStatus.AVAILABLE
            availability.refresh()
            assertEquals(ModelStatus.Available, prompt)
            model.status = FeatureStatus.UNAVAILABLE
            availability.refresh()
            assertEquals(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED), prompt)
            assertEquals(0, availability.probes)
        }

    @Test
    fun `no probe from the background`() =
        runTest {
            foreground = false
            availability.refresh()
            assertEquals(ModelStatus.Downloading(), prompt)
            assertEquals(0, availability.probes)
        }

    @Test
    fun `a failed probe is retried only after a while, or at once after an AICore update`() =
        runTest {
            model.answers.clear()
            model.answer(GenAiException("x", null, GenAiException.ErrorCode.NOT_AVAILABLE))
            availability.refresh()
            assertEquals(ModelStatus.Downloading(), prompt)
            now += 60_000
            availability.refresh()
            assertEquals(1, availability.probes)
            now += 600_000
            availability.refresh()
            assertEquals(2, availability.probes)
            version = 2
            model.answers.clear()
            model.answer("OK")
            availability.refresh()
            assertEquals(3, availability.probes)
            assertEquals(ModelStatus.Available, prompt)
        }

    @Test
    fun `a probe that times out counts as a failure, one blocked as background is not cached`() =
        runTest {
            model.latencyMillis = 30_000
            availability.refresh()
            assertEquals(ModelStatus.Downloading(), prompt)
            model.latencyMillis = 0
            availability.refresh()
            assertEquals("cached failure", 1, availability.probes)

            val fresh = MlKitAvailability(model, { true }, { 1 }, clock = { now })
            model.answers.clear()
            model.answer(GenAiException("x", null, GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED), "OK")
            fresh.refresh()
            assertEquals(ModelStatus.Downloading(), fresh.status(AiFeature.PROMPT).value)
            fresh.refresh()
            assertEquals(ModelStatus.Available, fresh.status(AiFeature.PROMPT).value)
            assertEquals(2, fresh.probes)
        }

    @Test
    fun `warmup failing alone doesn't fail the probe`() =
        runTest {
            model.warmupError = GenAiException("x", null, GenAiException.ErrorCode.NOT_AVAILABLE)
            availability.refresh()
            assertEquals(ModelStatus.Available, prompt)
        }

    @Test
    fun `invalidate drops the cached answer and the status until the next refresh`() =
        runTest {
            availability.refresh()
            availability.invalidate()
            assertEquals(ModelStatus.Unavailable(UnavailableReason.UNKNOWN), prompt)
            availability.refresh()
            assertEquals(2, availability.probes)
            assertEquals(ModelStatus.Available, prompt)
        }

    @Test
    fun `a failing status check is UNKNOWN, never an exception`() =
        runTest {
            val broken =
                MlKitAvailability(
                    object : PromptModel by model {
                        override suspend fun checkStatus(): Int = throw IllegalStateException("no AICore")
                    },
                )
            broken.refresh()
            assertEquals(ModelStatus.Unavailable(UnavailableReason.UNKNOWN), broken.status(AiFeature.PROMPT).value)
        }
}
