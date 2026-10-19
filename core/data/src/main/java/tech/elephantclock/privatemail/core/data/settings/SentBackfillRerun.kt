package tech.elephantclock.privatemail.core.data.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.onboarding.BackfillResult
import tech.elephantclock.privatemail.core.data.onboarding.SentHistoryBackfill
import tech.elephantclock.privatemail.core.model.AccountId

/**
 * Re-opens the auto-file gate after it closed on its own (T48's carried-over item): a Sent
 * folder's UIDVALIDITY reset (the sync drops the stored history) or a new Sent folder makes
 * [SentBackfillGate] report PENDING, and auto-file falls back to label only. Call [run] after
 * every background sync (`SyncWorker.afterSync`): it backfills again, resumably, for each
 * account that
 * - asked for auto-file (only set after the first run's Apply, so this never races the
 *   first-run worker's own backfill of that account),
 * - is PENDING (COMPLETE has nothing to do; NO_SENT_FOLDER can't be helped), and
 * - doesn't wait for the user (wrong password...): no sign-in is tried then.
 */
class SentBackfillRerun(
    private val db: AppDatabase,
    private val store: TriageSettingsStore,
    private val gate: SentBackfillGate,
    private val backfill: SentHistoryBackfill,
) {
    /** The accounts it ran for and how each ended. Never throws for one account's failure. */
    suspend fun run(): Map<AccountId, BackfillResult> {
        val results = LinkedHashMap<AccountId, BackfillResult>()
        for (account in db.accounts().all().first()) {
            val id = AccountId(account.id)
            if (account.authProblem != null) continue
            val prefs = store.get(id)
            if (!prefs.autoFileRequested || gate.status(id, prefs) != BackfillStatus.PENDING) continue
            results[id] =
                try {
                    backfill.run(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BackfillResult.Interrupted("backfill failed: ${e.javaClass.simpleName}")
                }
        }
        return results
    }
}
