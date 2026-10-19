package tech.elephantclock.privatemail.core.data.onboarding

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.toBody
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.db.toHeader
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.repo.AccountSyncResult
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.OnboardingStep
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.data.triage.MoveOutcome
import tech.elephantclock.privatemail.core.data.triage.PlannedMove
import tech.elephantclock.privatemail.core.data.triage.RelationshipRepository
import tech.elephantclock.privatemail.core.data.triage.TriageMover
import tech.elephantclock.privatemail.core.data.triage.TriageOrchestrator
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.people.Protections

/** Where the first run is, for the worker's notification and screen 03. */
sealed interface FirstRunProgress {
    data object Syncing : FirstRunProgress

    data class ReadingSent(
        val done: Int,
        val total: Int,
    ) : FirstRunProgress

    data class Sorting(
        val done: Int,
        val total: Int,
    ) : FirstRunProgress

    data class Applying(
        val done: Int,
        val total: Int,
    ) : FirstRunProgress
}

/** How a step ended. [Retry]: a network or server problem; running the step again resumes. */
sealed interface FirstRunResult {
    data object Done : FirstRunResult

    data class Retry(
        val reason: String,
    ) : FirstRunResult

    /** The user must act first (wrong password, account gone): retrying won't help. */
    data class Blocked(
        val reason: String,
    ) : FirstRunResult

    /** Apply refused: the Sent history isn't complete, so nothing may move yet. */
    data class NotReady(
        val status: BackfillStatus,
    ) : FirstRunResult

    data class Applied(
        val outcome: MoveOutcome,
        val skippedProtected: Int,
    ) : FirstRunResult
}

/** The worker's view of [FirstRun], so the worker can be tested with a fake. */
interface FirstRunSteps {
    suspend fun prepare(
        id: AccountId,
        progress: suspend (FirstRunProgress) -> Unit = {},
    ): FirstRunResult

    suspend fun sort(
        id: AccountId,
        progress: suspend (FirstRunProgress) -> Unit = {},
    ): FirstRunResult

    suspend fun apply(
        id: AccountId,
        progress: suspend (FirstRunProgress) -> Unit = {},
    ): FirstRunResult
}

/**
 * Screen 03's engine (T43). **Nothing moves on the server before [apply]**:
 * - [prepare] syncs the account (30 days) and backfills 12 months of Sent headers; started as
 *   soon as screen 02 opens, so the people list fills from Sent.
 * - [sort] runs [prepare] (cheap when done), then forgets the machine's earlier Inbox verdicts
 *   (made before the backfill knew everyone the user wrote to) and triages again with
 *   [labelOnly], an orchestrator whose settings never allow a move.
 * - [apply] refuses unless the backfill is complete, re-checks every discard and junk against
 *   the protections built from the full Sent history, moves through [TriageMover], and only
 *   then records that the user wants auto-file. [labelOnlyDone] moves nothing.
 */
