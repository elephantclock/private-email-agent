package tech.elephantclock.privatemail.notify

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.triage.TriageAlert
import tech.elephantclock.privatemail.core.data.triage.TriageNotifier
import tech.elephantclock.privatemail.core.model.AccountId

/**
 * Moves one message out of the Inbox to the account's special-use Archive folder (creating
 * nothing; Gmail: drop `\Inbox`); see `MailGraph.notificationArchiver`. The Archive action is
 * shown only when one is given.
 */
fun interface NotificationArchiver {
    /** True when the message left the Inbox. */
    suspend fun archive(
        accountId: AccountId,
        localId: Long,
    ): Boolean
}

/**
 * Posts triage alerts (T49): URGENT on the sounding channel, HIGH on the silent one, with
 * Open, Draft reply and (when [archiver] is wired) Archive. Lock screens show only "New
 * important email" (visibility private). Without the notification permission nothing is
 * posted; the in-app lists still show the mail. Alerts for mail that failed authentication
 * never reach this class (triage lowers them); the inbox shows those as a badge instead
 * ([SuppressedAlerts]).
 */
class AndroidTriageNotifier(
    context: Context,
    /** Whether [accountId] can archive (an existing Archive folder, or Gmail); Archive is offered only then. */
    private val canArchive: (AccountId) -> Boolean = { false },
) : TriageNotifier {
    private val context = context.applicationContext

    override fun notify(alert: TriageAlert) {
        val channel = Channels.forImportance(alert.importance) ?: return
        if (!canPost(context)) return
        Channels.ensure(context)
        val notification = build(alert, channel)
        try {
            NotificationManagerCompat.from(context).notify(tag(alert.accountId), id(alert.localId), notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post.
        }
    }

    internal fun build(
        alert: TriageAlert,
        channel: String,
    ): Notification {
        val sender = alert.from?.let { it.name ?: it.email } ?: context.getString(R.string.notify_unknown_sender)
        val subject = alert.subject.ifBlank { context.getString(R.string.notify_no_subject) }
        val open = NotificationLinks.pendingIntent(context, NotificationLink.Open(alert.accountId, alert.localId))
        val reply =
            NotificationLinks.pendingIntent(
                context,
                NotificationLink.DraftReply(alert.accountId, alert.localId),
            )
        val public =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle(context.getString(R.string.notify_public_title))
                .build()
        return NotificationCompat
            .Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(sender)
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$subject\n${alert.reason}"))
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(
                if (channel ==
                    Channels.URGENT
                ) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            ).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.notify_action_open), open)
            .addAction(0, context.getString(R.string.notify_action_reply), reply)
            .apply {
                if (canArchive(alert.accountId)) {
                    addAction(
                        0,
                        context.getString(R.string.notify_action_archive),
                        ArchiveReceiver.pendingIntent(context, alert),
                    )
                }
            }.build()
    }

    companion object {
        /** One notification per message: the tag is the account, the id comes from the local id. */
        fun tag(accountId: AccountId) = "alert:${accountId.value}"

        fun id(localId: Long): Int = (localId xor (localId ushr 32)).toInt()

        /** Notifications are on for the app and, from Android 13, the permission is granted. */
        fun canPost(context: Context): Boolean {
            val granted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
