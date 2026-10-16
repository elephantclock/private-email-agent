package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class VerdictAction {
    /** Leave in the Inbox (Priority). */
    KEEP,

    /** Move to a value folder. */
    FILE,

    /** Move to Discarded; recoverable for 30 days. */
    DISCARD,

    /** Move to Junk (spam, phishing). */
    JUNK,
}

/** Which folder roles an action may target. */
fun VerdictAction.allows(role: FolderRole): Boolean =
    when (this) {
        VerdictAction.KEEP -> role == FolderRole.PRIORITY
        VerdictAction.FILE -> !role.isDiscard && role != FolderRole.PRIORITY
        VerdictAction.DISCARD -> role == FolderRole.DISCARDED
        VerdictAction.JUNK -> role == FolderRole.JUNK
    }

@Serializable
enum class VerdictSource { RULE, AI, USER }

/**
 * The triage decision for one message. The constructor rejects combinations
 * that don't make sense, so a verdict that exists is always consistent.
 */
@Serializable
data class Verdict(
    val importance: Importance,
    val target: FolderTarget,
    val action: VerdictAction,
    val source: VerdictSource,
    val reason: String,
    /** 0.0–1.0. Rules and the user use 1.0. */
    val confidence: Float,
    /** One-line summary for lists, from the AI. */
    val summary: String? = null,
) {
    init {
        require(reason.isNotBlank()) { "every verdict needs a reason" }
        require(confidence in 0f..1f) { "confidence must be in 0..1, was $confidence" }
        require(action.allows(target.role)) { "$action cannot target ${target.role}" }
        require(!(action == VerdictAction.DISCARD || action == VerdictAction.JUNK) || !importance.notifies) {
            "$action cannot carry $importance importance"
        }
    }

    companion object {
        fun keep(
            importance: Importance,
            source: VerdictSource,
            reason: String,
            confidence: Float = 1f,
        ) = Verdict(importance, FolderTarget.Priority, VerdictAction.KEEP, source, reason, confidence)

        fun file(
            target: FolderTarget,
            importance: Importance,
            source: VerdictSource,
            reason: String,
            confidence: Float = 1f,
        ) = Verdict(importance, target, VerdictAction.FILE, source, reason, confidence)

        fun discard(
            source: VerdictSource,
            reason: String,
            confidence: Float = 1f,
        ) = Verdict(Importance.LOW, FolderTarget.Discarded, VerdictAction.DISCARD, source, reason, confidence)

        fun junk(
            source: VerdictSource,
            reason: String,
            confidence: Float = 1f,
        ) = Verdict(Importance.LOW, FolderTarget.Junk, VerdictAction.JUNK, source, reason, confidence)
    }
}
