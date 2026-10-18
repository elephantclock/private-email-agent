package tech.elephantclock.privatemail.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Whether triage may ask the model, derived from [AiFeature.PROMPT]'s status. */
enum class TriageMode {
    /** Rules first, the model for `NeedsAi` decisions. */
    RULES_AND_AI,

    /** The model can't run: `NeedsAi` decisions fall back to the rules' default. */
    RULES_ONLY,
}

/** Per-feature model status. Implementations: ML Kit (T31), [FakeAiAvailability], [NoAiAvailability]. */
interface AiAvailability {
    fun status(feature: AiFeature): StateFlow<ModelStatus>

    /** Re-checks every feature (after an AICore update, on app start). */
    suspend fun refresh()

    /** Starts a download for a [ModelStatus.Downloadable] feature; returns the new status. */
    suspend fun download(feature: AiFeature): ModelStatus
}

fun AiAvailability.triageMode(): Flow<TriageMode> =
    status(AiFeature.PROMPT)
        .map { if (it.canRun) TriageMode.RULES_AND_AI else TriageMode.RULES_ONLY }
        .distinctUntilChanged()

fun AiAvailability.isAvailable(feature: AiFeature): Boolean = status(feature).value.canRun

/** For devices without Gemini Nano, and until T31: every feature is unavailable, rules only. */
class NoAiAvailability(
    reason: UnavailableReason = UnavailableReason.NOT_SUPPORTED,
) : AiAvailability {
    private val state = MutableStateFlow<ModelStatus>(ModelStatus.Unavailable(reason)).asStateFlow()

    override fun status(feature: AiFeature): StateFlow<ModelStatus> = state

    override suspend fun refresh() = Unit

    override suspend fun download(feature: AiFeature): ModelStatus = state.value
}

/** Scriptable availability for tests and previews. [download] completes immediately. */
class FakeAiAvailability(
    initial: ModelStatus = ModelStatus.Available,
) : AiAvailability {
    private val states = AiFeature.entries.associateWith { MutableStateFlow(initial) }
    var refreshCount = 0
        private set

    fun set(
        feature: AiFeature,
        status: ModelStatus,
    ) {
        states.getValue(feature).value = status
    }

    fun setAll(status: ModelStatus) = AiFeature.entries.forEach { set(it, status) }

    override fun status(feature: AiFeature): StateFlow<ModelStatus> = states.getValue(feature).asStateFlow()

    override suspend fun refresh() {
        refreshCount++
    }

    override suspend fun download(feature: AiFeature): ModelStatus {
        val flow = states.getValue(feature)
        if (flow.value is ModelStatus.Downloadable || flow.value is ModelStatus.Downloading) {
            flow.value = ModelStatus.Available
        }
        return flow.value
    }
}
