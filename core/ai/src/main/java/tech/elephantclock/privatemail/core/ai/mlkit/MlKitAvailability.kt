package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.elephantclock.privatemail.core.ai.AiAvailability
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason

/**
 * Gemini Nano availability through ML Kit GenAI (AICore). Only [AiFeature.PROMPT] is backed so
 * far (classification and drafts, T31); the other features report UNKNOWN until their tasks
 * (T32 summaries, T33 rewriting) add their ML Kit clients. Never throws: a failed check is
 * `Unavailable(UNKNOWN)`, so the app falls back to rules only.
 */
class MlKitAvailability(
    private val model: () -> GenerativeModel = { Generation.getClient() },
) : AiAvailability {
    private val states =
        AiFeature.entries.associateWith {
            MutableStateFlow<ModelStatus>(
                ModelStatus.Unavailable(UnavailableReason.UNKNOWN),
            )
        }

    override fun status(feature: AiFeature): StateFlow<ModelStatus> = states.getValue(feature).asStateFlow()

    override suspend fun refresh() {
        states.getValue(AiFeature.PROMPT).value =
            try {
                statusOf(model().checkStatus())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ModelStatus.Unavailable(UnavailableReason.UNKNOWN)
            }
    }

    /** Downloads the Prompt model (AICore fetches it over Wi-Fi); progress shows in [status]. */
    override suspend fun download(feature: AiFeature): ModelStatus {
        val state = states.getValue(feature)
        if (feature != AiFeature.PROMPT) return state.value
        if (state.value !is ModelStatus.Downloadable && state.value !is ModelStatus.Downloading) return state.value
        var total: Long? = null
        try {
            model().download().collect { s ->
                state.value =
                    when (s) {
                        is DownloadStatus.DownloadStarted -> {
                            total = s.bytesToDownload.takeIf { it > 0 }
                            ModelStatus.Downloading(0, total)
                        }

                        is DownloadStatus.DownloadProgress -> {
                            ModelStatus.Downloading(s.totalBytesDownloaded, total)
                        }

                        is DownloadStatus.DownloadCompleted -> {
                            ModelStatus.Available
                        }

                        is DownloadStatus.DownloadFailed -> {
                            ModelStatus.Unavailable(UnavailableReason.NOT_READY)
                        }

                        else -> {
                            state.value
                        }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.value = ModelStatus.Unavailable(UnavailableReason.NOT_READY)
        }
        return state.value
    }

    companion object {
        /** ML Kit's `@FeatureStatus` onto [ModelStatus]. Unknown values count as not ready. */
        fun statusOf(featureStatus: Int): ModelStatus =
            when (featureStatus) {
                FeatureStatus.AVAILABLE -> ModelStatus.Available
                FeatureStatus.DOWNLOADABLE -> ModelStatus.Downloadable()
                FeatureStatus.DOWNLOADING -> ModelStatus.Downloading()
                FeatureStatus.UNAVAILABLE -> ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED)
                else -> ModelStatus.Unavailable(UnavailableReason.NOT_READY)
            }
    }
}
