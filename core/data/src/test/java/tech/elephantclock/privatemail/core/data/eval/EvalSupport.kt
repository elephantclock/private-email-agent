// Report-building lines are long text; wrapping them hurts readability.
@file:Suppress("ktlint:standard:max-line-length")

package tech.elephantclock.privatemail.core.data.eval

import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.search.HeaderTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.RecipientStringTerm
import org.eclipse.angus.mail.imap.IMAPStore
import tech.elephantclock.privatemail.core.data.db.VerdictEntity
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.model.AuthResult
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.Signals
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.Properties

data class WatcherEntry(
    val bucket: String,
    val label: String,
)

data class EvalRow(
    val uid: Long,
    val from: String,
    val subject: String,
    val snippetEmpty: Boolean,
    val snippetHtml: Boolean,
    /** The stored preview text; used only for counts in the report, never written out. */
    val preview: String,
    /** Domains of the Reply-To addresses (no local parts: those can be customers). */
    val replyToDomains: List<String> = emptyList(),
    val signals: Signals,
    val verdict: VerdictEntity?,
    val inReplyTo: List<String>,
    val watcher: WatcherEntry?,
) {
    /** "KEEP URGENT", "FILE WORK", "DISCARD", "JUNK", "untriaged". */
    val ours: String
        get() =
            verdict?.let { v ->
                when (v.action.name) {
                    "KEEP" -> "KEEP ${v.importance.name}"
                    "FILE" -> "FILE ${v.targetCustomName ?: v.targetRole.name}"
                    else -> v.action.name
                }
            } ?: "untriaged"
}

/** The Mac watcher's buckets and how they line up with our decisions. */
object WatcherBuckets {
    val NEEDS_USER = setOf("1 Urgent", "2 Customer Support", "3 Needs Reply")

    fun agrees(
        bucket: String,
        ours: String,
    ): Boolean? =
        when (bucket) {
            "1 Urgent" -> {
                ours == "KEEP URGENT" || ours == "KEEP HIGH"
            }

            "2 Customer Support", "3 Needs Reply" -> {
                ours.startsWith("KEEP") || ours.startsWith("FILE PEOPLE") ||
                    ours.startsWith("FILE ")
            }

            "4 Dev & Review" -> {
                ours == "FILE WORK" || ours.startsWith("KEEP")
            }

            "5 Notifications" -> {
                ours == "DISCARD" || ours == "FILE REPORTS"
            }

            "6 Reports" -> {
                ours == "FILE REPORTS"
            }

            "7 Marketing & Spam" -> {
                ours == "DISCARD" || ours == "JUNK"
            }

            else -> {
                null
            } // Unsorted: the watcher makes no claim
        }

    const val MAPPING =
        "1 Urgent → KEEP URGENT/HIGH · 2 Customer Support, 3 Needs Reply → KEEP or a value folder · " +
            "4 Dev & Review → FILE WORK (KEEP accepted) · 5 Notifications → DISCARD or FILE REPORTS (ambiguous) · " +
            "6 Reports → FILE REPORTS · 7 Marketing & Spam → DISCARD or JUNK · Unsorted → no claim"
}

/** The topmost Authentication-Results instance per message: who wrote it. */
class AuthIds private constructor(
    val counts: Map<String, Int>,
    val total: Int,
) {
    fun dominant(): String? =
        counts.entries
            .filter { it.key != NONE }
            .maxByOrNull { it.value }
            ?.takeIf { it.value * 10 >= total * 6 }
            ?.key

    companion object {
        const val NONE = "(no Authentication-Results)"

        fun of(headers: List<MessageHeader>): AuthIds {
            val ids =
                headers.map { h ->
                    h.headers["authentication-results"]
                        ?.firstOrNull()
                        ?.substringBefore(';')
                        ?.trim()
                        ?.split(Regex("\\s+"))
                        ?.firstOrNull()
                        ?.lowercase(Locale.ROOT)
                        ?.ifBlank { null } ?: NONE
                }
            return AuthIds(ids.groupingBy { it }.eachCount(), headers.size)
        }
    }
}

