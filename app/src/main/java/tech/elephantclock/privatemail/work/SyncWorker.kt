package tech.elephantclock.privatemail.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import java.util.concurrent.TimeUnit

/**
 * The periodic background sync: every account, every synced folder, read-only. Runs at most
 * every 15 minutes and only with a network (the plan's budget: one wake per 15 minutes).
 *
 * Results: a transient failure (network, dropped connection) asks WorkManager to retry with
 * backoff, at most [MAX_ATTEMPTS] times per period; anything that needs the user (wrong
 * password, untrusted certificate) is recorded on the account by the repository and is
 * *not* retried, so the server never sees a stream of failed sign-ins.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: MailRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = repository.syncAll()
        return if (report.shouldRetry && runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "mail-sync"
        const val MAX_ATTEMPTS = 3

        fun request(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(
                PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

        /** Idempotent: an already scheduled sync keeps its schedule. */
        fun schedule(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request())
        }
    }
}

/** Builds [SyncWorker] with its repository (no DI framework yet); other workers use the default. */
class SyncWorkerFactory(
    private val repository: () -> MailRepository,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) {
            SyncWorker(appContext, workerParameters, repository())
        } else {
            null
        }
}
