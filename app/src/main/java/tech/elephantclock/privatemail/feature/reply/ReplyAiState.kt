package tech.elephantclock.privatemail.feature.reply

import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.core.ai.RewriteStyle

/** The AI buttons of the reply editor. */
enum class AiAction(
    val style: RewriteStyle?,
) {
    DRAFT(null),
    SHORTER(RewriteStyle.SHORTER),
    FORMAL(RewriteStyle.MORE_FORMAL),
    FRIENDLIER(RewriteStyle.FRIENDLIER),
    PROOFREAD(null),
}

/** The translation preview: the draft in the user's own language. */
sealed interface TranslationUi {
    /** Not asked yet. */
    data object Idle : TranslationUi

    data object Working : TranslationUi

    data class Ready(
        val text: String,
        val from: LanguageTag,
        /** The draft changed since; the preview is older than the draft. */
        val stale: Boolean = false,
    ) : TranslationUi

    /** The draft is already in the user's language. */
    data object SameLanguage : TranslationUi

    /** Language ID couldn't tell (too short, mixed). */
    data object Undetermined : TranslationUi

    data class Unsupported(
        val from: LanguageTag,
    ) : TranslationUi

    /** A model must be downloaded once, on Wi-Fi; nothing downloads before the user asks. */
    data class NeedsDownload(
        val from: LanguageTag,
        val languages: List<LanguageTag>,
    ) : TranslationUi

    /** Asked for: ML Kit downloads it as soon as the phone is on Wi-Fi. */
    data class Downloading(
        val from: LanguageTag,
        val languages: List<LanguageTag>,
    ) : TranslationUi

    data object Failed : TranslationUi
}

data class ReplyAiUi(
    val draftAvailable: Boolean = false,
    val rewriteAvailable: Boolean = false,
    val proofreadAvailable: Boolean = false,
    val translateAvailable: Boolean = false,
    /** The running action; typing stays possible meanwhile. */
    val working: AiAction? = null,
    /** The last action failed; the UI offers Retry. */
    val failed: AiAction? = null,
    /** The failure was "AI not ready" rather than a one-off error. */
    val failedUnavailable: Boolean = false,
    /** The result arrived after the user had changed the draft, so it wasn't applied. */
    val skipped: Boolean = false,
    /** The draft before the last AI change: one-step undo. Cleared by the next manual edit. */
    val undo: String? = null,
    val lastChange: AiAction? = null,
    val translation: TranslationUi = TranslationUi.Idle,
    /** The language the translation preview shows the draft in. */
    val userLanguage: LanguageTag = LanguageTag.ENGLISH,
) {
    val anyGenerate: Boolean get() = draftAvailable || rewriteAvailable || proofreadAvailable

    fun available(action: AiAction): Boolean =
        when (action) {
            AiAction.DRAFT -> draftAvailable
            AiAction.PROOFREAD -> proofreadAvailable
            else -> rewriteAvailable
        }
}
