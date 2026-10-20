package tech.elephantclock.privatemail.core.data.triage

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.toBody
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.db.toFlags
import tech.elephantclock.privatemail.core.data.db.toHeader
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.people.ContactsSource
import tech.elephantclock.privatemail.core.triage.rules.Decision
import java.util.concurrent.TimeUnit

/** What one [TriageOrchestrator.drain] did. */
data class TriageReport(
    val triaged: Int = 0,
    val byAi: Int = 0,
    val alerts: Int = 0,
    val moves: MoveOutcome = MoveOutcome(),
) {
    operator fun plus(o: TriageReport) =
        TriageReport(
            triaged + o.triaged,
            byAi + o.byAi,
            alerts + o.alerts,
            moves + o.moves,
        )
}

/**
 * Where a pass over rules-only verdicts got to: newest first, by (received, local id).
 * [START] is before the newest message.
 */
data class BacklogCursor(
    val receivedAtMillis: Long,
    val id: Long,
) {
    companion object {
        val START = BacklogCursor(Long.MAX_VALUE, Long.MAX_VALUE)
    }
}

/** One [TriageOrchestrator.reclassifyRulesOnly] batch: rows looked at, what was done, where to go on. */
data class BacklogPass(
    val considered: Int,
    val report: TriageReport,
    val next: BacklogCursor,
)

/**
 * One pipeline from new message to action (T24): for each untriaged Inbox message, signals →
 * rules → [TriageAi] when the rules can't decide ([Decision.NeedsAi.accept]) → the protections
 * on every verdict → verdict stored → move per the account's [AutoFile] switches ([TriageMover])
 * → alert for URGENT/HIGH, as the account's alert levels allow ([TriageNotifier]).
 *
 * - **Durable:** the work list is the database (messages in INBOX without a verdict), not the
 *   [MailRepository.newMessages] nudges, which are lost when nobody listens. Every message
 *   taken from the list gets a verdict, even when the AI is unavailable or the row is
 *   unreadable, so a drain always ends. A UIDVALIDITY reset drops verdicts; those messages
 *   come back and are triaged again.
 * - **Label only by default:** with auto-file off nothing moves; verdicts are stored for the
 *   first-run review. Moves that didn't go through stay pending and are retried next drain.
 * - **Alerts** only for mail that is new to this account (received after it was added and
 *   within a day), unread, still in the Inbox, and not failing authentication.
 * - **Contacts:** [contacts] returns the app's Android `ContactsSource` for an account (null
 *   without the permission); it joins the relationship protections.
 *
 * - **Rules-only backlog:** when the AI can't run (background, model missing, busy) a NeedsAi
 *   message is kept in Priority with a provisional verdict ([RULES_ONLY_MARK], confidence 0).
 *   [reclassifyRulesOnly] asks the AI again for those, newest first, through the same steps
 *   (accept, protections, alerts, auto-file); a verdict the user changed meanwhile is left alone.
 *
 * Entry points: [run] for a long-lived scope (drains at start and on every nudge), [drain]
 * for one pass (the sync worker after a sync) and [reclassifyRulesOnly] for the foreground AI
 * drain. They never overlap.
 */
