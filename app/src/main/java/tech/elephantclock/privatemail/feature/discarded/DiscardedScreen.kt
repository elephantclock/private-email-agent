package tech.elephantclock.privatemail.feature.discarded

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.Brand
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** The Discarded tab with its ViewModel; the shell puts this in its list pane. */
@Composable
fun DiscardedRoute(
    viewModel: DiscardedViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscardedScreen(state, viewModel, modifier)
}

/**
 * Screen 05 (mockup "Discarded digest"): a dark-red header with today's count, tabs by sender /
 * by reason / spam, one card per group with Restore, Never discard and (when the sender offers
 * a safe way) Unsubscribe, behind a confirmation dialog. Cards flow into columns on wide panes.
 */
@Composable
fun DiscardedScreen(
    state: DiscardedUiState,
    actions: DiscardedScreenActions,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.text(context))
        actions.onNoticeShown()
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            DiscardHeader(state.discardedToday)
            GroupingTabs(state, actions::onGrouping)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state.loading && state.groups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyGroups(state.grouping == Grouping.SPAM) }
                }
                items(state.groups, key = { it.key }) { group ->
                    GroupCard(
                        group = group,
                        busy = group.key in state.busy,
                        unsubscribed = group.key in state.unsubscribed,
                        actions = actions,
                    )
                }
            }
            Footer()
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
    }
    state.confirm?.let { UnsubscribeDialog(it, actions) }
}

@Composable
private fun DiscardHeader(today: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.DarkRed)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.discard_title_today, today),
            style = MaterialTheme.typography.headlineSmall,
            color = Brand.White,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.discard_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = HeaderSubtitle,
        )
    }
}

@Composable
private fun GroupingTabs(
    state: DiscardedUiState,
    onGrouping: (Grouping) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab(stringResource(R.string.discard_tab_sender), state.grouping == Grouping.SENDER) {
            onGrouping(Grouping.SENDER)
        }
        Tab(stringResource(R.string.discard_tab_reason), state.grouping == Grouping.REASON) {
            onGrouping(Grouping.REASON)
        }
        Tab(stringResource(R.string.discard_tab_spam, state.spamCount), state.grouping == Grouping.SPAM) {
            onGrouping(Grouping.SPAM)
        }
    }
}

/** Mockup pill: filled when selected, outlined otherwise; 34 dp visible, 48 dp touch target. */
@Composable
private fun Tab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(34.dp)
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier.background(colors.primary)
                    } else {
                        Modifier.border(1.5.dp, colors.onSurface, CircleShape)
                    },
                ).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onPrimary else colors.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyGroups(spam: Boolean) {
    Text(
        text = stringResource(if (spam) R.string.discard_empty_spam else R.string.discard_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = BrandTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp),
    )
}

@Composable
private fun Footer() {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            text = stringResource(R.string.discard_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}