/** Read-only searches of the server's Sent folders (EXAMINE + SEARCH), independent of triage's own index. */
class SentSearch(
    host: String,
    port: Int,
    username: String,
    password: Password,
) : Closeable {
    private val store =
        (Session.getInstance(Properties().apply { put("mail.imaps.timeout", "60000") }).getStore("imaps") as IMAPStore)
            .apply { connect(host, port, username, password.reveal()) }
    private val folders = mutableMapOf<String, Folder>()
    private val wrote = mutableMapOf<String, Boolean>()
    private val own = mutableMapOf<String, Boolean>()

    private fun folder(path: String): Folder =
        folders.getOrPut(path) {
            store.getFolder(path).apply { open(Folder.READ_ONLY) }
        }

    fun wroteTo(
        sent: List<String>,
        address: String,
    ): Boolean =
        address.isNotBlank() &&
            wrote.getOrPut(address.lowercase()) {
                val term =
                    OrTerm(
                        RecipientStringTerm(Message.RecipientType.TO, address),
                        RecipientStringTerm(Message.RecipientType.CC, address),
                    )
                sent.any { folder(it).search(term).isNotEmpty() }
            }

    fun ownMessage(
        sent: List<String>,
        messageId: String,
    ): Boolean =
        own.getOrPut(messageId) {
            sent.any { folder(it).search(HeaderTerm("Message-ID", messageId)).isNotEmpty() }
        }

    fun capabilities(): String =
        listOf("CONDSTORE", "ENABLE", "QRESYNC", "IDLE", "MOVE", "UIDPLUS", "SPECIAL-USE")
            .joinToString(", ") { "$it=${store.hasCapability(it)}" }

    override fun close() {
        folders.values.forEach { runCatching { it.close(false) } }
        runCatching { store.close() }
    }
}

