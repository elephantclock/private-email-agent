package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.ui.components.FolderChip

@Composable
fun FolderListRoute(
    viewModel: InboxViewModel,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()
    FolderListScreen(
        state = state,
        items = items,
        selectedId = selectedId,
        actions =
            ListActions(
                onOpen = onOpen,
                onSelectAccount = viewModel::selectAccount,
                onSelectFolder = viewModel::selectFolder,
                onQuery = viewModel::setQuery,
                onOpenSearch = viewModel::openSearch,
                onCloseSearch = viewModel::closeSearch,
                onRefresh = viewModel::refresh,
                onDismissProblem = viewModel::dismissProblem,
            ),
        modifier = modifier,
    )
}

/**
 * Folders (mockups 08, 09): one account at a time; its synced folders as chips in the header
 * (Inbox first), the chosen folder's mail below. Chips instead of a third navigation level keep
 * list → email → reply within the shell's two panes.
 */
@Composable
fun FolderListScreen(
    state: InboxUiState,
    items: LazyPagingItems<MessageRow>,
    selectedId: Long?,
    actions: ListActions,
    modifier: Modifier = Modifier,
) {
    val account = state.accounts.firstOrNull { it.id == state.selectedAccount }
    ListBackground {
        Column(modifier.fillMaxSize()) {
            InboxHeader(
                title = stringResource(R.string.inbox_title_folders),
                subtitle = account?.let { stringResource(R.string.inbox_one_account, it.label) },
                searching = state.searching,
                query = state.query,
                onQuery = actions.onQuery,
                onOpenSearch = actions.onOpenSearch,
                onCloseSearch = actions.onCloseSearch,
            ) {
                if (state.accounts.size > 1) {
                    state.accounts.forEach { a ->
                        FolderChip(
                            label = a.label,
                            selected = a.id == state.selectedAccount,
                            onClick = { actions.onSelectAccount(a.id) },
                            dotColor = accountColor(a.colorIndex),
                        )
                    }
                }
                state.folders.forEach { f ->
                    val name =
                        if (f.path ==
                            MailRepository.INBOX
                        ) {
                            stringResource(R.string.inbox_folder_inbox)
                        } else {
                            f.label
                        }
                    FolderChip(
                        label = if (f.unread > 0) stringResource(R.string.inbox_folder_chip, name, f.unread) else name,
                        selected = f.path == state.selectedFolder,
                        onClick = { actions.onSelectFolder(f.path) },
                    )
                }
            }
            ListBody(
                state = state,
                items = items,
                selectedId = selectedId,
                actions = actions,
                emptyText =
                    stringResource(
                        if (state.folders.isEmpty()) R.string.inbox_empty_folders else R.string.inbox_empty_folder,
                    ),
                showToday = false,
            )
        }
    }
}
