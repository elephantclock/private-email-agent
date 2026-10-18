package tech.elephantclock.privatemail

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.elephantclock.privatemail.feature.inbox.FolderListRoute
import tech.elephantclock.privatemail.feature.inbox.InboxMode
import tech.elephantclock.privatemail.feature.inbox.InboxRoute
import tech.elephantclock.privatemail.feature.inbox.InboxViewModel
import tech.elephantclock.privatemail.feature.inbox.RoomInboxSource
import tech.elephantclock.privatemail.feature.shell.LocalShellNavigation
import tech.elephantclock.privatemail.feature.shell.PlaceholderDetail
import tech.elephantclock.privatemail.feature.shell.PlaceholderList
import tech.elephantclock.privatemail.feature.shell.ShellDestination
import tech.elephantclock.privatemail.feature.shell.ShellDetailPane
import tech.elephantclock.privatemail.feature.shell.ShellListPane

/*
 * The reading screens (T44–T46) on top of MailGraph, as extensions so MailGraph itself stays
 * untouched while other lanes work on it. Wire them into the shell with
 * `AppShell(listPane = graph.readingListPane(other), detailPane = graph.readingDetailPane(other))`.
 */

fun MailGraph.inboxSource() = RoomInboxSource(database, repository, afterSync = { orchestrator.drain() })

fun MailGraph.inboxViewModel(mode: InboxMode) = InboxViewModel(inboxSource(), mode)

/**
 * List panes for Priority and Folders; [other] draws the remaining destinations (Discarded,
 * Rules: placeholders until their lanes land). Pane keys are local message ids.
 */
fun MailGraph.readingListPane(other: ShellListPane = { d, _, _ -> PlaceholderList(d) }): ShellListPane =
    { destination, selectedKey, onOpen ->
        when (destination) {
            ShellDestination.Priority, ShellDestination.Folders -> {
                ReadingList(this, destination, selectedKey?.toLongOrNull()) { onOpen(it.toString()) }
            }

            else -> {
                other(destination, selectedKey, onOpen)
            }
        }
    }

/** Detail panes for Priority and Folders; [other] for the remaining destinations. */
fun MailGraph.readingDetailPane(other: ShellDetailPane = { _, _ -> PlaceholderDetail() }): ShellDetailPane =
    { destination, key ->
        when (destination) {
            ShellDestination.Priority, ShellDestination.Folders -> PlaceholderDetail()
            else -> other(destination, key)
        }
    }

@Composable
private fun ReadingList(
    graph: MailGraph,
    destination: ShellDestination,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
) {
    val navigation = LocalShellNavigation.current
    if (destination == ShellDestination.Priority) {
        val vm = viewModel(key = "inbox:priority") { graph.inboxViewModel(InboxMode.PRIORITY) }
        InboxRoute(vm, selectedId, onOpen, onReviewDiscarded = { navigation.select(ShellDestination.Discarded) })
    } else {
        val vm = viewModel(key = "inbox:folders") { graph.inboxViewModel(InboxMode.FOLDERS) }
        FolderListRoute(vm, selectedId, onOpen)
    }
}
