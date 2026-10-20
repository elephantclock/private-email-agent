package tech.elephantclock.privatemail

import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.elephantclock.privatemail.feature.inbox.SuppressedItem
import tech.elephantclock.privatemail.feature.inbox.TimeLabels
import tech.elephantclock.privatemail.feature.shell.FlexReadingDetail
import tech.elephantclock.privatemail.feature.shell.PlaceholderDetail
import tech.elephantclock.privatemail.feature.shell.ShellDestination
import tech.elephantclock.privatemail.feature.shell.ShellDetailPane
import tech.elephantclock.privatemail.notify.SuppressedAlerts

/*
 * T4A wiring, as extensions so MailGraph and ReadingGraph stay untouched while other lanes work.
 * The coordinator swaps `graph.readingDetailPane()` for `graph.flexReadingDetailPane()` in
 * MainActivity, and passes `suppressedAlerts()` to the Priority list (see InboxRoute).
 */

/**
 * Like [readingDetailPane], but Flex-aware: in tabletop posture the email stays above the hinge
 * and the reply editor opens below it. Same ViewModel keys, so drafts carry over.
 */
fun MailGraph.flexReadingDetailPane(other: ShellDetailPane = { _, _ -> PlaceholderDetail() }): ShellDetailPane =
    { destination, key ->
        when (destination) {
            ShellDestination.Priority, ShellDestination.Folders -> {
                val id = key.toLongOrNull()
                if (id == null) {
                    PlaceholderDetail()
                } else {
                    val context = LocalContext.current
                    FlexReadingDetail(
                        messageId = id,
                        detailViewModel = { detailViewModel(context, id) },
                        replyViewModel = { replyViewModel(context, id) },
                    )
                }
            }

            else -> {
                other(destination, key)
            }
        }
    }

/** The messages behind the suppressed-alert badge (unread, failed sender checks), newest first. */
fun MailGraph.suppressedAlerts(times: TimeLabels = TimeLabels()): Flow<List<SuppressedItem>> =
    SuppressedAlerts(database).items().map { rows ->
        rows.map { r ->
            SuppressedItem(
                id = r.id,
                sender = r.fromName?.takeIf { it.isNotBlank() } ?: r.fromEmail.orEmpty(),
                subject = r.subject,
                time = times.short(r.receivedAtMillis),
            )
        }
    }
