package tech.elephantclock.privatemail.core.data.triage

import androidx.room.withTransaction
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.rules.Correction
import tech.elephantclock.privatemail.core.triage.rules.LearnedRules

/** What a restore did. [failed] messages are still discarded (server unreachable, move refused). */
data class RestoreReport(
    val restored: Int = 0,
    val failed: Int = 0,
    val errors: List<Throwable> = emptyList(),
)

/**
 * The Discarded digest's corrections (T47).
 *
 * - **Restore** puts discarded or junked messages back in the Inbox and replaces their verdict
 *   with the user's KEEP, so triage never moves them again. A message that was only labelled
 *   (auto-file off) is still in the Inbox: only its verdict changes. A moved one goes back with
 *   a single move per folder through [TriageMover] (not `undo`, which would restore a whole
 *   triage batch); its verdict changes only once the move is confirmed, so a failed move never
 *   leaves mail in Discarded with a KEEP verdict that hides it from both lists.
 * - **Never discard** stores a learned KEEP rule for the sender ([LearnedRules.fromCorrection],
 *   sender scope, this account only), then restores the given messages.
 *
 * Known gap: when the server reports no new UID for a restored message, [TriageMover] drops
 * its row; the Inbox sync stores it again without a verdict and triage sees it anew (with
 * Never discard, the rule then keeps it).
 */
class DiscardedActions(
    private val db: AppDatabase,
    private val mover: TriageMover,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun restore(ids: Collection<Long>): RestoreReport {
        val rows =
            ids.distinct().mapNotNull { id ->
                val row = db.messages().get(id) ?: return@mapNotNull null
                val verdict = db.verdicts().get(id) ?: return@mapNotNull null
                row.takeIf { verdict.action == VerdictAction.DISCARD || verdict.action == VerdictAction.JUNK }
            }
        val (inInbox, moved) = rows.partition { it.folderPath == MailRepository.INBOX }
        keep(inInbox.map { it.id })
        var report = RestoreReport(restored = inInbox.size)
        for ((accountId, group) in moved.groupBy { it.accountId }) {
            report = report.plus(moveBack(accountId, group))
        }
        return report
    }

    /** Stores "never discard [sender]" for [accountId] and restores [ids]. Returns the rule and the restore. */
    suspend fun neverDiscard(
        accountId: AccountId,
        sender: Address,
        ids: Collection<Long>,
    ): Pair<TriageRule, RestoreReport> {
        val correction = Correction(accountId, sender, FolderTarget.Priority, Correction.Scope.SENDER)
        val rule = LearnedRules.fromCorrection(correction, id = 0, nowMillis = clock())
        val id = db.learnedRules().put(rule.toEntity())
        return rule.copy(id = id) to restore(ids)
    }

    private suspend fun moveBack(
        accountId: String,
        group: List<MessageEntity>,
    ): RestoreReport {
        val account =
            db.accounts().get(accountId)?.toModel()
                ?: return RestoreReport(failed = group.size)
        val outcome =
            mover.apply(account, group.map { PlannedMove(it, FolderTarget.Priority, VerdictAction.KEEP) })
        // The mover moves the rows it confirmed to the Inbox; only those become the user's KEEP.
        val back = group.mapNotNull { db.messages().get(it.id) }.filter { it.folderPath == MailRepository.INBOX }
        keep(back.map { it.id })
        return RestoreReport(restored = back.size, failed = group.size - back.size, errors = outcome.errors)
    }

    private suspend fun keep(ids: List<Long>) {
        if (ids.isEmpty()) return
        val now = clock()
        db.withTransaction { db.verdicts().upsertAll(ids.map { RESTORED.toEntity(it, now) }) }
    }

    private operator fun RestoreReport.plus(o: RestoreReport) =
        RestoreReport(restored + o.restored, failed + o.failed, errors + o.errors)

    companion object {
        /** Reason shown for restored mail; stable (tests and the inbox may look for it). */
        const val RESTORED_REASON = "You restored it from Discarded"
        private val RESTORED = Verdict.keep(Importance.NORMAL, VerdictSource.USER, RESTORED_REASON)
    }
}