class FirstRun(
    private val db: AppDatabase,
    private val repo: MailRepository,
    private val mover: TriageMover,
    private val labelOnly: TriageOrchestrator,
    private val backfill: SentHistoryBackfill,
    private val store: TriageSettingsStore,
    private val relationships: RelationshipRepository = RelationshipRepository(db),
    private val clock: () -> Long = System::currentTimeMillis,
) : FirstRunSteps {
    override suspend fun prepare(
        id: AccountId,
        progress: suspend (FirstRunProgress) -> Unit,
    ): FirstRunResult {
        progress(FirstRunProgress.Syncing)
        when (val sync = repo.syncAccount(id)) {
            is AccountSyncResult.SignInFailed -> {
                val reason = "sign-in failed: ${sync.error.javaClass.simpleName}"
                return if (sync.error.needsUserAction) FirstRunResult.Blocked(reason) else FirstRunResult.Retry(reason)
            }

            is AccountSyncResult.Skipped -> {
                return FirstRunResult.Blocked(sync.reason)
            }

            is AccountSyncResult.Failed -> {
                return FirstRunResult.Retry("sync failed: ${sync.error.javaClass.simpleName}")
            }

            is AccountSyncResult.Synced -> {
                Unit
            }
        }
        progress(FirstRunProgress.ReadingSent(0, 0))
        return when (val r = backfill.run(id) { done, total -> progress(FirstRunProgress.ReadingSent(done, total)) }) {
            BackfillResult.Complete, BackfillResult.NoSentFolder -> FirstRunResult.Done
            is BackfillResult.Interrupted -> FirstRunResult.Retry(r.reason)
        }
    }

    override suspend fun sort(
        id: AccountId,
        progress: suspend (FirstRunProgress) -> Unit,
    ): FirstRunResult {
        val prepared = prepare(id, progress)
        if (prepared != FirstRunResult.Done) return prepared
        if (store.get(id).step == OnboardingStep.DONE) return FirstRunResult.Done
        val dao = db.firstRun()
        dao.clearMachineVerdicts(id.value, MailRepository.INBOX)
        val total = dao.untriagedCount(id.value, MailRepository.INBOX)
        progress(FirstRunProgress.Sorting(0, total))
        labelOnly.drain()
        progress(FirstRunProgress.Sorting(total - dao.untriagedCount(id.value, MailRepository.INBOX), total))
        return FirstRunResult.Done
    }

    suspend fun review(id: AccountId): FirstRunReview {
        val dao = db.firstRun()
        return FirstRunReview.of(
            dao.verdictCounts(id.value, MailRepository.INBOX),
            store.get(id),
            dao.untriagedCount(id.value, MailRepository.INBOX),
        )
    }

    suspend fun items(
        id: AccountId,
        group: ReviewGroup,
        limit: Int = 200,
    ): List<ReviewItem> =
        db
            .firstRun()
            .reviewRows(id.value, MailRepository.INBOX, group.action, group.target.role, limit)
            .map(ReviewItem::of)

    /** The user's correction in a drill-down: this one stays in the Inbox, whatever triage thought. */
    suspend fun keep(messageId: Long) {
        db.verdicts().upsert(KEPT_BY_USER.toEntity(messageId, clock()))
    }

    override suspend fun apply(
        id: AccountId,
        progress: suspend (FirstRunProgress) -> Unit,
    ): FirstRunResult {
        val status = backfill.status(id)
        if (status != BackfillStatus.COMPLETE) return FirstRunResult.NotReady(status)
        val entity = db.accounts().get(id.value) ?: return FirstRunResult.Blocked("unknown account")
        val account = entity.toModel()
        val prefs = store.get(id)
        val index = relationships.index(id, prefs.ownAddresses + account.email.email, prefs.vips)
        val protections = Protections(index)
        var skipped = 0
        val moves =
            db.firstRun().movable(id.value, MailRepository.INBOX).mapNotNull { mv ->
                val verdict = mv.verdict?.toModel() ?: return@mapNotNull null
                if (verdict.action == VerdictAction.FILE && !prefs.folderEnabled(verdict.target.role)) {
                    return@mapNotNull null
                }
                if (verdict.action == VerdictAction.DISCARD || verdict.action == VerdictAction.JUNK) {
                    val protection = protectionOf(protections, mv.message)
                    if (protection == null || protection.forbids(verdict.action)) {
                        skipped++
                        val kept = protection?.enforce(verdict) ?: KEPT_UNREADABLE
                        db.verdicts().upsert(kept.toEntity(mv.message.id, clock()))
                        return@mapNotNull null
                    }
                }
                PlannedMove(mv.message, verdict.target, verdict.action)
            }
        // Pending first: whatever doesn't go through now is retried by triage once auto-file is on.
        db.withTransaction { moves.forEach { db.triage().setPendingMove(it.message.id, true) } }
        var outcome = MoveOutcome()
        progress(FirstRunProgress.Applying(0, moves.size))
        for (chunk in moves.chunked(APPLY_CHUNK)) {
            outcome += mover.apply(account, chunk)
            progress(FirstRunProgress.Applying(outcome.moved + outcome.missing + outcome.failed, moves.size))
        }
        store.update(id) { it.copy(autoFileRequested = true, step = OnboardingStep.DONE) }
        return FirstRunResult.Applied(outcome, skipped)
    }

    /** "Label only, move nothing": verdicts stay as labels, auto-file stays off. */
    suspend fun labelOnlyDone(id: AccountId) {
        store.update(id) { it.copy(autoFileRequested = false, step = OnboardingStep.DONE) }
    }

    private fun protectionOf(
        protections: Protections,
        row: MessageEntity,
    ) = try {
        protections.evaluate(MailItem(row.toHeader(), row.toBody()))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null // unreadable row: never thrown away
    }

    private companion object {
        const val APPLY_CHUNK = 200
        val KEPT_BY_USER = Verdict.keep(Importance.NORMAL, VerdictSource.USER, "You chose to keep it")
        val KEPT_UNREADABLE =
            Verdict.keep(
                Importance.NORMAL,
                VerdictSource.RULE,
                "Could not be checked; left in Priority",
            )
    }
}
