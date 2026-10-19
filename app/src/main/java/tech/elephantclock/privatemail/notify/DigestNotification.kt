package tech.elephantclock.privatemail.notify

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.work.DigestSummary

/** The 18:00 digest notification: counts only (no senders or subjects), opens the Discarded tab. */
object DigestNotification {
    private const val TAG = "digest"
    private const val ID = 1

    fun post(
        context: Context,
        summary: DigestSummary,
    ) {
        if (!AndroidTriageNotifier.canPost(context)) return
        Channels.ensure(context)
        val text = text(context, summary)
        val notification =
            NotificationCompat
                .Builder(context, Channels.DIGEST)
                .setSmallIcon(R.drawable.ic_stat_mail)
                .setContentTitle(context.getString(R.string.notify_digest_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(NotificationLinks.pendingIntent(context, NotificationLink.Discarded))
                .setAutoCancel(true)
                .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, ID, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post.
        }
    }

    fun text(
        context: Context,
        s: DigestSummary,
    ): String {
        val r = context.resources
        val parts =
            buildList {
                add(r.getQuantityString(R.plurals.notify_digest_filed, s.filed, s.filed))
                add(r.getQuantityString(R.plurals.notify_digest_discarded, s.discarded, s.discarded))
                if (s.junk > 0) add(r.getQuantityString(R.plurals.notify_digest_junk, s.junk, s.junk))
            }
        val lines =
            buildList {
                add(parts.joinToString(", "))
                if (s.needsYou > 0) add(r.getQuantityString(R.plurals.notify_digest_needs_you, s.needsYou, s.needsYou))
                if (s.unverified >
                    0
                ) {
                    add(r.getQuantityString(R.plurals.notify_digest_unverified, s.unverified, s.unverified))
                }
            }
        return lines.joinToString("\n")
    }
}
