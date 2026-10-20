package tech.elephantclock.privatemail.feature.reply

import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiThread
import tech.elephantclock.privatemail.core.ai.DetectedLanguage
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.ModelStatus
import tech.elephantclock.privatemail.core.ai.ReplyDraft
import tech.elephantclock.privatemail.core.ai.ReplyTone
import tech.elephantclock.privatemail.core.ai.RewriteStyle
import tech.elephantclock.privatemail.core.ai.TextSuggestion
import tech.elephantclock.privatemail.core.ai.Translation
import tech.elephantclock.privatemail.core.ai.UnavailableReason
import tech.elephantclock.privatemail.core.ai.mlkit.TranslationModels
import tech.elephantclock.privatemail.core.ai.mlkit.TranslationReadiness
import tech.elephantclock.privatemail.core.ai.queue.AiQueue

/**
 * What the reply editor may ask of the on-device AI (T33). It only returns text for the editor:
 * there is no way to send, save or move anything through it.
 */
interface ReplyAi {
    /** Drafts and rewrites (Gemini Nano) can be offered at all; the per-feature status still applies. */
    val canGenerate: Boolean

    /** On-device translation (ML Kit Translation: no Gemini Nano needed). */
    val canTranslate: Boolean

    suspend fun detectLanguage(text: String): AiResult<DetectedLanguage>

    suspend fun draft(
        thread: AiThread,
        language: LanguageTag,
        styleSample: String?,
    ): AiResult<ReplyDraft>

    suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ): AiResult<TextSuggestion>

    suspend fun proofread(text: String): AiResult<TextSuggestion>

    suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Translation>

    suspend fun translationReadiness(
        from: LanguageTag,
        to: LanguageTag,
    ): TranslationReadiness

    /** Downloads missing translation models on Wi-Fi only; suspends until then. */
    suspend fun downloadTranslation(
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Unit>
}

/** No AI wired (rules-only phones, tests): every tool shows as unavailable. */
object NoReplyAi : ReplyAi {
    private val none = AiResult.Failed(AiFailure.Unavailable(ModelStatus.Unavailable(UnavailableReason.NOT_SUPPORTED)))

    override val canGenerate = false
    override val canTranslate = false

    override suspend fun detectLanguage(text: String) = none

    override suspend fun draft(
        thread: AiThread,
        language: LanguageTag,
        styleSample: String?,
    ) = none

    override suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ) = none

    override suspend fun proofread(text: String) = none

    override suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ) = none

    override suspend fun translationReadiness(
        from: LanguageTag,
        to: LanguageTag,
    ) = TranslationReadiness.Unsupported

    override suspend fun downloadTranslation(
        from: LanguageTag,
        to: LanguageTag,
    ) = none
}

/**
 * The app's [ReplyAi]: every model call goes through [AiQueue.foreground] (GenAI is
 * foreground-only and single-flight; the queue's engine is the app's `SafeAiEngine`, so text is
 * redacted on the way in and restored on the way out). Translation model checks and downloads go
 * straight to [models]: they aren't inference, and a download waiting for Wi-Fi must not hold
 * the queue.
 */
class QueuedReplyAi(
    private val queue: AiQueue,
    private val models: TranslationModels?,
) : ReplyAi {
    override val canGenerate = true
    override val canTranslate = models != null

    override suspend fun detectLanguage(text: String) = queue.foreground { it.detectLanguage(text) }

    override suspend fun draft(
        thread: AiThread,
        language: LanguageTag,
        styleSample: String?,
    ) = queue.foreground { it.draftReply(thread, ReplyTone.NEUTRAL, language, styleSample) }

    override suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ) = queue.foreground { it.rewrite(text, style) }

    override suspend fun proofread(text: String) = queue.foreground { it.proofread(text) }

    override suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ) = queue.foreground { it.translate(text, from, to) }

    override suspend fun translationReadiness(
        from: LanguageTag,
        to: LanguageTag,
    ) = models?.readiness(from, to) ?: TranslationReadiness.Unsupported

    override suspend fun downloadTranslation(
        from: LanguageTag,
        to: LanguageTag,
    ) = models?.download(from, to) ?: NoReplyAi.downloadTranslation(from, to)
}
