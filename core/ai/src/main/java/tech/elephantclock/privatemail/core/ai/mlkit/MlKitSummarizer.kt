package tech.elephantclock.privatemail.core.ai.mlkit

import android.content.Context
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import kotlinx.coroutines.guava.await
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.ThreadSummary
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.ai.aiCall
import tech.elephantclock.privatemail.core.ai.oneLine
import tech.elephantclock.privatemail.core.ai.safety.InjectionGuard
import java.time.LocalDate
import java.time.ZoneId

/**
 * The thread brief on Gemini Nano (T32). `MlKitAiEngine.summarize(thread)` delegates here; the
 * engine sits behind `SafeAiEngine` (redaction, cleaning) and runs through `AiQueue.foreground`,
 * since ML Kit GenAI only serves the top foreground app.
 *
 * 1. [BriefPrompt.trim] keeps the newest messages within the token budget (quoted history is
 *    removed by the caller, `BriefRepository`).
 * 2. Gist: the Summarization API for English threads (it supports English, Japanese and Korean
 *    only); for any other language, or when it fails, the Prompt API writes the gist too.
 * 3. Status, deadline and next steps: the Prompt API, JSON checked by [BriefParser].
 *
 * A gist is required: without one the call fails. A rejected JSON answer with a good
 * Summarization gist still gives a gist-only brief. When the thread tries to steer the model
 * ([InjectionGuard.scan]), next steps are dropped: they'd be the attacker's words.
 *
 * The two ports are plain functions so all of this runs in unit tests; [create] binds them to
 * ML Kit. They may throw; [GenAiException] codes become typed failures ([failureOf]).
 */
class MlKitSummarizer(
    /** Summarization API: (input, isConversation) → summary text, bullets allowed. */
    private val summarizeText: suspend (String, Boolean) -> String,
    /** Prompt API: prompt → raw answer. */
    private val generate: suspend (String) -> String,
    private val timeouts: AiTimeouts = AiTimeouts(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val today: () -> LocalDate = { LocalDate.now(zone()) },
    private val guard: InjectionGuard = InjectionGuard(),
) {
    suspend fun summarize(thread: AiThread): AiResult<ThreadSummary> {
        val trimmed = BriefPrompt.trim(thread)
        val english = BriefPrompt.isEnglish(trimmed.messages.joinToString("\n") { it.text })
        val gist: AiResult<String>? =
            if (english) {
                call(timeouts.summarizeMillis) {
                    val conversation = trimmed.messages.size > 1
                    cleanGist(summarizeText(BriefPrompt.summarizationInput(trimmed, zone()), conversation))
                }
            } else {
                null
            }
        val needGist = gist?.isOk != true
        val prompt =
            BriefPrompt.system(today(), withGist = needGist) + "\n\n" + BriefPrompt.user(trimmed, zone(), guard)
        val details =
            when (val raw = call(timeouts.generateMillis) { generate(prompt) }) {
                is AiResult.Ok -> BriefParser.parse(raw.value, withGist = needGist)
                is AiResult.Failed -> raw
            }
        val gistText = gist?.getOrNull() ?: details.getOrNull()?.gist
        if (gistText == null) {
            // Prefer the Prompt API's reason; it ran last and covers every language.
            return (details as? AiResult.Failed) ?: (gist as AiResult.Failed)
        }
        val fields = details.getOrNull()
        val steered = guard.scan(trimmed.subject, *trimmed.messages.map { it.text }.toTypedArray()).suspicious
        return AiResult.Ok(
            ThreadSummary(
                gist = gistText,
                status = fields?.status?.key,
                deadline = fields?.deadline,
                nextSteps = if (steered) emptyList() else fields?.nextSteps.orEmpty(),
            ),
        )
    }

    private suspend fun <T> call(
        timeoutMillis: Long,
        block: suspend () -> T?,
    ): AiResult<T> =
        aiCall(timeoutMillis) {
            try {
                block()?.let { AiResult.Ok(it) } ?: AiResult.Failed(AiFailure.InvalidOutput("brief: empty answer"))
            } catch (e: GenAiException) {
                AiResult.Failed(failureOf(e.errorCode, e.message))
            }
        }

    companion object {
        /** Bullets ("* ", "• ", "- ") joined into one paragraph; null when nothing is left. */
        fun cleanGist(summary: String): String? =
            summary
                .lineSequence()
                .map {
                    it
                        .trim()
                        .removePrefix("*")
                        .removePrefix("•")
                        .removePrefix("-")
                        .trim()
                }.filter { it.isNotEmpty() }
                .joinToString(" ")
                .oneLine(BriefParser.GIST_CHARS)
                .ifBlank { null }

        /** ML Kit's `GenAiException.ErrorCode` onto [AiFailure]. */
        fun failureOf(
            code: Int,
            message: String?,
        ): AiFailure =
            when (code) {
                GenAiException.ErrorCode.NOT_SUPPORTED,
                GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
                -> AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED))

                GenAiException.ErrorCode.NOT_AVAILABLE,
                GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
                GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
                -> AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_READY))

                GenAiException.ErrorCode.REQUEST_TOO_LARGE,
                GenAiException.ErrorCode.REQUEST_TOO_SMALL,
                -> AiFailure.Refused("GenAI $code")

                // BUSY, PER_APP_BATTERY_USE_QUOTA_EXCEEDED, BACKGROUND_USE_BLOCKED and the rest:
                // nothing cached, so the next open of the email tries again.
                else -> AiFailure.EngineError("GenAI $code: ${message.orEmpty().take(120)}")
            }

        /** Binds the ports to ML Kit. Call [MlKitClients.close] when the app no longer needs them. */
        fun create(
            context: Context,
            timeouts: AiTimeouts = AiTimeouts(),
        ): Pair<MlKitSummarizer, MlKitClients> {
            val clients = MlKitClients(context.applicationContext)
            return MlKitSummarizer(clients::summarize, clients::generate, timeouts) to clients
        }
    }
}

