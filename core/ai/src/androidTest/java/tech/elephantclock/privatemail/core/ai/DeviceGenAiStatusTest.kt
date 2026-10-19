package tech.elephantclock.privatemail.core.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitAvailability

/**
 * T30 device check: what Gemini Nano offers on this phone. Read-only (no download, no prompts).
 * Run on the Fold7: `ANDROID_SERIAL=<serial> ./gradlew :core:ai:connectedDebugAndroidTest`,
 * then `adb logcat -d -s PEA-AI`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceGenAiStatusTest {
    @Test
    fun reportGeminiNanoStatus() =
        runBlocking {
            val availability = MlKitAvailability()
            availability.refresh()
            val status = availability.status(AiFeature.PROMPT).value
            Log.i(TAG, "prompt status: $status")
            if (status.canRun) {
                val model = Generation.getClient()
                try {
                    Log.i(TAG, "base model: ${model.getBaseModelName()}")
                    Log.i(TAG, "token limit: ${model.getTokenLimit()}")
                    Log.i(TAG, "structured output: ${model.isStructuredOutputFeatureAvailable()}")
                    Log.i(TAG, "system prompt: ${model.isSystemPromptAvailable()}")
                } finally {
                    model.close()
                }
            }
        }

    private companion object {
        const val TAG = "PEA-AI"
    }
}
