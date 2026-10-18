package tech.elephantclock.privatemail.core.triage.rules

import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.triage.people.Protection

/** What the rules engine decided for one message. Every decision carries a stable reason. */
sealed interface Decision {
    /** Stable id of the rule that decided, e.g. `phishing.lookalike` (for tests, logs and stats). */
    val rule: String

    /** Human-readable explanation, shown to the user. Stable for the same input. */
    val reason: String

    val signals: Signals
    val protection: Protection

    /** The rules are sure: this is the verdict. */
    data class Final(
        val verdict: Verdict,
        override val rule: String,
        override val signals: Signals,
        override val protection: Protection,
    ) : Decision {
        override val reason: String get() = verdict.reason
    }

    /**
     * No rule was sure; the AI decides. Pass the AI's verdict through [accept], which applies the
     * message's [protection] so a protected message can't be discarded or junked by the model.
     */
    data class NeedsAi(
        override val reason: String,
        override val signals: Signals,
        override val protection: Protection,
    ) : Decision {
        override val rule: String get() = RULE_ID

        fun accept(aiVerdict: Verdict): Verdict = protection.enforce(aiVerdict)

        companion object {
            const val RULE_ID = "needs-ai"
        }
    }
}

/** The folder's default display name, for reasons. CUSTOM uses its own name. */
fun FolderTarget.label(): String =
    customName ?: when (role) {
        FolderRole.PRIORITY -> "Priority"
        FolderRole.PEOPLE -> "People"
        FolderRole.MONEY -> "Money"
        FolderRole.WORK -> "Work updates"
        FolderRole.ORDERS -> "Orders & travel"
        FolderRole.REPORTS -> "Reports & alerts"
        FolderRole.CUSTOM -> "Custom"
        FolderRole.DISCARDED -> "Discarded"
        FolderRole.JUNK -> "Junk"
    }
