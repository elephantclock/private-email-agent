package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.components.FolderChip

/** Priority (mockup 04): mail that needs the user, across accounts or for one. */
@Composable
fun InboxRoute(
    viewModel: InboxViewModel,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
    onReviewDiscarded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()
    InboxScreen(
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
                onReviewDiscarded = onReviewDiscarded,
            ),
        modifier = modifier,
    )
}

/** Everything the list screens can do, so the stateless screens take one parameter for it. */
data class ListActions(
    val onOpen: (Long) -> Unit = {},
    val onSelectAccount: (String?) -> Unit = {},
    val onSelectFolder: (String) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onOpenSearch: () -> Unit = {},
    val onCloseSearch: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onDismissProblem: () -> Unit = {},
    val onReviewDiscarded: () -> Unit = {},
)

@Composable
fun InboxScreen(
    state: InboxUiState,
    items: LazyPagingItems<MessageRow>,
    selectedId: Long?,
    actions: ListActions,
    modifier: Modifier = Modifier,
) {
    val selected = state.accounts.firstOrNull { it.id == state.selectedAccount }
    ListBackground {
        Column(modifier.fillMaxSize()) {
            InboxHeader(
                title = stringResource(R.string.inbox_title_priority),
                subtitle =
                    if (selected == null) {
                        stringResource(R.string.inbox_all_accounts)
                    } else {
                        stringResource(R.string.inbox_one_account, selected.label)
                    },
                searching = state.searching,
                query = state.query,
                onQuery = actions.onQuery,
                onOpenSearch = actions.onOpenSearch,
                onCloseSearch = actions.onCloseSearch,
            ) {
                FolderChip(
                    label = stringResource(R.string.inbox_chip_all, state.priorityTotal),
                    selected = state.selectedAccount == null,
                    onClick = { actions.onSelectAccount(null) },
                )
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
            }
            ListBody(
                state = state,
                items = items,
                selectedId = selectedId,
                actions = actions,
                emptyText = stringResource(R.string.inbox_empty_priority),
                showToday = true,
            )
        }
    }
}

/** Search results, the problem line and the paged list; shared by Priority and Folders. */
@Composable
internal fun ListBody(
    state: InboxUiState,
    items: LazyPagingItems<MessageRow>,
    selectedId: Long?,
    actions: ListActions,
    emptyText: String,
    showToday: Boolean,
) {
    // Stripes tell accounts apart; with one account they'd only add noise.
    val colors =
        if (state.accounts.size > 1) state.accounts.associate { it.id to accountColor(it.colorIndex) } else emptyMap()
    state.refreshProblem?.let { ProblemLine(it, actions.onDismissProblem) }
    val results = state.results
    if (state.searching && results != null) {
        MessageRows(
            rows = results,
            selectedId = selectedId,
            accountColors = colors,
            onOpen = { actions.onOpen(it.id) },
            emptyText = stringResource(R.string.inbox_search_empty, state.query),
        )
        return
    }
    PagedMessageList(
        items = items,
        selectedId = selectedId,
        accountColors = colors,
        refreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        onOpen = { actions.onOpen(it.id) },
        emptyText = emptyText,
        footer =
            state.today?.takeIf { showToday }?.let { today ->
                { item(key = "today") { TodayCard(today, actions.onReviewDiscarded) } }
            },
    )
}

@Composable
private fun ProblemLine(
    problem: RefreshOutcome,
    onDismiss: () -> Unit,
) {
    val text =
        when (problem) {
            RefreshOutcome.NEEDS_SIGN_IN -> R.string.inbox_refresh_sign_in
            RefreshOutcome.PARTLY_FAILED, RefreshOutcome.DONE -> R.string.inbox_refresh_partly
        }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.inbox_dismiss), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
