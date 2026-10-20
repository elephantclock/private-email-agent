package tech.elephantclock.privatemail.core.ai.mlkit

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.DetectedLanguage
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.aiCall

/** ML Kit Language ID as a seam: candidate tags with confidences, best first. */
fun interface LanguageIdBackend {
    suspend fun identify(text: String): List<Pair<String, Float>>
}

/** The bundled Language ID model (no download, no AICore; works without Gemini Nano). */
class MlKitLanguageIdBackend(
    client: () -> LanguageIdentifier = { LanguageIdentification.getClient() },
) : LanguageIdBackend {
    private val identifier by lazy(client)

    override suspend fun identify(text: String): List<Pair<String, Float>> =
        identifier.identifyPossibleLanguages(text).awaitResult().map { it.languageTag to it.confidence }
}

/**
 * `AiEngine.detectLanguage` (T33): the most likely language of [text], or
 * [LanguageTag.UNDETERMINED] with confidence 0 when Language ID can't tell (too short, mixed, or
 * below [minConfidence]).
 */
class MlKitLanguageId(
    private val backend: LanguageIdBackend = MlKitLanguageIdBackend(),
    private val timeouts: AiTimeouts = AiTimeouts(),
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
) {
    suspend fun detectLanguage(text: String): AiResult<DetectedLanguage> =
        aiCall(timeouts.shortTextMillis) {
            val sample = text.trim().take(SAMPLE_CHARS)
            if (sample.isEmpty()) return@aiCall AiResult.Ok(UNDETERMINED)
            val best = backend.identify(sample).maxByOrNull { it.second }
            AiResult.Ok(
                if (best == null || best.second < minConfidence || !LanguageTag.isValid(best.first)) {
                    UNDETERMINED
                } else {
                    DetectedLanguage(LanguageTag(best.first), best.second.coerceIn(0f, 1f))
                },
            )
        }

    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.5f
        const val SAMPLE_CHARS = 2_000
        val UNDETERMINED = DetectedLanguage(LanguageTag.UNDETERMINED, 0f)
    }
}
