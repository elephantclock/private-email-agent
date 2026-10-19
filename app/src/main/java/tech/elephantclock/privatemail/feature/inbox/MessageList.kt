package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.components.ImportancePill
import tech.elephantclock.privatemail.ui.theme.Brand
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Dot and stripe colours per account, in the order accounts were added (mockup 04). */
private val accountColors = listOf(Brand.Cognac, Brand.Gold, Brand.Beige, Brand.DarkRed, Color(0xFF6F8F6A))

fun accountColor(index: Int): Color = accountColors[index.mod(accountColors.size)]

/** Tags the "Loading…" row while the first page is on its way; screenshots wait until it's gone. */
const val PAGED_LOADING_TAG = "paged_loading"

/**
 * A pull-to-refresh list of paged messages, followed by [footer] (the "today" card). Shows
 * [emptyText] once loading finished with nothing to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagedMessageList(
    items: LazyPagingItems<MessageRow>,
    selectedId: Long?,
    accountColors: Map<String, Color>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (MessageRow) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    footer: (LazyListScope.() -> Unit)? = null,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("message_list")) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                items[index]?.let { row ->
                    MessageRowItem(row, row.id == selectedId, accountColors[row.accountId], onOpen = { onOpen(row) })
                }
            }
            val loaded = items.loadState.refresh is LoadState.NotLoading
            if (items.itemCount == 0) {
                item(key = "empty") {
                    if (loaded) {
                        EmptyText(emptyText)
                    } else {
                        EmptyText(stringResource(R.string.inbox_loading), Modifier.testTag(PAGED_LOADING_TAG))
                    }
                }
            }
            footer?.invoke(this)
        }
    }
}

/** Plain list for search results (not paged: search returns at most 100). */
@Composable
fun MessageRows(
    rows: List<MessageRow>,
    selectedId: Long?,
    accountColors: Map<String, Color>,
    onOpen: (MessageRow) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("search_results")) {
        items(rows.size, key = { rows[it].id }) { i ->
            val row = rows[i]
            MessageRowItem(row, row.id == selectedId, accountColors[row.accountId], onOpen = { onOpen(row) })
        }
        if (rows.isEmpty()) item(key = "empty") { EmptyText(emptyText) }
    }
}

/** One message (mockup 04): account stripe, pill, sender, time; subject; one or two lines of preview. */
@Composable
fun MessageRowItem(
    row: MessageRow,
    selected: Boolean,
    accountColor: Color?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val brand = BrandTheme.colors
    val unread = stringResource(R.string.inbox_unread)
    val attachment = stringResource(R.string.inbox_has_attachment)
    val status =
        listOfNotNull(
            unread.takeIf { row.unread },
            attachment.takeIf { row.hasAttachments },
        ).joinToString(", ")
    Column(modifier.testTag("row:${row.id}")) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(if (selected) scheme.surfaceContainerLowest else scheme.background)
                .clickable(onClick = onOpen)
                .semantics {
                    this.selected = selected
                    if (status.isNotEmpty()) stateDescription = status
                }.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val stripe = if (selected) brand.accent else accountColor
            if (stripe != null) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(stripe, RoundedCornerShape(2.dp)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.level?.let { ImportancePill(it) }
                    if (row.unread) Box(Modifier.size(8.dp).background(brand.accent, CircleShape))
                    Text(
                        row.sender,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (row.unread) FontWeight.ExtraBold else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(row.time, style = MaterialTheme.typography.labelMedium, color = brand.textMuted, maxLines = 1)
                }
                Text(
                    row.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.preview.isNotBlank()) {
                    Text(
                        row.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

/** "Today: 38 filed, 61 discarded. Nothing else needs you. Review" (mockup 04). */
@Composable
fun TodayCard(
    today: TodaySummary,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filed = pluralStringResource(R.plurals.inbox_today_filed, today.filed, today.filed)
    val discarded = pluralStringResource(R.plurals.inbox_today_discarded, today.discarded, today.discarded)
    val rest = if (today.labelOnly) R.string.inbox_today_label_only else R.string.inbox_today_rest
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("today_card"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                stringResource(R.string.inbox_today, filed, discarded),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(rest),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        TextButton(onClick = onReview) {
            Text(
                stringResource(R.string.inbox_today_review),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun EmptyText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = BrandTheme.colors.textMuted,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}

/** Fills the pane behind lists, so the off-white shows under short lists. */
@Composable
fun ListBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}
