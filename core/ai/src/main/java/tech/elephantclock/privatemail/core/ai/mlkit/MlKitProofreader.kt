package tech.elephantclock.privatemail.core.ai.mlkit

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.TextSuggestion
import tech.elephantclock.privatemail.core.ai.map

/** The Proofreading API as a seam: corrected candidates for [text], best first. */
fun interface ProofreadBackend {
    suspend fun proofread(
        text: String,
        language: Int,
    ): List<String>

    /**
     * Whether the feature is downloaded. Asked before each direct call: on the Fold7 a call to a
     * feature that isn't there hangs until the timeout (15 s) instead of failing.
     */
    suspend fun ready(language: Int): Boolean = true
}

/** ML Kit GenAI Proofreading for typed text; one client per language, closed by [close]. */
class MlKitProofreadBackend(
    private val context: Context,
) : ProofreadBackend,
    AutoCloseable {
    private val clients = HashMap<Int, Proofreader>()
    private val available = HashSet<Int>()

    override suspend fun ready(language: Int): Boolean {
        if (synchronized(available) { language in available }) return true
        val ok =
            try {
                client(language).checkFeatureStatus().awaitResult() == FeatureStatus.AVAILABLE
            } catch (e: GenAiException) {
                false
            }
        if (ok) synchronized(available) { available += language }
        return ok
    }

    override suspend fun proofread(
        text: String,
        language: Int,
    ): List<String> {
        val result = client(language).runInference(ProofreadingRequest.builder(text).build()).awaitResult()
        return result.results.map { it.text }
    }

    private fun client(language: Int): Proofreader =
        synchronized(clients) {
            clients.getOrPut(language) {
                Proofreading.getClient(
                    ProofreaderOptions
                        .builder(context.applicationContext)
                        .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                        .setLanguage(language)
                        .build(),
                )
            }
        }

    override fun close() =
        synchronized(clients) {
            clients.values.forEach { it.close() }
            clients.clear()
        }
}

/**
 * `AiEngine.proofread` (T33): the Proofreading API for its languages and short text, else the
 * Prompt API ("correct spelling and grammar only"), with the same fallback rules as [MlKitRewriter].
 */
class MlKitProofreader(
    private val backend: ProofreadBackend,
    private val fallback: TextGenerator,
    private val languageOf: suspend (String) -> LanguageTag,
    private val prompts: ReplyPrompts = ReplyPrompts(),
    private val timeouts: AiTimeouts = AiTimeouts(),
) {
    suspend fun proofread(text: String): AiResult<TextSuggestion> {
        val language = languageOf(text)
        val code = genAiTextLanguage(language)
        if (code != null && text.length <= MlKitRewriter.SHORT_INPUT_CHARS && backend.ready(code)) {
            val direct = genAiCall(timeouts.generateMillis) { suggestions(backend.proofread(text, code)) }
            if (direct is AiResult.Ok || !(direct as AiResult.Failed).failure.worthPromptFallback()) return direct
        }
        return genAiCall(timeouts.generateMillis) {
            ReplyPrompts
                .clean(fallback.generate(prompts.proofread(text, language), ReplyPrompts.OWN_TEXT_OUTPUT_TOKENS))
                .map { TextSuggestion(it) }
        }
    }
}
