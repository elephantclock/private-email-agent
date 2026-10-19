package tech.elephantclock.privatemail

import android.content.Context
import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.data.triage.ArchiveOutcome
import tech.elephantclock.privatemail.core.data.triage.InboxArchiver
import tech.elephantclock.privatemail.core.data.triage.TriageNotifier
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.notify.AndroidTriageNotifier
import tech.elephantclock.privatemail.notify.NotificationArchiver
import tech.elephantclock.privatemail.notify.SuppressedAlerts

// Notifications' wiring (T49), kept out of MailGraph so lanes don't collide. The coordinator
// passes triageNotifier() to the orchestrator and shows suppressedAlertCount() in the inbox.

/**
 * Archive from a notification: one message out of the Inbox into the account's existing
 * special-use Archive folder (Gmail: the `\Inbox` label is removed), creating nothing, with an
 * undo entry. Its own connection factory over the graph's password store and pins (the graph's
 * connector is private), sharing the graph's repository and so its folder locks.
 */
fun MailGraph.notificationArchiver(): NotificationArchiver? {
    // Built on the first tap: offering the action must not open the database or the Keystore.
    val archiver by lazy {
        InboxArchiver(database, repository, PasswordImapConnector(secrets, ImapClient(certificatePins)))
    }
    return NotificationArchiver {
        accountId,
        localId,
        ->
        archiver.archive(accountId, localId) is ArchiveOutcome.Archived
    }
}

fun MailGraph.triageNotifier(context: Context): TriageNotifier =
    AndroidTriageNotifier(context, archiveAvailable = notificationArchiver() != null)

/** Unread mail whose alert was held back because it failed sender authentication: the inbox badge. */
fun MailGraph.suppressedAlertCount(): Flow<Int> = SuppressedAlerts(database).count()
