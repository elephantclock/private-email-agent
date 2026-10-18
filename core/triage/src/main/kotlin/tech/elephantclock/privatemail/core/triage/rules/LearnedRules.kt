package tech.elephantclock.privatemail.core.triage.rules

import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.signals.Domains

/** A user's correction: "mail like this belongs in [target]". */
data class Correction(
    /** Null makes the rule apply to every account. */
    val accountId: AccountId?,
    val sender: Address,
    val target: FolderTarget,
    val scope: Scope = Scope.SENDER,
    /** Also mark the sender as VIP (forces Priority). */
    val vip: Boolean = false,
) {
    enum class Scope { SENDER, DOMAIN }
}

/**
 * Rules learned from the user's corrections (sender or domain → folder or action).
 *
 * When several match, the most specific wins: an exact sender before a domain, a longer domain
 * before a shorter one, an account's own rule before a global one, then the newest.
 */
class LearnedRules(
    rules: List<TriageRule>,
) {
    private val ordered: List<TriageRule> = rules.sortedWith(SPECIFICITY)

    fun match(
        account: AccountId,
        sender: Address,
    ): TriageRule? = ordered.firstOrNull { it.appliesTo(account, sender) }

    companion object {
        /**
         * Turns a correction into a rule. A domain-wide correction on a free-mail domain
         * (`gmail.com`, `mail.ru`, ...) becomes a sender rule: "discard everyone at gmail.com"
         * is never what the user meant. A VIP correction always targets Priority.
         */
        fun fromCorrection(
            correction: Correction,
            id: Long,
            nowMillis: Long,
        ): TriageRule {
            val sender = correction.sender
            val domain = sender.domain
            val domainOk =
                correction.scope == Correction.Scope.DOMAIN &&
                    domain.isNotBlank() &&
                    '.' in domain &&
                    domain !in FREE_MAIL &&
                    Domains.registrable(domain) !in FREE_MAIL
            val matcher = if (domainOk) RuleMatcher.Domain(domain) else RuleMatcher.Sender(sender.email)
            val target = if (correction.vip) FolderTarget.Priority else correction.target
            return TriageRule(
                id = id,
                accountId = correction.accountId,
                matcher = matcher,
                action = actionFor(target),
                target = target,
                createdAtMillis = nowMillis,
                vip = correction.vip,
            )
        }

        /**
         * Adds [rule] to [existing], replacing any rule with the same matcher and account
         * (the latest correction wins).
         */
        fun upsert(
            existing: List<TriageRule>,
            rule: TriageRule,
        ): List<TriageRule> = existing.filterNot { it.matcher == rule.matcher && it.accountId == rule.accountId } + rule

        fun actionFor(target: FolderTarget): VerdictAction =
            when (target.role) {
                FolderRole.PRIORITY -> VerdictAction.KEEP
                FolderRole.DISCARDED -> VerdictAction.DISCARD
                FolderRole.JUNK -> VerdictAction.JUNK
                else -> VerdictAction.FILE
            }

        /** The verdict a matching rule gives, with a stable reason. */
        fun verdict(rule: TriageRule): Verdict {
            val who =
                when (val m = rule.matcher) {
                    is RuleMatcher.Sender -> m.email
                    is RuleMatcher.Domain -> m.domain
                }
            val source = VerdictSource.RULE
            return when {
                rule.vip -> {
                    Verdict.keep(Importance.URGENT, source, "Your rule: $who is a VIP")
                }

                else -> {
                    when (rule.action) {
                        VerdictAction.KEEP -> {
                            Verdict.keep(Importance.NORMAL, source, "Your rule: keep mail from $who in Priority")
                        }

                        VerdictAction.FILE -> {
                            Verdict.file(
                                rule.target,
                                Importance.NORMAL,
                                source,
                                "Your rule: mail from $who goes to ${rule.target.label()}",
                            )
                        }

                        VerdictAction.DISCARD -> {
                            Verdict.discard(source, "Your rule: discard mail from $who")
                        }

                        VerdictAction.JUNK -> {
                            Verdict.junk(source, "Your rule: mail from $who is junk")
                        }
                    }
                }
            }
        }

        private val SPECIFICITY: Comparator<TriageRule> =
            compareBy<TriageRule> { if (it.matcher is RuleMatcher.Sender) 0 else 1 }
                .thenByDescending { (it.matcher as? RuleMatcher.Domain)?.domain?.length ?: 0 }
                .thenBy { if (it.accountId != null) 0 else 1 }
                .thenByDescending { it.createdAtMillis }
                .thenByDescending { it.id }

        /** Free-mail domains: a domain rule there would cover strangers. */
        val FREE_MAIL: Set<String> =
            setOf(
                "gmail.com",
                "googlemail.com",
                "outlook.com",
                "hotmail.com",
                "live.com",
                "msn.com",
                "yahoo.com",
                "yahoo.co.uk",
                "yahoo.de",
                "yahoo.fr",
                "ymail.com",
                "icloud.com",
                "me.com",
                "mac.com",
                "aol.com",
                "proton.me",
                "protonmail.com",
                "pm.me",
                "gmx.de",
                "gmx.net",
                "gmx.com",
                "web.de",
                "t-online.de",
                "mail.ru",
                "bk.ru",
                "list.ru",
                "inbox.ru",
                "internet.ru",
                "yandex.ru",
                "yandex.com",
                "ya.ru",
                "yandex.by",
                "yandex.kz",
                "rambler.ru",
                "zoho.com",
                "orange.fr",
                "free.fr",
                "laposte.net",
                "mail.com",
                "email.com",
                "qq.com",
                "163.com",
                "ukr.net",
                "tut.by",
            )

        /** Declared last: the constructor needs the comparator above. */
        val EMPTY = LearnedRules(emptyList())
    }
}
