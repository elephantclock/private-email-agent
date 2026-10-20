package tech.elephantclock.privatemail.core.ai.mlkit

import tech.elephantclock.privatemail.core.ai.AiEngine
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.ClassifyOutcome
import tech.elephantclock.privatemail.core.ai.ClassifyRequest
import tech.elephantclock.privatemail.core.ai.DetectedLanguage
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.ReplyDraft
import tech.elephantclock.privatemail.core.ai.ReplyTone
import tech.elephantclock.privatemail.core.ai.RewriteStyle
import tech.elephantclock.privatemail.core.ai.TextSuggestion
import tech.elephantclock.privatemail.core.ai.ThreadSummary
import tech.elephantclock.privatemail.core.ai.Translation
import tech.elephantclock.privatemail.core.ai.UnavailableReason

/**
 * The ML Kit [AiEngine]. Classification is [MlKitClassifier] (T31); every other call is a
 * constructor parameter that defaults to [AiFailure.Unavailable], so T32 (summaries) and T33
 * (replies, rewriting, proofreading, language ID, translation) plug in without changing this
 * class, e.g. `MlKitAiEngine(classifier, summarize = summarizer::summarize)`.
 *
 * Wrap it in `SafeAiEngine` and share it through the `AiQueue`.
 */
class MlKitAiEngine(
    private val classifier: MlKitClassifier,
    private val summarize: suspend (AiThread) -> AiResult<ThreadSummary> = { notYet() },
    private val draftReply: suspend (AiThread, ReplyTone, LanguageTag, String?) -> AiResult<ReplyDraft> =
        { _, _, _, _ -> notYet() },
    private val rewrite: suspend (String, RewriteStyle) -> AiResult<TextSuggestion> = { _, _ -> notYet() },
    private val proofread: suspend (String) -> AiResult<TextSuggestion> = { notYet() },
    private val detectLanguage: suspend (String) -> AiResult<DetectedLanguage> = { notYet() },
    private val translate: suspend (
        String,
        LanguageTag,
        LanguageTag,
    ) -> AiResult<Translation> = { _, _, _ -> notYet() },
) : AiEngine {
    override suspend fun classify(request: ClassifyRequest): List<ClassifyOutcome> = classifier.classify(request)

    override suspend fun summarize(thread: AiThread): AiResult<ThreadSummary> = summarize.invoke(thread)

    override suspend fun draftReply(
        thread: AiThread,
        tone: ReplyTone,
        language: LanguageTag,
        styleSample: String?,
    ): AiResult<ReplyDraft> = draftReply.invoke(thread, tone, language, styleSample)

    override suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ): AiResult<TextSuggestion> = rewrite.invoke(text, style)

    override suspend fun proofread(text: String): AiResult<TextSuggestion> = proofread.invoke(text)

    override suspend fun detectLanguage(text: String): AiResult<DetectedLanguage> = detectLanguage.invoke(text)

    override suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Translation> = translate.invoke(text, from, to)

    companion object {
        /** What a call without its ML Kit component answers: not on this build, rules/UI fallback. */
        val NOT_BUILT = AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED))

        fun notYet(): AiResult<Nothing> = AiResult.Failed(NOT_BUILT)
    }
}
