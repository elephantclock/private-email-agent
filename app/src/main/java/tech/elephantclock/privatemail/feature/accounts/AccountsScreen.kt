package tech.elephantclock.privatemail.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.feature.inbox.TimeLabels
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/**
 * The Accounts screen with its ViewModel, for the coordinator to place (e.g. from the Rules tab
 * or a shell menu). [onAddAccount] opens the add-account flow for a new address;
 * [onSignInAgain] reopens it for an account whose password stopped working.
 */
@Composable
fun AccountsRoute(
    viewModel: AccountsViewModel,
    onAddAccount: () -> Unit,
    onSignInAgain: (email: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountsScreen(state, viewModel, onAddAccount, onSignInAgain, modifier)
}

/**
 * Every account on this device: its colour, name, address and sync status, with Rename, a
 * colour choice, Sign in again (when the password stopped working) and Remove, which asks first
 * and deletes only what is stored on this phone. Cards flow into columns on wide windows.
 */
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    actions: AccountsScreenActions,
    onAddAccount: () -> Unit,
    onSignInAgain: (email: String) -> Unit,
    modifier: Modifier = Modifier,
    times: TimeLabels = remember { TimeLabels() },
) {
    val snackbar = remember { SnackbarHostState() }
    val removed = stringResource(R.string.accounts_removed)
    val removeFailed = stringResource(R.string.accounts_remove_failed)
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(
            when (notice) {
                AccountsNotice.REMOVED -> removed
                AccountsNotice.REMOVE_FAILED -> removeFailed
            },
        )
        actions.onNoticeShown()
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            AccountsHeader()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.accounts, key = { it.id }) { account ->
                    AccountCard(
                        account = account,
                        lastSync = account.lastSyncMillis?.let(times::short),
                        removing = state.removing == account.id,
                        actions = actions,
                        onSignInAgain = { onSignInAgain(account.email) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(
                            onClick = onAddAccount,
                            modifier = Modifier.widthIn(min = 240.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                        ) { Text(stringResource(R.string.accounts_add)) }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
    when (val dialog = state.dialog) {
        is AccountsDialog.Rename -> RenameDialog(dialog, actions)
        is AccountsDialog.ConfirmRemove -> RemoveDialog(dialog, actions)
        null -> Unit
    }
}

@Composable
private fun AccountsHeader() {
    val brand = BrandTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(brand.header)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.accounts_title),
            style = MaterialTheme.typography.headlineSmall,
            color = brand.onHeader,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.accounts_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = brand.onHeaderMuted,
        )
    }
}
