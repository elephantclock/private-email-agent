package tech.elephantclock.privatemail.core.ai

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitAvailability

/**
 * Downloads Gemini Nano's Prompt model through AICore, logging size and progress (tag PEA-AI).
 * Only with the user's OK, so it runs only when asked for explicitly:
 * `./gradlew :core:ai:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.peaDownload=1
 *  -Pandroid.testInstrumentationRunnerArguments.class=tech.elephantclock.privatemail.core.ai.DeviceGenAiDownloadTest`
 */
@RunWith(AndroidJUnit4::class)
class DeviceGenAiDownloadTest {
    @Test
    fun downloadPromptModel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("peaDownload") == "1")
        ActivityScenario.launch(ForegroundActivity::class.java).use {
            runBlocking {
                val before = MlKitAvailability().apply { refresh() }.status(AiFeature.PROMPT).value
                Log.i(TAG, "before: $before")
                if (before.canRun) return@runBlocking
                val model = Generation.getClient()
                var total = 0L
                var lastLogged = -1L
                val finished =
                    withTimeoutOrNull(TIMEOUT_MS) {
                        model.download().collect { s ->
                            when (s) {
                                is DownloadStatus.DownloadStarted -> {
                                    total = s.bytesToDownload
                                    Log.i(TAG, "started: ${total / 1_000_000} MB to download")
                                }

                                is DownloadStatus.DownloadProgress -> {
                                    val mb = s.totalBytesDownloaded / 1_000_000
                                    if (mb - lastLogged >= 50) {
                                        lastLogged = mb
                                        Log.i(TAG, "progress: $mb / ${total / 1_000_000} MB")
                                    }
                                }

                                is DownloadStatus.DownloadCompleted -> {
                                    Log.i(TAG, "completed")
                                }

                                is DownloadStatus.DownloadFailed -> {
                                    Log.i(TAG, "failed: ${s.e.errorCode} ${s.e.message}")
                                }

                                else -> {
                                    Log.i(TAG, "status: $s")
                                }
                            }
                        }
                        true
                    }
                if (finished ==
                    null
                ) {
                    Log.i(TAG, "still running after ${TIMEOUT_MS / 60_000} min; AICore continues in the background")
                }
                val after = MlKitAvailability().apply { refresh() }.status(AiFeature.PROMPT).value
                Log.i(TAG, "after: $after")
                model.close()
            }
        }
    }

    private companion object {
        const val TAG = "PEA-AI"
        const val TIMEOUT_MS = 30 * 60_000L
    }
}
