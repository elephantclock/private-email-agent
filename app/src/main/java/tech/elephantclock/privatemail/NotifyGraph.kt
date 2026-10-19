package tech.elephantclock.privatemail

import android.content.Context
import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.data.triage.TriageNotifier
import tech.elephantclock.privatemail.notify.AndroidTriageNotifier
import tech.elephantclock.privatemail.notify.NotificationArchiver
import tech.elephantclock.privatemail.notify.SuppressedAlerts

// Notifications' wiring (T49), kept out of MailGraph so lanes don't collide. The coordinator
// passes triageNotifier() to the orchestrator and shows suppressedAlertCount() in the inbox.

/**
 * Archive from a notification needs a `core:mail` move to the existing special-use Archive
 * folder (creating nothing); until that exists there is none and the action is not shown.
 */
fun MailGraph.notificationArchiver(): NotificationArchiver? = null

fun MailGraph.triageNotifier(context: Context): TriageNotifier =
    AndroidTriageNotifier(context, archiveAvailable = notificationArchiver() != null)

/** Unread mail whose alert was held back because it failed sender authentication: the inbox badge. */
fun MailGraph.suppressedAlertCount(): Flow<Int> = SuppressedAlerts(database).count()
