package tech.elephantclock.privatemail.core.triage.rules

import tech.elephantclock.privatemail.core.model.AuthResult
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Signals
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.people.Protection
import tech.elephantclock.privatemail.core.triage.signals.Domains
import tech.elephantclock.privatemail.core.triage.signals.Keywords
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor

/** Everything a rule may look at. Text checks are lazy so most messages never run them. */
class RuleContext(
    val message: MailItem,
    val signals: Signals,
    val protection: Protection,
) {
    val content: String by lazy(LazyThreadSafetyMode.NONE) { SignalExtractor.contentText(message) }

    /** Registrable sender domain, e.g. `github.com` for `notifications.github.com`. */
    val senderRegistrable: String? by lazy(LazyThreadSafetyMode.NONE) {
        Domains.normalize(message.header.from?.domain)?.let(Domains::registrable)
    }

    /** Sent by a machine: no-reply sender, `Auto-Submitted`, or any bulk header. */
    val automated: Boolean get() = signals.noReplySender || signals.autoSubmitted || signals.isBulk

    val accountSecurity: Boolean by lazy(LazyThreadSafetyMode.NONE) { Keywords.accountSecurity(content) }
    val ordersOrTravel: Boolean by lazy(LazyThreadSafetyMode.NONE) { Keywords.ordersOrTravel(content) }
    val workTool: Boolean get() = senderRegistrable in BuiltInRules.WORK_TOOLS
}

/** One built-in rule: a stable [id] and a check that returns a verdict when the rule is sure. */
class Rule(
    val id: String,
    val check: (RuleContext) -> Verdict?,
)

/**
 * The built-in rules, in the order they run (after learned rules and the VIP check, see
 * [RulesEngine]): phishing → bulk/marketing → low-value notifications → role folders.
 *
 * They are tuned for precision: discard precision must stay at or above 98%, so anything
 * doubtful is left to the AI. Authentication is used only as evidence against a message
 * (a FAIL); a PASS never relaxes anything, because without the user's own authserv-id the
 * header may be the sender's.
 */
object BuiltInRules {
    private val src = VerdictSource.RULE

    val PHISHING: List<Rule> =
        listOf(
            Rule("phishing.lookalike") { c ->
                c.signals.lookalikeOf?.let { Verdict.junk(src, "Sender domain imitates $it") }
            },
            Rule("phishing.auth") { c ->
                val a = c.signals.auth
                val failed = a.dmarc == AuthResult.FAIL && a.spf in SPF_FAILED && a.dkim != AuthResult.PASS
                if (failed) Verdict.junk(src, "Failed SPF and DMARC authentication") else null
            },
            Rule("phishing.hidden-characters") { c ->
                if (c.signals.hiddenCharacters && c.signals.auth.anyFail) {
                    Verdict.junk(src, "Hidden characters in the sender name or subject, and failed authentication")
                } else {
                    null
                }
            },
        )

    val BULK: List<Rule> =
        listOf(
            Rule("bulk.marketing") { c ->
                val s = c.signals
                val indicator =
                    when {
                        s.esp != null -> "sent through ${s.esp}"

                        s.precedenceBulk -> "Precedence: bulk"

                        !c.message.header
                            .header("list-unsubscribe-post")
                            .isNullOrBlank() -> "one-click unsubscribe"

                        else -> null
                    }
                val marketing =
                    s.hasListUnsubscribe && indicator != null && !s.isReply && !c.workTool &&
                        !c.ordersOrTravel && !c.accountSecurity
                if (marketing) Verdict.discard(src, "Newsletter or marketing: unsubscribe link, $indicator") else null
            },
        )

    val LOW_VALUE: List<Rule> =
        listOf(
            Rule("low-value.social") { c ->
                val domain = c.senderRegistrable
                val social =
                    domain in SOCIAL_NOTIFIERS && c.automated && !c.signals.isReply &&
                        !c.accountSecurity && !Keywords.personalMessage(c.content)
                if (social) Verdict.discard(src, "Social network notification from $domain") else null
            },
        )

    val ROLE_FOLDERS: List<Rule> =
        listOf(
            Rule("role.security-code") { c ->
                if (c.signals.securityCode) Verdict.keep(Importance.HIGH, src, "Security code") else null
            },
            Rule("role.money") { c ->
                if (c.signals.moneySignal && c.automated) {
                    Verdict.file(FolderTarget(FolderRole.MONEY), Importance.NORMAL, src, "Payment, invoice or receipt")
                } else {
                    null
                }
            },
            Rule("role.orders") { c ->
                if (c.automated && c.ordersOrTravel) {
                    Verdict.file(FolderTarget(FolderRole.ORDERS), Importance.NORMAL, src, "Order, delivery or booking")
                } else {
                    null
                }
            },
            Rule("role.work") { c ->
                if (c.workTool && c.automated) {
                    Verdict.file(
                        FolderTarget(FolderRole.WORK),
                        Importance.NORMAL,
                        src,
                        "Notification from ${c.senderRegistrable}",
                    )
                } else {
                    null
                }
            },
            Rule("role.reports") { c ->
                val report =
                    c.automated && !c.signals.hasListUnsubscribe && !c.accountSecurity && Keywords.report(c.content)
                if (report) {
                    Verdict.file(
                        FolderTarget(FolderRole.REPORTS),
                        Importance.LOW,
                        src,
                        "Automated report or alert",
                    )
                } else {
                    null
                }
            },
        )

    private val SPF_FAILED = setOf(AuthResult.FAIL, AuthResult.SOFTFAIL)

    /** Developer and work tools whose notifications go to Work updates. */
    val WORK_TOOLS: Set<String> =
        setOf(
            "github.com",
            "gitlab.com",
            "atlassian.net",
            "atlassian.com",
            "bitbucket.org",
            "linear.app",
            "sentry.io",
            "asana.com",
            "trello.com",
            "notion.so",
            "figma.com",
            "youtrack.cloud",
            "jetbrains.com",
            "circleci.com",
            "travis-ci.com",
            "vercel.com",
            "netlify.com",
            "zendesk.com",
            "freshdesk.com",
            "intercom.io",
            "clickup.com",
            "monday.com",
            "basecamp.com",
        )

    /** Social networks whose automated mail (likes, digests, "people you may know") is low value. */
    val SOCIAL_NOTIFIERS: Set<String> =
        setOf(
            "facebookmail.com",
            "facebook.com",
            "instagram.com",
            "linkedin.com",
            "twitter.com",
            "x.com",
            "pinterest.com",
            "tiktok.com",
            "quora.com",
            "medium.com",
            "redditmail.com",
            "reddit.com",
            "vk.com",
            "ok.ru",
            "threads.net",
            "snapchat.com",
            "tumblr.com",
        )
}
