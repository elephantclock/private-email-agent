package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow

/** Sampling settings for one Prompt API call. */
data class GenerationSettings(
    val temperature: Float,
    val topK: Int,
    val maxOutputTokens: Int,
)

/** The first candidate of a Prompt API answer. [finishReason] is ML Kit's `Candidate.FinishReason`. */
data class Generated(
    val text: String?,
    val finishReason: Int?,
)

/**
 * The part of ML Kit's `GenerativeModel` this app uses, as a seam: tests script it, and ML Kit's
 * response classes (which have no public constructors) stay inside [MlKitPromptModel]. Methods
 * may throw `GenAiException`; callers map it ([GenAiErrors]).
 */
interface PromptModel {
    /** ML Kit's `@FeatureStatus`. */
    suspend fun checkStatus(): Int

    fun download(): Flow<DownloadStatus>

    suspend fun warmup()

    /** Input + output tokens the model accepts (Fold7, nano-v2: 8192). */
    suspend fun tokenLimit(): Int

    suspend fun generate(
        prompt: String,
        settings: GenerationSettings,
    ): Generated
}

/**
 * [PromptModel] over one `Generation.getClient()` for the process (AICore keeps one model per
 * app; a new client per call would reload it). Never closed: it lives as long as the app.
 */
class MlKitPromptModel(
    client: () -> GenerativeModel = { Generation.getClient() },
) : PromptModel {
    private val model by lazy(client)

    override suspend fun checkStatus(): Int = model.checkStatus()

    override fun download(): Flow<DownloadStatus> = model.download()

    override suspend fun warmup() = model.warmup()

    override suspend fun tokenLimit(): Int = model.getTokenLimit()

    override suspend fun generate(
        prompt: String,
        settings: GenerationSettings,
    ): Generated {
        val request =
            generateContentRequest(TextPart(prompt)) {
                temperature = settings.temperature
                topK = settings.topK
                maxOutputTokens = settings.maxOutputTokens
                candidateCount = 1
            }
        val first = model.generateContent(request).candidates.firstOrNull()
        return Generated(first?.text, first?.finishReason)
    }
}
