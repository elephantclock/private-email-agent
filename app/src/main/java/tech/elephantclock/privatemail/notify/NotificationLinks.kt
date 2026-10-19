package tech.elephantclock.privatemail.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import tech.elephantclock.privatemail.MainActivity
import tech.elephantclock.privatemail.core.model.AccountId

/**
 * Where a tap on a notification leads. `MainActivity` reads it with [NotificationLinks.parse]
 * (in `onCreate` and `onNewIntent`) and navigates; nothing is acted on without the user
 * (Draft reply only opens the reply screen, T46).
 */
sealed interface NotificationLink {
    data class Open(
        val accountId: AccountId,
        val localId: Long,
    ) : NotificationLink

    data class DraftReply(
        val accountId: AccountId,
        val localId: Long,
    ) : NotificationLink

    /** The daily digest: the Discarded tab. */
    data object Discarded : NotificationLink
}

/**
 * Builds and reads the notification intents. They are explicit (component = [MainActivity]),
 * so no intent filter is needed and nothing new is exported; each carries a distinct data URI
 * per message and action, because `PendingIntent` equality ignores extras (otherwise every
 * Open would reuse the first one). PendingIntents are immutable.
 */
object NotificationLinks {
    private const val PREFIX = "tech.elephantclock.privatemail.action."
    const val ACTION_OPEN = PREFIX + "OPEN_MESSAGE"
    const val ACTION_DRAFT_REPLY = PREFIX + "DRAFT_REPLY"
    const val ACTION_DISCARDED = PREFIX + "OPEN_DISCARDED"
    private const val SCHEME = "privatemail"
    private const val ACCOUNT = "account"

    fun intent(
        context: Context,
        link: NotificationLink,
    ): Intent {
        val (action, uri) =
            when (link) {
                is NotificationLink.Open -> {
                    ACTION_OPEN to messageUri("open", link.accountId, link.localId)
                }

                is NotificationLink.DraftReply -> {
                    ACTION_DRAFT_REPLY to
                        messageUri("reply", link.accountId, link.localId)
                }

                NotificationLink.Discarded -> {
                    ACTION_DISCARDED to "$SCHEME://discarded".toUri()
                }
            }
        return Intent(context, MainActivity::class.java)
            .setAction(action)
            .setData(uri)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
    }

    fun pendingIntent(
        context: Context,
        link: NotificationLink,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent(context, link),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The link an intent carries, or null for anything else (a launcher start, a malformed URI). */
    fun parse(intent: Intent?): NotificationLink? {
        val uri = intent?.data ?: return null
        if (uri.scheme != SCHEME) return null
        if (intent.action == ACTION_DISCARDED) return NotificationLink.Discarded.takeIf { uri.host == "discarded" }
        val localId = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val account = uri.getQueryParameter(ACCOUNT)?.takeIf { it.isNotBlank() }?.let(::AccountId) ?: return null
        return when {
            intent.action == ACTION_OPEN && uri.host == "open" -> NotificationLink.Open(account, localId)
            intent.action == ACTION_DRAFT_REPLY && uri.host == "reply" -> NotificationLink.DraftReply(account, localId)
            else -> null
        }
    }

    private fun messageUri(
        host: String,
        accountId: AccountId,
        localId: Long,
    ): Uri =
        Uri
            .Builder()
            .scheme(SCHEME)
            .authority(host)
            .appendPath(localId.toString())
            .appendQueryParameter(ACCOUNT, accountId.value)
            .build()
}
