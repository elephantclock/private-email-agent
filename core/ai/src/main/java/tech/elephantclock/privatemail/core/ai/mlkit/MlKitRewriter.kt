package tech.elephantclock.privatemail.core.ai.mlkit

import android.content.Context
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.RewriteStyle
import tech.elephantclock.privatemail.core.ai.TextSuggestion
import tech.elephantclock.privatemail.core.ai.map

/** The Rewriting API as a seam: candidates for [text], best first. */
fun interface RewriteBackend {
    suspend fun rewrite(
        text: String,
        outputType: Int,
        language: Int,
    ): List<String>
}

/** ML Kit GenAI Rewriting; one client per (language, output type), closed by [close]. */
class MlKitRewriteBackend(
    private val context: Context,
) : RewriteBackend,
    AutoCloseable {
    private val clients = HashMap<Pair<Int, Int>, Rewriter>()

    override suspend fun rewrite(
        text: String,
        outputType: Int,
        language: Int,
    ): List<String> {
        val client =
            synchronized(clients) {
                clients.getOrPut(language to outputType) {
                    Rewriting.getClient(
                        RewriterOptions
                            .builder(context.applicationContext)
                            .setLanguage(language)
                            .setOutputType(outputType)
                            .build(),
                    )
                }
            }
        val result = client.runInference(RewritingRequest.builder(text).build()).awaitResult()
        return result.results.map { it.text }
    }

    override fun close() =
        synchronized(clients) {
            clients.values.forEach { it.close() }
            clients.clear()
        }
}

/**
 * `AiEngine.rewrite` (T33): the Rewriting API where it fits (one of its seven languages, under its
 * 256-token input limit), else the Prompt API with the draft fenced as data. A failure that
 * another engine could avoid (feature missing, input too long) falls back to the Prompt API;
 * BUSY, quota and timeouts don't (it's the same model underneath).
 */
class MlKitRewriter(
    private val backend: RewriteBackend,
    private val fallback: TextGenerator,
    private val languageOf: suspend (String) -> LanguageTag,
    private val prompts: ReplyPrompts = ReplyPrompts(),
    private val timeouts: AiTimeouts = AiTimeouts(),
) {
    suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ): AiResult<TextSuggestion> {
        val language = languageOf(text)
        val code = genAiTextLanguage(language)
        if (code != null && text.length <= SHORT_INPUT_CHARS) {
            val direct =
                genAiCall(timeouts.generateMillis) {
                    suggestions(backend.rewrite(text, outputType(style), code))
                }
            if (direct is AiResult.Ok || !(direct as AiResult.Failed).failure.worthPromptFallback()) return direct
        }
        return genAiCall(timeouts.generateMillis) {
            ReplyPrompts
                .clean(fallback.generate(prompts.rewrite(text, style, language), ReplyPrompts.OWN_TEXT_OUTPUT_TOKENS))
                .map { TextSuggestion(it) }
        }
    }

    companion object {
        /** About 256 tokens of Latin-script text, with room to spare. */
        const val SHORT_INPUT_CHARS = 700

        fun outputType(style: RewriteStyle): Int =
            when (style) {
                RewriteStyle.SHORTER -> RewriterOptions.OutputType.SHORTEN
                RewriteStyle.MORE_FORMAL -> RewriterOptions.OutputType.PROFESSIONAL
                RewriteStyle.FRIENDLIER -> RewriterOptions.OutputType.FRIENDLY
                RewriteStyle.REPHRASE -> RewriterOptions.OutputType.REPHRASE
                RewriteStyle.ELABORATE -> RewriterOptions.OutputType.ELABORATE
            }
    }
}

/**
 * The language constant shared by the Rewriting and Proofreading APIs (same values in both
 * `Language` annotations), or null for languages they don't serve (Russian among them).
 */
fun genAiTextLanguage(tag: LanguageTag): Int? =
    when (tag.primary) {
        "en" -> RewriterOptions.Language.ENGLISH
        "ja" -> RewriterOptions.Language.JAPANESE
        "ko" -> RewriterOptions.Language.KOREAN
        "de" -> RewriterOptions.Language.GERMAN
        "fr" -> RewriterOptions.Language.FRENCH
        "it" -> RewriterOptions.Language.ITALIAN
        "es" -> RewriterOptions.Language.SPANISH
        else -> null
    }

/** Non-blank candidates as a [TextSuggestion]; none is [AiFailure.InvalidOutput]. */
internal fun suggestions(candidates: List<String>): AiResult<TextSuggestion> {
    val texts = candidates.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return if (texts.isEmpty()) {
        AiResult.Failed(AiFailure.InvalidOutput("no suggestion"))
    } else {
        AiResult.Ok(TextSuggestion(texts.first().take(ReplyPrompts.OUTPUT_CHARS), texts.drop(1).take(MAX_ALTERNATIVES)))
    }
}

private const val MAX_ALTERNATIVES = 3
