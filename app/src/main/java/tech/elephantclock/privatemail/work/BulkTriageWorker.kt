package tech.elephantclock.privatemail.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/**
 * The backlog that needs the model (first run, re-sorting, messages left in rules-only mode).
 * The coordinator implements it over the triage orchestrator, submitting to the `AiQueue` with
 * `AiPriority.BULK`, so a foreground request always preempts this work.
 */
fun interface BulkTriageSource {
    /** Triages up to [limit] messages; returns how many it handled, 0 when nothing is left. */
    suspend fun triageBatch(limit: Int): Int
}

/**
 * Drains [BulkTriageSource] in batches, only while the phone is charging or idle (two requests,
 * because WorkManager constraints are AND-only; a run that finds the other one busy or nothing
 * left ends at once). Stopping (charger out, screen on) cancels the coroutine, which drops this
 * work's queued model calls; results already cached per Message-ID aren't repeated next time.
 */
class BulkTriageWorker(
    context: Context,
    params: WorkerParameters,
    private val source: BulkTriageSource,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!running.tryLock()) return Result.success() // the other trigger is already draining
        return try {
            var handled = source.triageBatch(BATCH_SIZE)
            while (handled > 0 && !isStopped) handled = source.triageBatch(BATCH_SIZE)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } finally {
            running.unlock()
        }
    }

    companion object {
        const val UNIQUE_CHARGING = "ai-bulk-triage-charging"
        const val UNIQUE_IDLE = "ai-bulk-triage-idle"
        const val BATCH_SIZE = 20
        const val MAX_ATTEMPTS = 3

        /** One drain per process at a time, whichever trigger started it. */
        private val running = Mutex()

        fun chargingRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BulkTriageWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

        /** No backoff: WorkManager rejects backoff criteria on idle-mode work. */
        fun idleRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BulkTriageWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).build()

        /**
         * Asks for a drain when conditions allow; call after first sync, a re-sort or leaving
         * rules-only mode. Idempotent: pending requests keep their place.
         */
        fun schedule(workManager: WorkManager) {
            workManager.enqueueUniqueWork(UNIQUE_CHARGING, ExistingWorkPolicy.KEEP, chargingRequest())
            workManager.enqueueUniqueWork(UNIQUE_IDLE, ExistingWorkPolicy.KEEP, idleRequest())
        }

        fun cancel(workManager: WorkManager) {
            workManager.cancelUniqueWork(UNIQUE_CHARGING)
            workManager.cancelUniqueWork(UNIQUE_IDLE)
        }
    }
}

/** Builds [BulkTriageWorker] with its source; combine with `SyncWorkerFactory` in a `DelegatingWorkerFactory`. */
class BulkTriageWorkerFactory(
    private val source: () -> BulkTriageSource,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == BulkTriageWorker::class.java.name) {
            BulkTriageWorker(appContext, workerParameters, source())
        } else {
            null
        }
}
