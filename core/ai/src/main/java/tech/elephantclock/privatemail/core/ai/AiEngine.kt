package tech.elephantclock.privatemail.core.ai

/**
 * The app's only door to the on-device model.
 *
 * Contract for every implementation (ML Kit in T31–T33, [FakeAiEngine], the safety wrapper):
 * - Every function returns data ([AiResult]); model problems (timeout, feature unavailable,
 *   refusal, malformed output) are [AiResult.Failed], never exceptions. Cancellation propagates.
 * - The engine has **no** capability to send, move, delete, flag or open anything: no callbacks,
 *   no links followed, no tools given to the model. Acting on a result is the caller's decision.
 * - Email text is untrusted. Callers should go through `SafeAiEngine`, which redacts secrets and
 *   checks outputs; implementations fence content as data in their prompts (`InjectionGuard`).
 * - One call at a time per model: callers share the `AiQueue`.
 */
interface AiEngine {
    /**
     * Chooses folder, importance, a reason and a one-line summary for each item, from
     * [ClassifyRequest.folders] only. Returns one outcome per item, keyed by [ClassifyItem.key].
     */
    suspend fun classify(request: ClassifyRequest): List<ClassifyOutcome>

    suspend fun summarize(thread: AiThread): AiResult<ThreadSummary>

    /** A reply to the last message of [thread], in [language]; [styleSample] is the user's own writing. */
    suspend fun draftReply(
        thread: AiThread,
        tone: ReplyTone,
        language: LanguageTag,
        styleSample: String? = null,
    ): AiResult<ReplyDraft>

    suspend fun rewrite(
        text: String,
        style: RewriteStyle,
    ): AiResult<TextSuggestion>

    suspend fun proofread(text: String): AiResult<TextSuggestion>

    suspend fun detectLanguage(text: String): AiResult<DetectedLanguage>

    suspend fun translate(
        text: String,
        from: LanguageTag,
        to: LanguageTag,
    ): AiResult<Translation>
}

/** Which feature each call needs, for availability checks. */
internal fun AiCall.feature(): AiFeature =
    when (this) {
        AiCall.CLASSIFY, AiCall.DRAFT_REPLY -> AiFeature.PROMPT
        AiCall.SUMMARIZE -> AiFeature.SUMMARIZE
        AiCall.REWRITE -> AiFeature.REWRITE
        AiCall.PROOFREAD -> AiFeature.PROOFREAD
        AiCall.DETECT_LANGUAGE -> AiFeature.LANGUAGE_ID
        AiCall.TRANSLATE -> AiFeature.TRANSLATE
    }

/** The calls [AiEngine] offers, for logs and the fake's call record. */
enum class AiCall { CLASSIFY, SUMMARIZE, DRAFT_REPLY, REWRITE, PROOFREAD, DETECT_LANGUAGE, TRANSLATE }
