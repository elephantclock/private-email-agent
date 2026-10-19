package tech.elephantclock.privatemail.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import tech.elephantclock.privatemail.PrivateMailApp
import tech.elephantclock.privatemail.core.data.triage.TriageAlert
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.notificationArchiver

/**
 * The notification's Archive button: removes the notification and hands the move to
 * [ArchiveWorker] (a receiver may run for seconds only; a server move can take longer and
 * must survive the process). Not exported; reached only through its immutable PendingIntent.
 */
class ArchiveReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return
        val uri = intent.data ?: return
        val localId = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val account = uri.getQueryParameter("account")?.takeIf { it.isNotBlank() } ?: return
        NotificationManagerCompat
            .from(context)
            .cancel(AndroidTriageNotifier.tag(AccountId(account)), AndroidTriageNotifier.id(localId))
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "archive:$account:$localId",
                ExistingWorkPolicy.KEEP,
                ArchiveWorker.request(account, localId),
            )
    }

    companion object {
        const val ACTION = "tech.elephantclock.privatemail.action.ARCHIVE"

        fun pendingIntent(
            context: Context,
            alert: TriageAlert,
        ): PendingIntent {
            val uri =
                Uri
                    .Builder()
                    .scheme("privatemail")
                    .authority("archive")
                    .appendPath(alert.localId.toString())
                    .appendQueryParameter("account", alert.accountId.value)
                    .build()
            val intent = Intent(context, ArchiveReceiver::class.java).setAction(ACTION).setData(uri)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

/** Runs one archive. Never retried on its own: a failed move leaves the mail in the Inbox, where the user sees it. */
class ArchiveWorker(
    context: Context,
    params: WorkerParameters,
    private val archiver: NotificationArchiver?,
) : CoroutineWorker(context, params) {
    /** For WorkManager's default factory: the archiver comes from the app's graph. */
    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        (context.applicationContext as? PrivateMailApp)?.graph?.notificationArchiver(),
    )

    override suspend fun doWork(): Result {
        val archiver = archiver ?: return Result.failure()
        val account = inputData.getString(KEY_ACCOUNT) ?: return Result.failure()
        val localId = inputData.getLong(KEY_LOCAL_ID, 0).takeIf { it > 0 } ?: return Result.failure()
        return try {
            if (archiver.archive(AccountId(account), localId)) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_ACCOUNT = "account"
        const val KEY_LOCAL_ID = "localId"

        fun request(
            account: String,
            localId: Long,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ArchiveWorker>()
                .setInputData(workDataOf(KEY_ACCOUNT to account, KEY_LOCAL_ID to localId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
    }
}
