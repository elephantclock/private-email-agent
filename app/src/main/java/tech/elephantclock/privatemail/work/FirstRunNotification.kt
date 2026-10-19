package tech.elephantclock.privatemail.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunProgress
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode

/**
 * The quiet, ongoing notification of the first-run worker: its own low-importance channel
 * (the alert channels are T49's). Without the notification permission Android hides it, and
 * the work still runs.
 */
class FirstRunNotification(
    private val context: Context,
) {
    fun info(
        notificationId: Int,
        mode: Mode,
        progress: FirstRunProgress,
    ): ForegroundInfo {
        ensureChannel()
        val (text, done, total) =
            when (progress) {
                FirstRunProgress.Syncing -> {
                    Triple(context.getString(R.string.first_run_stage_sync), 0, 0)
                }

                is FirstRunProgress.ReadingSent -> {
                    Triple(
                        context.getString(R.string.first_run_stage_sent),
                        progress.done,
                        progress.total,
                    )
                }

                is FirstRunProgress.Sorting -> {
                    Triple(
                        context.getString(R.string.first_run_stage_sort),
                        progress.done,
                        progress.total,
                    )
                }

                is FirstRunProgress.Applying -> {
                    Triple(
                        context.getString(R.string.first_run_stage_apply),
                        progress.done,
                        progress.total,
                    )
                }
            }
        val titleRes =
            if (mode ==
                Mode.APPLY
            ) {
                R.string.first_run_notification_apply
            } else {
                R.string.first_run_notification_title
            }
        val title = context.getString(titleRes)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setProgress(total, done.coerceAtMost(total), total == 0)
                .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.first_run_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID = "first_run"
    }
}
