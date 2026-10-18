package tech.elephantclock.privatemail.core.triage.rules

import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.people.Protection
import tech.elephantclock.privatemail.core.triage.people.Protections
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor

/**
 * Fast, explainable verdicts. Rules run in order and the first that is sure wins:
 *
 * 1. learned rules (the user's corrections, most specific first);
 * 2. protections: a VIP stays in Priority as URGENT; other protections don't decide, they
 *    forbid what follows (see [Protection]);
 * 3. phishing → Junk; 4. bulk/marketing → Discarded; 5. low-value notifications → Discarded;
 * 6. role folders by signals (security code, money, orders, work tools, reports);
 * 7. otherwise [Decision.NeedsAi].
 *
 * **Protection gate.** A rule whose verdict the message's protection forbids is skipped and the
 * next rule runs, so a learned "discard this domain" can't discard someone the user writes to,
 * and phishing can't junk them. Each final verdict also goes through [Protection.enforce].
 *
 * Build one per account and reuse it; [decide] is thread-safe and never throws on hostile input.
 */
class RulesEngine(
    private val account: AccountId,
    private val protections: Protections,
    private val extractor: SignalExtractor = SignalExtractor(),
    private val learned: LearnedRules = LearnedRules.EMPTY,
) {
    fun decide(message: MailItem): Decision = decide(message, extractor.extract(message))

    fun decide(
        message: MailItem,
        signals: Signals,
    ): Decision {
        val protection = protections.evaluate(message, signals)
        val context = RuleContext(message, signals, protection)

        val sender = message.header.from
        val rule = sender?.let { learned.match(account, it) }
        if (rule != null) {
            final(LearnedRules.verdict(rule), "learned:${rule.id}", context)?.let { return it }
        }
        if (protection.vip) {
            final(Verdict.keep(Importance.URGENT, VerdictSource.RULE, "VIP sender"), VIP, context)?.let { return it }
        }
        for (r in PIPELINE) {
            val verdict = r.check(context) ?: continue
            final(verdict, r.id, context)?.let { return it }
        }
        return Decision.NeedsAi(needsAiReason(protection), signals, protection)
    }

    /** A final decision, or null when the protection forbids [verdict] (the next rule then runs). */
    private fun final(
        verdict: Verdict,
        ruleId: String,
        context: RuleContext,
    ): Decision.Final? {
        if (context.protection.forbids(verdict.action)) return null
        return Decision.Final(context.protection.enforce(verdict), ruleId, context.signals, context.protection)
    }

    private fun needsAiReason(protection: Protection): String =
        if (protection.blocksDiscard) {
            "No rule matched; protected (${protection.reasons.joinToString(", ")})"
        } else {
            "No rule matched"
        }

    companion object {
        const val VIP = "vip"

        private val PIPELINE: List<Rule> =
            BuiltInRules.PHISHING + BuiltInRules.BULK + BuiltInRules.LOW_VALUE + BuiltInRules.ROLE_FOLDERS
    }
}
