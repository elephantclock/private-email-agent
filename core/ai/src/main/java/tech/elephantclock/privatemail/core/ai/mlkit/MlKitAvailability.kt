package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tech.elephantclock.privatemail.core.ai.AiAvailability
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.UnavailableReason

/**
 * Gemini Nano availability through ML Kit GenAI (AICore). Only [AiFeature.PROMPT] is backed so
 * far (classification and drafts); the other features report UNKNOWN until their tasks (T32
 * summaries, T33 rewriting) add their ML Kit clients. Never throws: a failed check is
 * `Unavailable(UNKNOWN)`, so the app falls back to rules only.
 *
 * **Probe.** On the Fold7 `checkStatus()` stays DOWNLOADING although the model answers. So when
 * it says DOWNLOADABLE or DOWNLOADING, [refresh] asks the model itself (`warmup()`, then a tiny
 * prompt) and an answer counts as AVAILABLE. Inference is foreground-only, so the probe runs
 * only when [isForeground] says so; callers should run [refresh] through the `AiQueue`
 * (`queue.foreground { availability.refresh() }`) so it never overlaps another model call.
 * The result is cached per AICore version ([aicoreVersion], e.g. its `lastUpdateTime`): a success
 * holds until AICore changes or a call reports the model gone ([invalidate]); a failure is
 * retried after [probeRetryMillis]; BACKGROUND_USE_BLOCKED is not cached at all.
 */
class MlKitAvailability(
    private val model: PromptModel = MlKitPromptModel(),
    private val isForeground: () -> Boolean = { true },
    private val aicoreVersion: () -> Long? = { null },
    private val probeTimeoutMillis: Long = PROBE_TIMEOUT_MILLIS,
    private val probeRetryMillis: Long = PROBE_RETRY_MILLIS,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AiAvailability {
    private val states =
        AiFeature.entries.associateWith {
            MutableStateFlow<ModelStatus>(
                ModelStatus.Unavailable(UnavailableReason.UNKNOWN),
            )
        }

    private class Probe(
        val version: Long?,
        val ok: Boolean,
        val atMillis: Long,
    )

    @Volatile private var probe: Probe? = null
    private val refreshing = Mutex()

    /** How many times the model was actually probed (for tests and logs). */
    @Volatile var probes = 0
        private set

    override fun status(feature: AiFeature): StateFlow<ModelStatus> = states.getValue(feature).asStateFlow()

    override suspend fun refresh() =
        refreshing.withLock {
            val reported =
                try {
                    statusOf(model.checkStatus())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ModelStatus.Unavailable(UnavailableReason.UNKNOWN)
                }
            val stale = reported is ModelStatus.Downloadable || reported is ModelStatus.Downloading
            states.getValue(AiFeature.PROMPT).value = if (stale && answers()) ModelStatus.Available else reported
        }

    /** A call failed in a way that suggests the model is gone (NOT_AVAILABLE …): check again on the next refresh. */
    fun invalidate() {
        probe = null
        states.getValue(AiFeature.PROMPT).value = ModelStatus.Unavailable(UnavailableReason.UNKNOWN)
    }

    /** Whether the model answers, from the cache or by asking it now. */
    private suspend fun answers(): Boolean {
        val version = aicoreVersion()
        val cached = probe?.takeIf { it.version == version }
        if (cached != null && (cached.ok || clock() - cached.atMillis < probeRetryMillis)) return cached.ok
        if (!isForeground()) return false
        probes++
        val answered =
            try {
                withTimeoutOrNull(probeTimeoutMillis) {
                    try {
                        model.warmup()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The prompt below decides: warmup may refuse while the status is stale.
                    }
                    model.generate(PROBE_PROMPT, PROBE_SETTINGS)
                    true
                } ?: false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Asked from the background after all: says nothing about the model, try next time.
                if (GenAiErrors.find(e)?.errorCode == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED) return false
                false
            }
        probe = Probe(version, answered, clock())
        return answered
    }

    /** Downloads the Prompt model (AICore fetches it over Wi-Fi); progress shows in [status]. */
    override suspend fun download(feature: AiFeature): ModelStatus {
        val state = states.getValue(feature)
        if (feature != AiFeature.PROMPT) return state.value
        if (state.value !is ModelStatus.Downloadable && state.value !is ModelStatus.Downloading) return state.value
        var total: Long? = null
        try {
            model.download().collect { s ->
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
        /** Includes loading the model (7.6 s cold on the Fold7). */
        const val PROBE_TIMEOUT_MILLIS = 20_000L
        const val PROBE_RETRY_MILLIS = 10 * 60_000L
        internal const val PROBE_PROMPT = "Reply with exactly: OK"
        internal val PROBE_SETTINGS = GenerationSettings(temperature = 0f, topK = 1, maxOutputTokens = 2)

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
