package tech.elephantclock.privatemail.core.triage.people

import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.signals.Keywords
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor

/**
 * Why a message must not be thrown away.
 *
 * Two kinds, with different strength:
 * - **Relationship** (sent-to, contact, replied thread, VIP): blocks DISCARD *and* JUNK. Junk is
 *   a discard role too, and the quality bar is zero false discards of people the user wrote to.
 * - **Content** (security code, money): blocks DISCARD only. Phishing is full of "payment
 *   failed" and "your verification code", so a look-alike stranger can still go to Junk.
 */
data class Protection(
    val sentTo: Boolean = false,
    val contact: Boolean = false,
    val repliedThread: Boolean = false,
    val vip: Boolean = false,
    val securityCode: Boolean = false,
    val money: Boolean = false,
) {
    val relationship: Boolean get() = sentTo || contact || repliedThread || vip
    val blocksDiscard: Boolean get() = relationship || securityCode || money
    val blocksJunk: Boolean get() = relationship

    /** Stable, human-readable list, strongest first; empty when unprotected. */
    val reasons: List<String>
        get() =
            buildList {
                if (vip) add("VIP")
                if (sentTo) add("you have written to this sender")
                if (repliedThread) add("you replied in this thread")
                if (contact) add("sender is in your contacts")
                if (securityCode) add("security code")
                if (money) add("payment or invoice")
            }

    /** True when [action] is not allowed for a message with this protection. */
    fun forbids(action: VerdictAction): Boolean =
        when (action) {
            VerdictAction.DISCARD -> blocksDiscard
            VerdictAction.JUNK -> blocksJunk
            VerdictAction.KEEP, VerdictAction.FILE -> false
        }

    /**
     * The last line of defence, for every verdict from rules, the AI or anywhere else: a verdict
     * this protection [forbids] becomes KEEP (Priority, NORMAL) with the reason saying why.
     * Allowed verdicts are returned unchanged.
     */
    fun enforce(verdict: Verdict): Verdict {
        if (!forbids(verdict.action)) return verdict
        return Verdict.keep(
            importance = Importance.NORMAL,
            source = verdict.source,
            reason = "Kept: ${reasons.joinToString(
                ", ",
            )} (would have been ${verdict.action.name.lowercase()}: ${verdict.reason})",
            confidence = verdict.confidence,
        )
    }

    companion object {
        val NONE = Protection()
    }
}

/**
 * Decides which messages must never be discarded, from the [index] of people the user writes
 * to, optional [contacts], and the message's own security-code and money signals.
 */
class Protections(
    private val index: RelationshipIndex,
    private val contacts: ContactsSource? = null,
) {
    /** [signals] avoids re-scanning the text when they are already extracted. */
    fun evaluate(
        message: MailItem,
        signals: Signals? = null,
    ): Protection {
        val header = message.header
        val from = header.from
        val content by lazy(LazyThreadSafetyMode.NONE) { SignalExtractor.contentText(message) }
        return Protection(
            sentTo = from != null && index.hasSentTo(from),
            contact = from != null && isContact(from.email),
            repliedThread = index.repliedInThread(header),
            vip = from != null && index.isVip(from),
            securityCode = signals?.securityCode ?: Keywords.securityCode(content),
            money = signals?.moneySignal ?: Keywords.money(content),
        )
    }

    fun blocksDiscard(
        message: MailItem,
        signals: Signals? = null,
    ): Boolean = evaluate(message, signals).blocksDiscard

    /**
     * Checks the address as written and without its `+tag`. A failing lookup counts as "not a
     * contact"; the other protections still apply.
     */
    private fun isContact(email: String): Boolean {
        val source = contacts ?: return false
        return try {
            source.isContact(email) || RelationshipIndex.key(email).let { it != email && source.isContact(it) }
        } catch (_: RuntimeException) {
            false
        }
    }
}