class TriageOrchestrator(
    private val db: AppDatabase,
    private val repo: MailRepository,
    private val mover: TriageMover,
    private val ai: TriageAi = TriageAi.UNAVAILABLE,
    settings: TriageSettings = TriageSettings.DEFAULTS,
    private val notifier: TriageNotifier = TriageNotifier.NONE,
    contacts: (AccountId) -> ContactsSource? = { null },
    relationships: RelationshipRepository = RelationshipRepository(db),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Rules, JSON decoding and the contacts lookup are CPU or blocking work: never on Main. */
    private val work: CoroutineDispatcher = Dispatchers.Default,
) {
    private val factory = AccountTriageFactory(db, relationships, settings, contacts)
    private val drainLock = Mutex()

    /** Drains now and again whenever the sync stores new mail; runs until cancelled. */
    suspend fun run(): Unit =
        coroutineScope {
            val nudges = Channel<Unit>(Channel.CONFLATED)
            // Subscribed before the first drain, so mail stored during it is not missed.
            launch(start = CoroutineStart.UNDISPATCHED) { repo.newMessages.collect { nudges.trySend(Unit) } }
            nudges.trySend(Unit)
            for (nudge in nudges) drainQuietly()
        }

    /** Triages every untriaged Inbox message, and retries pending moves. */
    suspend fun drain(): TriageReport = withContext(work) { drainLocked() }

    private suspend fun drainLocked(): TriageReport =
        drainLock.withLock {
            var report = retryPendingMoves()
            val seen = HashSet<Long>()
            while (true) {
                val batch = db.triage().untriagedIn(MailRepository.INBOX, BATCH)
                // Safety valve: rows that keep coming back (their verdicts can't be stored) end the drain.
                if (batch.isEmpty() || !seen.addAll(batch.map { it.id })) break
                for ((accountId, rows) in batch.groupBy { it.accountId }) {
                    val triage = factory.prepare(accountId) ?: continue
                    report += triageAccount(triage, rows)
                }
            }
            report
        }

    /**
     * Asks [ai] again about up to [limit] Inbox messages left with the rules-only fallback,
     * newest first, older than [after]. Only verdicts the AI actually gave replace the fallback;
     * the rest stay as they are (the next pass, or the next foreground, tries again).
     */
    suspend fun reclassifyRulesOnly(
        after: BacklogCursor,
        limit: Int,
        ai: TriageAi = this.ai,
    ): BacklogPass =
        withContext(work) {
            drainLock.withLock {
                val rows =
                    db.triage().rulesOnlyIn(
                        MailRepository.INBOX,
                        RULES_ONLY_MARK,
                        after.receivedAtMillis,
                        after.id,
                        limit,
                    )
                var report = TriageReport()
                for ((accountId, group) in rows.groupBy { it.accountId }) {
                    val triage = factory.prepare(accountId) ?: continue
                    report += triageAccount(triage, group, ai, revisit = true)
                }
                val next = rows.lastOrNull()?.let { BacklogCursor(it.receivedAtMillis, it.id) } ?: after
                BacklogPass(rows.size, report, next)
            }
        }

    private suspend fun drainQuietly() {
        try {
            drain()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A database or server surprise; the next nudge or worker run tries again.
        }
    }

    private class Classified(
        val row: MessageEntity,
        val verdict: Verdict,
        val byAi: Boolean,
    )

    private suspend fun triageAccount(
        triage: AccountTriage,
        rows: List<MessageEntity>,
        ai: TriageAi = this.ai,
        revisit: Boolean = false,
    ): TriageReport {
        val now = clock()
        val classified = rows.map { classify(triage, it, ai) }.filter { !revisit || it.byAi }
        if (classified.isEmpty()) return TriageReport()
        val autoFile = triage.settings.autoFile
        val results =
            db.withTransaction {
                // Revisiting: the user (or an undo, or a move) may have changed it meanwhile; that wins.
                val current =
                    if (revisit) {
                        val still =
                            db
                                .triage()
                                .stillRulesOnly(classified.map { it.row.id }, MailRepository.INBOX, RULES_ONLY_MARK)
                                .toSet()
                        classified.filter { it.row.id in still }
                    } else {
                        classified
                    }
                val plannedIds = current.filter { autoFile.allows(it.verdict) }.map { it.row.id }.toSet()
                db.verdicts().upsertAll(
                    current.map { it.verdict.toEntity(it.row.id, now).copy(pendingMove = it.row.id in plannedIds) },
                )
                current
            }
        val planned = results.filter { autoFile.allows(it.verdict) }
        var alerts = 0
        for (c in results) {
            val alert = alertFor(triage, c) ?: continue
            runCatching { notifier.notify(alert) }.onSuccess { alerts++ }
        }
        val moves =
            mover.apply(
                triage.account,
                planned.map { PlannedMove(it.row, it.verdict.target, it.verdict.action) },
            )
        return TriageReport(results.size, results.count { it.byAi }, alerts, moves)
    }

    private suspend fun classify(
        triage: AccountTriage,
        row: MessageEntity,
        ai: TriageAi,
    ): Classified {
        val item =
            try {
                MailItem(row.toHeader(), row.toBody())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return Classified(row, Verdict.keep(Importance.NORMAL, VerdictSource.RULE, UNREADABLE, 0f), false)
            }
        val decision = triage.engine.decide(item)
        val (verdict, byAi) =
            when (decision) {
                is Decision.Final -> {
                    decision.verdict to false
                }

                is Decision.NeedsAi -> {
                    askAi(ai, item, decision)?.let { decision.accept(it) to true }
                        ?: (rulesOnly(decision) to false)
                }
            }
        return Classified(row, noAlertIfUnauthenticated(decision, decision.protection.enforce(verdict)), byAi)
    }

    private suspend fun askAi(
        ai: TriageAi,
        item: MailItem,
        decision: Decision.NeedsAi,
    ): Verdict? =
        try {
            ai.classify(item, decision)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /**
     * Left in Priority. Confidence 0 marks it provisional: the AI queue (T35) may classify these
     * again once the model is available.
     */
    private fun rulesOnly(decision: Decision.NeedsAi): Verdict =
        Verdict.keep(Importance.NORMAL, VerdictSource.RULE, "${decision.reason}$RULES_ONLY_MARK", 0f)

    /** Mail that failed authentication never alerts, whoever it claims to be from. */
    private fun noAlertIfUnauthenticated(
        decision: Decision,
        verdict: Verdict,
    ): Verdict =
        if (verdict.importance.notifies && decision.signals.auth.anyFail) {
            verdict.copy(importance = Importance.NORMAL, reason = "${verdict.reason} (no alert: failed authentication)")
        } else {
            verdict
        }

    private fun alertFor(
        triage: AccountTriage,
        c: Classified,
    ): TriageAlert? {
        val row = c.row
        val fresh = row.receivedAtMillis >= maxOf(triage.createdAtMillis, clock() - ALERT_WINDOW_MILLIS)
        val alert =
            triage.settings.notifications && c.verdict.importance.notifies &&
                c.verdict.importance in triage.settings.alertLevels && fresh &&
                row.folderPath == MailRepository.INBOX && MessageFlag.SEEN !in row.flags.toFlags()
        if (!alert) return null
        val from = runCatching { row.toHeader().from }.getOrNull()
        return TriageAlert(triage.account.id, row.id, from, row.subject, c.verdict.importance, c.verdict.reason)
    }

    /** Moves stored as pending (the server was unreachable, a batch failed) are tried again. */
    private suspend fun retryPendingMoves(): TriageReport {
        var outcome = MoveOutcome()
        for (accountId in db.triage().accountsWithPendingMoves()) {
            val triage = factory.prepare(accountId) ?: continue
            val pending = db.triage().pendingMoves(accountId, MailRepository.INBOX, BATCH)
            val (allowed, notAllowed) =
                pending.partition { p -> p.verdict?.let { triage.settings.autoFile.allows(it.toModel()) } == true }
            // The user turned auto-file off since: forget the move rather than surprise them later.
            notAllowed.forEach { db.triage().setPendingMove(it.message.id, false) }
            val moves =
                allowed.mapNotNull { p ->
                    p.verdict?.toModel()?.let { PlannedMove(p.message, it.target, it.action) }
                }
            outcome += mover.apply(triage.account, moves)
        }
        return TriageReport(moves = outcome)
    }

    companion object {
        /** Ends the reason of a rules-only fallback verdict; [reclassifyRulesOnly] finds them by it. */
        const val RULES_ONLY_MARK = "; left in Priority (AI unavailable)"

        private const val BATCH = 50
        private val ALERT_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(1)
        private const val UNREADABLE = "Could not be triaged; left in Priority"
    }
}