/** Builds `eval/report.md`. Lives only on this Mac (gitignored). */
class EvalReport(
    private val account: String,
) {
    private val sb = StringBuilder()

    private fun line(text: String = "") = sb.appendLine(text)

    fun sync(
        first: String,
        firstMillis: Long,
        secondMillis: Long,
    ) {
        line("## Sync (read-only)")
        line("- First sync: ${firstMillis / 1000.0} s; second sync (reconcile path): ${secondMillis / 1000.0} s")
        line("- First sync result: `$first`")
        line()
    }

    fun auth(
        ids: AuthIds,
        trusted: Set<String>,
    ) {
        line("## Authentication-Results: who writes the topmost header (Inbox, ${ids.total} messages)")
        ids.counts.entries
            .sortedByDescending { it.value }
            .take(10)
            .forEach { line("- `${it.key}`: ${it.value}") }
        line(
            "- Trusted for this run: ${trusted.ifEmpty {
                setOf(
                    "none (no dominant id, topmost instance used)",
                )
            }.joinToString()}",
        )
        line()
    }

    fun backfill(result: String) {
        line("## Sent backfill (12 months of headers)")
        line("- $result")
        line()
    }

    fun rows(
        rows: List<EvalRow>,
        unsafe: Map<EvalRow, List<String>>,
        review: List<EvalRow>,
        capabilities: String,
        folders: List<String>,
        watcherCount: Int,
        moverSignIns: Int,
        signIns: Int,
    ) {
        line("## Safety")
        line("- Mover sign-ins (must be 0): $moverSignIns · read-only sign-ins: $signIns")
        line("- DISCARD/JUNK decisions: ${rows.count { it.ours == "DISCARD" || it.ours == "JUNK" }}")
        line(
            "- **Protected messages discarded or junked (must be 0): ${unsafe.size}** (senders you wrote to anywhere in the server's Sent folder, threads you replied in)",
        )
        unsafe.forEach { (r, why) ->
            line(
                "  - UID ${r.uid} · ${r.from} · \"${r.subject.take(
                    80,
                )}\" · ${r.ours} (${r.verdict?.reason}) · ${why.joinToString()}",
            )
        }
        line("- For review: discarded/junked although the watcher said it needs you: ${review.size}")
        review.forEach { r ->
            line(
                "  - UID ${r.uid} · ${r.from} · \"${r.subject.take(
                    80,
                )}\" · ${r.ours} (${r.verdict?.reason}) · watcher ${r.watcher?.bucket}",
            )
        }
        line()

        line("## Sender domains per decision (top 6)")
        rows.groupBy { it.ours }.entries.sortedByDescending { it.value.size }.forEach { (decision, rs) ->
            val domains =
                rs
                    .groupingBy {
                        it.from.substringAfter(
                            '@',
                        )
                    }.eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(6)
            line("- $decision: ${domains.joinToString { "${it.key} ${it.value}" }}")
        }
        line()

        securityCodeDiagnosis(rows)
        supportDiagnosis(rows)
        line("## Reasons per decision")
        rows
            .groupingBy { "${it.ours} · ${it.verdict?.reason?.replace(Regex("\\d+"), "#")?.take(80)}" }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(40)
            .forEach { line("- ${it.value} × ${it.key}") }
        line()

        line("## Decisions (${rows.size} Inbox messages)")
        rows
            .groupingBy {
                it.ours
            }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .forEach { line("- ${it.key}: ${it.value}") }
        line()

        val matched = rows.filter { it.watcher != null }
        line("## Agreement with the Mac watcher")
        line("- Watcher messages: $watcherCount · ours: ${rows.size} · matched by UID: ${matched.size}")
        line("- Mapping: ${WatcherBuckets.MAPPING}")
        val judged = matched.mapNotNull { r -> WatcherBuckets.agrees(r.watcher!!.bucket, r.ours)?.let { r to it } }
        line("- Agree: ${judged.count { it.second }} of ${judged.size} where the watcher makes a claim")
        line()
        line("| Watcher bucket | Our decisions |")
        line("|---|---|")
        matched.groupBy { it.watcher!!.bucket }.toSortedMap().forEach { (bucket, rs) ->
            line(
                "| $bucket (${rs.size}) | ${rs.groupingBy {
                    it.ours
                }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }} |",
            )
        }
        line()
        line("### Disagreements")
        judged.filterNot { it.second }.map { it.first }.forEach { r ->
            line(
                "- UID ${r.uid} · ${r.from} · \"${r.subject.take(
                    70,
                )}\" · watcher **${r.watcher!!.bucket}** (${r.watcher.label}) · ours **${r.ours}** (${r.verdict?.reason?.take(
                    90,
                )})",
            )
        }
        line()

        line("## Carried-over measurements")
        val empty = rows.count { it.snippetEmpty }
        line(
            "- Empty previews: $empty of ${rows.size} (HTML source ${rows.count {
                it.snippetEmpty && it.snippetHtml
            }}, plain ${rows.count { it.snippetEmpty && !it.snippetHtml }})",
        )
        line("- Server capabilities: $capabilities")
        line("- Folders named Triage/* (watcher --apply ever used?): ${folders.count { it.startsWith("Triage") }}")
        val dmarcUnknown = rows.count { it.signals.auth.dmarc == AuthResult.UNKNOWN }
        line("- DMARC unknown after the trusted-id rule: $dmarcUnknown of ${rows.size}")
        line(
            "- Orders filings with bulk signals: ${rows.count {
                it.ours == "FILE ORDERS" && it.signals.isBulk
            }} of ${rows.count { it.ours == "FILE ORDERS" }}",
        )
        val money =
            rows.filter { r ->
                r.signals.isBulk && r.signals.moneySignal && (r.ours.startsWith("KEEP") || r.ours == "FILE MONEY")
            }
        line("- Known failure 45 (bulk mail kept or filed only thanks to the money protection): ${money.size}")
        money.forEach { line("  - ${it.from} · \"${it.subject.take(70)}\" · ${it.ours}") }
        line("- Phishing / junk verdicts (review each):")
        rows
            .filter {
                it.ours == "JUNK"
            }.forEach { line("  - ${it.from} · \"${it.subject.take(70)}\" · ${it.verdict?.reason}") }
        line("- Look-alike signals on kept mail: ${rows.count { it.signals.lookalikeOf != null && it.ours != "JUNK" }}")
        rows
            .filter {
                it.signals.lookalikeOf != null
            }.forEach { line("  - ${it.from} → looks like ${it.signals.lookalikeOf} · ${it.ours}") }
    }

    fun render(): String = "# T25 evaluation: $account (read-only)\n\n$sb"

    /** Which security phrase matched, and whether the only "code" is a year: counts only, no content. */
    private fun securityCodeDiagnosis(rows: List<EvalRow>) {
        val flagged = rows.filter { it.verdict?.reason == "Security code" }
        if (flagged.isEmpty()) return
        val source =
            File(
                System.getProperty("pea.repoRoot")!!,
                "core/triage/src/main/kotlin/tech/elephantclock/privatemail/core/triage/signals/KeywordLists.kt",
            ).readText()
        val block = source.substringAfter("SECURITY_PHRASES =").substringBefore("\n    val ")
        val phrases = Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
        line("## Security-code diagnosis (${flagged.size} messages; counts only)")
        val counts =
            phrases.associateWith { ph ->
                flagged.count { (it.subject + " " + it.preview).lowercase().contains(ph) }
            }
        counts.entries
            .filter {
                it.value > 0
            }.sortedByDescending { it.value }
            .forEach { line("- phrase \"${it.key}\": ${it.value}") }
        val codes = Regex("(?<![\\w/#])\\d{4,8}(?![\\w/#])")
        val yearOnly =
            flagged.count { r ->
                codes.findAll(r.subject + " " + r.preview).map { it.value }.toList().let { m ->
                    m.isNotEmpty() &&
                        m.all { it.length == 4 && (it.startsWith("19") || it.startsWith("20")) }
                }
            }
        line("- messages whose only 4–8 digit numbers are years (19xx/20xx): $yearOnly")
        line()
    }

    /** Customer-support mail we filed: which system address sent it and whether Reply-To points elsewhere. */
    private fun supportDiagnosis(rows: List<EvalRow>) {
        val filed = rows.filter { it.watcher?.bucket == "2 Customer Support" && it.ours.startsWith("FILE") }
        if (filed.isEmpty()) return
        line("## Customer-support mail we filed (${filed.size}; system sender addresses only)")
        filed
            .groupingBy {
                "${it.from} → ${it.ours}"
            }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(8)
            .forEach { line("- ${it.value} × ${it.key}") }
        line(
            "- with a Reply-To: ${filed.count {
                it.replyToDomains.isNotEmpty()
            }}; Reply-To domains: ${filed.flatMap { it.replyToDomains }.groupingBy { it }.eachCount()}",
        )
        line()
    }
}
