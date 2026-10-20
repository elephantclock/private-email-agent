package tech.elephantclock.privatemail.core.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitAvailability

/**
 * Asks Gemini Nano one trivial question and logs the answer and latency (tag PEA-AI), or AICore's
 * error. No mail content. Opt-in: `-Pandroid.testInstrumentationRunnerArguments.peaSmoke=1`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceGenAiSmokeTest {
    @Test
    fun askOneQuestion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("peaSmoke") == "1")
        runBlocking {
            Log.i(TAG, "status: ${MlKitAvailability().apply { refresh() }.status(AiFeature.PROMPT).value}")
            val model = Generation.getClient()
            try {
                val start = System.nanoTime()
                val response = model.generateContent("Reply with exactly: OK")
                val ms = (System.nanoTime() - start) / 1_000_000
                val text =
                    response.candidates
                        .firstOrNull()
                        ?.text
                        ?.trim()
                        ?.take(80)
                Log.i(TAG, "answer in $ms ms: \"$text\"")
                Log.i(TAG, "base model: ${model.getBaseModelName()}, token limit: ${model.getTokenLimit()}")
            } catch (e: GenAiException) {
                Log.i(TAG, "GenAiException ${e.errorCode}: ${e.message}")
            } catch (e: Exception) {
                Log.i(TAG, "${e::class.java.simpleName}: ${e.message}")
            } finally {
                model.close()
            }
        }
    }

    private companion object {
        const val TAG = "PEA-AI"
    }
}
