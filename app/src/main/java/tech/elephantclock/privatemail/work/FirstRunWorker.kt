package tech.elephantclock.privatemail.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import tech.elephantclock.privatemail.PrivateMailApp
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunProgress
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunResult
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunSteps
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Stage
import tech.elephantclock.privatemail.onboarding

/**
 * The first run (T43) as a foreground worker with progress, so it survives leaving the app:
 * - [Mode.PREPARE]: sync the last 30 days and back-fill 12 months of Sent headers;
 * - [Mode.SORT]: the same (resumes), then triage label-only: **nothing moves**;
 * - [Mode.APPLY]: the moves, then auto-file on (refused until the backfill is complete).
 *
 * Built by WorkManager's default factory through the (Context, WorkerParameters) constructor,
 * which takes the engine from the app's graph; tests pass their own.
 */
class FirstRunWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: () -> FirstRunSteps,
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        { (context.applicationContext as PrivateMailApp).graph.onboarding(context).firstRun },
    )

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ACCOUNT)?.takeIf { it.isNotBlank() }?.let(::AccountId)
        val mode = inputData.getString(KEY_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
        if (id == null || mode == null) return Result.failure()
        val notification = FirstRunNotification(applicationContext)
        goForeground(notification, mode, FirstRunProgress.Syncing)
        val progress: suspend (FirstRunProgress) -> Unit = { p ->
            setProgress(p.toData())
            goForeground(notification, mode, p)
        }
        val firstRun = engine()
        val result =
            when (mode) {
                Mode.PREPARE -> firstRun.prepare(id, progress)
                Mode.SORT -> firstRun.sort(id, progress)
                Mode.APPLY -> firstRun.apply(id, progress)
            }
        return when (result) {
            FirstRunResult.Done, is FirstRunResult.Applied -> {
                Result.success()
            }

            is FirstRunResult.Retry -> {
                if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }

            is FirstRunResult.Blocked -> {
                Result.failure(workDataOf(KEY_BLOCKED to true))
            }

            is FirstRunResult.NotReady -> {
                Result.failure(workDataOf(KEY_NOT_READY to result.status.name))
            }
        }
    }

    /** A refused foreground start (the app went to the background on Android 12+) just runs on as normal work. */
    private suspend fun goForeground(
        notification: FirstRunNotification,
        mode: Mode,
        progress: FirstRunProgress,
    ) {
        try {
            setForeground(notification.info(this.id.hashCode(), mode, progress))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Log.w(TAG, "no foreground: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_MODE = "mode"
        const val KEY_STAGE = "stage"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_BLOCKED = "blocked"
        const val KEY_NOT_READY = "notReady"
        const val MAX_ATTEMPTS = 5
        private const val TAG = "FirstRunWorker"

        fun input(
            id: AccountId,
            mode: Mode,
        ): Data = workDataOf(KEY_ACCOUNT to id.value, KEY_MODE to mode.name)

        fun FirstRunProgress.toData(): Data =
            when (this) {
                FirstRunProgress.Syncing -> workDataOf(KEY_STAGE to Stage.SYNC.name)
                is FirstRunProgress.ReadingSent -> progressData(Stage.SENT, done, total)
                is FirstRunProgress.Sorting -> progressData(Stage.SORT, done, total)
                is FirstRunProgress.Applying -> progressData(Stage.APPLY, done, total)
            }

        private fun progressData(
            stage: Stage,
            done: Int,
            total: Int,
        ) = workDataOf(KEY_STAGE to stage.name, KEY_DONE to done, KEY_TOTAL to total)
    }
}
