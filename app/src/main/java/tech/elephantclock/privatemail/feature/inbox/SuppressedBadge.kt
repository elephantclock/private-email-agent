package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** A message whose alert was held back because it failed SPF/DKIM/DMARC (T49). */
data class SuppressedItem(
    val id: Long,
    val sender: String,
    val subject: String,
    val time: String,
)

/**
 * The quiet line instead of silence (T49): "2 messages didn't alert: sender checks failed".
 * Tapping it lists those messages ([items]); tapping one opens it. Nothing shows at zero.
 */
@Composable
fun SuppressedBadge(
    count: Int,
    items: List<SuppressedItem>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
) {
    if (count <= 0) return
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val muted = BrandTheme.colors.textMuted
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = items.isNotEmpty(), role = Role.Button) { open = !open }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("suppressed_badge"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
            Text(
                pluralStringResource(R.plurals.inbox_suppressed_badge, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                Text(
                    stringResource(if (open) R.string.inbox_suppressed_hide else R.string.inbox_suppressed_show),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        if (open) {
            items.forEach { item -> SuppressedRow(item, onOpen) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SuppressedRow(
    item: SuppressedItem,
    onOpen: (Long) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(item.id) }
            .heightIn(min = 48.dp)
            .padding(start = 44.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .testTag("suppressed:${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            item.sender,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subject,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(item.time, style = MaterialTheme.typography.bodySmall, color = BrandTheme.colors.textMuted)
    }
}
