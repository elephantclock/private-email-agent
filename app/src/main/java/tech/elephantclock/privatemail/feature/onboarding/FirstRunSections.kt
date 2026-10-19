package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunReview
import tech.elephantclock.privatemail.core.data.onboarding.ReviewGroup
import tech.elephantclock.privatemail.core.data.onboarding.ReviewItem
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** KEEP · INTO FOLDERS and DISCARD, each group a row that opens its messages. */
@Composable
internal fun ReviewGroups(
    review: FirstRunReview,
    actions: FirstRunActions,
) {
    if (review.keep.isNotEmpty()) {
        SectionLabel(stringResource(R.string.first_run_keep_label), SectionLabelPadding)
        GroupCard(review.keep, actions)
    }
    if (review.discard.isNotEmpty()) {
        SectionLabel(stringResource(R.string.first_run_discard_label), SectionLabelPadding, danger = true)
        GroupCard(review.discard, actions)
    }
}

@Composable
private fun GroupCard(
    groups: List<ReviewGroup>,
    actions: FirstRunActions,
) {
    BrandCard(CardPadding.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        groups.forEachIndexed { i, group ->
            if (i > 0) HorizontalDivider(color = BrandTheme.colors.divider)
            GroupRow(group) { actions.onOpenGroup(group) }
        }
    }
}

@Composable
private fun GroupRow(
    group: ReviewGroup,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (group.keeps) 48.dp else 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (group.keeps) {
            FolderBar(group.target.role, height = 24)
        }
        Column(Modifier.weight(1f)) {
            Text(groupName(group), style = MaterialTheme.typography.titleSmall)
            groupDestination(group)?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.textMuted,
                )
            }
        }
        Text(
            format(group.count),
            style = MaterialTheme.typography.titleSmall,
            color = if (group.keeps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = BrandTheme.colors.textMuted,
        )
    }
}

@Composable
internal fun groupName(group: ReviewGroup): String =
    when {
        group.target.role == FolderRole.CUSTOM -> group.target.customName.orEmpty()
        else -> stringResource(folderName(group.target.role))
    }

private fun groupDestination(group: ReviewGroup): Int? =
    when (group.action) {
        VerdictAction.DISCARD -> R.string.first_run_group_discarded_dest
        VerdictAction.JUNK -> R.string.first_run_group_junk_dest
        else -> null
    }

/** One group's messages, newest first, with "Keep" for anything that would move. */
@Composable
internal fun DrillDown(
    group: ReviewGroup,
    items: List<ReviewItem>?,
    actions: FirstRunActions,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions::onCloseGroup) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.first_run_back))
        }
        Text(
            groupName(group),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(format(group.count), style = MaterialTheme.typography.titleMedium, color = BrandTheme.colors.textMuted)
    }
    BrandCard(CardPadding.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(0.dp)) {
        when {
            items == null -> {
                EmptyLine(R.string.first_run_items_loading)
            }

            items.isEmpty() -> {
                EmptyLine(R.string.first_run_items_empty)
            }

            else -> {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(color = BrandTheme.colors.divider)
                    ItemRow(item, canKeep = group.action != VerdictAction.KEEP) { actions.onKeep(item) }
                }
            }
        }
    }
    if (items != null && items.size < group.count && items.isNotEmpty()) {
        Text(
            stringResource(R.string.first_run_items_more, format(items.size)),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.textMuted,
            modifier = SectionLabelPadding,
        )
    }
}

@Composable
private fun EmptyLine(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = BrandTheme.colors.textMuted,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun ItemRow(
    item: ReviewItem,
    canKeep: Boolean,
    onKeep: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.from, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subject,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canKeep) {
            val description = stringResource(R.string.first_run_keep_item_description, item.subject)
            TextButton(onClick = onKeep, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(R.string.first_run_keep_item))
            }
        }
    }
}
