package tech.elephantclock.privatemail.core.ai

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.elephantclock.privatemail.core.ai.mlkit.ClassifyPrompt
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitAiEngine
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitAvailability
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitClassifier
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitPromptModel
import tech.elephantclock.privatemail.core.ai.safety.SafeAiEngine

/**
 * Classifies 20 synthetic emails with Gemini Nano through the app's stack (SafeAiEngine →
 * MlKitAiEngine → MlKitClassifier) and logs, under tag PEA-AI, each answer and its latency, the
 * P50/P90 of the warm calls (the first call includes loading the model and is logged apart),
 * the share of answers that parsed, and one prompt's token count (to check the chars-per-token
 * budget). Measures; asserts nothing about quality. Opt-in:
 * `-Pandroid.testInstrumentationRunnerArguments.peaClassify=1`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceClassifyTest {
    @Test
    fun classifyTwentyEmails() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("peaClassify") == "1")
        ActivityScenario.launch(ForegroundActivity::class.java).use {
            runBlocking { measure() }
        }
    }

    private suspend fun measure() {
        val client = Generation.getClient()
        val model = MlKitPromptModel { client }
        val availability = MlKitAvailability(model)
        val probeStart = System.nanoTime()
        availability.refresh()
        Log.i(
            TAG,
            "status after probe: ${availability.status(
                AiFeature.PROMPT,
            ).value} (${ms(probeStart)} ms, probes ${availability.probes})",
        )
        Log.i(TAG, "base model: ${client.getBaseModelName()}, token limit: ${client.getTokenLimit()}")

        val engine = SafeAiEngine(MlKitAiEngine(MlKitClassifier(model, onModelGone = availability::invalidate)))
        val fixtures = DeviceClassifyFixtures.all
        val latencies = mutableListOf<Long>()
        var parsed = 0
        var agreed = 0
        for ((i, fixture) in fixtures.withIndex()) {
            val start = System.nanoTime()
            val result = engine.classifyOne(DeviceClassifyFixtures.folders, fixture.item)
            val took = ms(start)
            if (i > 0) latencies += took
            val line =
                when (result) {
                    is AiResult.Ok -> {
                        parsed++
                        val key = DeviceClassifyFixtures.folders.first { it.target == result.value.target }.key
                        if (key == fixture.expected) agreed++
                        "$key ${result.value.importance} ${result.value.confidence} | ${result.value.reason} | ${result.value.summary}"
                    }

                    is AiResult.Failed -> {
                        "FAILED ${result.failure}"
                    }
                }
            Log.i(
                TAG,
                "${fixture.item.key}${if (i == 0) " (cold)" else ""}: $took ms, expected ${fixture.expected}: $line",
            )
        }
        latencies.sort()
        Log.i(
            TAG,
            "warm latency P50 ${pct(latencies, 50)} ms, P90 ${pct(latencies, 90)} ms (n=${latencies.size}); " +
                "parsed $parsed/${fixtures.size}; agreed with expected $agreed/${fixtures.size}",
        )

        val sample = ClassifyPrompt().build(DeviceClassifyFixtures.folders, fixtures.first().item, 50_000).orEmpty()
        try {
            val tokens = client.countTokens(generateContentRequest(TextPart(sample)) {}).totalTokens
            Log.i(
                TAG,
                "sample prompt: ${sample.length} chars, $tokens tokens (${"%.2f".format(
                    sample.length.toDouble() / tokens,
                )} chars/token)",
            )
        } catch (e: Exception) {
            Log.i(TAG, "countTokens failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun pct(
        sorted: List<Long>,
        p: Int,
    ): Long = if (sorted.isEmpty()) -1 else sorted[((sorted.size - 1) * p / 100.0).toInt()]

    private companion object {
        const val TAG = "PEA-AI"
    }
}