/** The ML Kit clients behind [MlKitSummarizer.create], made on first use. */
class MlKitClients internal constructor(
    private val context: Context,
) {
    private var article: Summarizer? = null
    private var conversation: Summarizer? = null
    private var prompt: GenerativeModel? = null

    private fun model(): GenerativeModel = synchronized(this) { prompt ?: Generation.getClient().also { prompt = it } }

    private fun summarizer(isConversation: Boolean): Summarizer =
        synchronized(this) {
            val existing = if (isConversation) conversation else article
            existing ?: Summarization
                .getClient(
                    SummarizerOptions
                        .builder(context)
                        .setInputType(
                            if (isConversation) {
                                SummarizerOptions.InputType.CONVERSATION
                            } else {
                                SummarizerOptions.InputType.ARTICLE
                            },
                        ).setOutputType(
                            if (isConversation) {
                                SummarizerOptions.OutputType.TWO_BULLETS
                            } else {
                                SummarizerOptions.OutputType.ONE_BULLET
                            },
                        ).setLanguage(SummarizerOptions.Language.ENGLISH)
                        .setLongInputAutoTruncationEnabled(true)
                        .build(),
                ).also { if (isConversation) conversation = it else article = it }
        }

    internal suspend fun summarize(
        input: String,
        isConversation: Boolean,
    ): String =
        summarizer(isConversation)
            .runInference(SummarizationRequest.builder(input).build())
            .await()
            .summary

    internal suspend fun generate(prompt: String): String {
        // Instructions go in the prompt text rather than a SystemInstruction: system prompts
        // aren't available on every Gemini Nano version (isSystemPromptAvailable).
        val request =
            generateContentRequest(TextPart(prompt)) {
                temperature = 0.1f
                topK = 16
                maxOutputTokens = BriefPrompt.MAX_OUTPUT_TOKENS
            }
        return model()
            .generateContent(request)
            .candidates
            .firstOrNull()
            ?.text
            .orEmpty()
    }

    /** The Summarization feature's status (ML Kit `FeatureStatus`), for `AiAvailability`. */
    suspend fun summarizationStatus(): ModelStatus =
        MlKitAvailability.statusOf(summarizer(true).checkFeatureStatus().await())

    fun close() {
        synchronized(this) {
            article?.close()
            conversation?.close()
            prompt?.close()
            article = null
            conversation = null
            prompt = null
        }
    }
}
