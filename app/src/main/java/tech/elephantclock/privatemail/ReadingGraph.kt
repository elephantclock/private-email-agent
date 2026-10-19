package tech.elephantclock.privatemail

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.elephantclock.privatemail.core.ai.AiAvailability
import tech.elephantclock.privatemail.core.ai.NoAiAvailability
import tech.elephantclock.privatemail.core.data.repo.MessageContentRepository
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.feature.detail.AttachmentFiles
import tech.elephantclock.privatemail.feature.detail.DetailRoute
import tech.elephantclock.privatemail.feature.detail.DetailViewModel
import tech.elephantclock.privatemail.feature.detail.RoomDetailSource
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
import java.io.File

/*
 * The reading screens (T44–T46) on top of MailGraph, as extensions so MailGraph itself stays
 * untouched while other lanes work on it. Wire them into the shell with
 * `AppShell(listPane = graph.readingListPane(other), detailPane = graph.readingDetailPane(other))`.
 */

fun MailGraph.inboxSource() = RoomInboxSource(database, repository, afterSync = { orchestrator.drain() })

fun MailGraph.inboxViewModel(mode: InboxMode) = InboxViewModel(inboxSource(), mode)

/** Its own connector over the same password store and pins as sync (MailGraph's is private). */
private fun MailGraph.readingConnector() = PasswordImapConnector(secrets, ImapClient(certificatePins))

/** The on-device model isn't wired yet (T31–T33): every AI feature shows as unavailable. */
private val readingAi: AiAvailability = NoAiAvailability()

fun MailGraph.detailViewModel(
    messageId: Long,
    cacheDir: File,
): DetailViewModel {
    AttachmentFiles.pruneOld(cacheDir)
    val content = MessageContentRepository(database, readingConnector())
    return DetailViewModel(messageId, RoomDetailSource(database, content, cacheDir), readingAi)
}

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
            ShellDestination.Priority, ShellDestination.Folders -> {
                key.toLongOrNull()?.let { ReadingDetail(this, it) } ?: PlaceholderDetail()
            }

            else -> {
                other(destination, key)
            }
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

@Composable
private fun ReadingDetail(
    graph: MailGraph,
    messageId: Long,
) {
    val cacheDir = LocalContext.current.cacheDir
    val vm = viewModel(key = "detail:$messageId") { graph.detailViewModel(messageId, cacheDir) }
    DetailRoute(vm, onReply = null)
}
