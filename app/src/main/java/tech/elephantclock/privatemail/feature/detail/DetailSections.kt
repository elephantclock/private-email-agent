package tech.elephantclock.privatemail.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.components.ImportancePill
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Pill, folder chip and attachment count above the subject (mockups 08, 09). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BriefChips(
    triage: TriageBrief?,
    attachments: Int,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        triage?.let {
            ImportancePill(it.level)
            InfoChip(it.customFolder ?: stringResource(it.folder.label()))
        }
        if (attachments > 0) InfoChip("${stringResource(R.string.detail_attachments)} · $attachments")
    }
}

@Composable
private fun InfoChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

fun FolderRole.label(): Int =
    when (this) {
        FolderRole.PRIORITY -> R.string.detail_folder_priority
        FolderRole.PEOPLE -> R.string.detail_folder_people
        FolderRole.MONEY -> R.string.detail_folder_money
        FolderRole.WORK -> R.string.detail_folder_work
        FolderRole.ORDERS -> R.string.detail_folder_orders
        FolderRole.REPORTS -> R.string.detail_folder_reports
        FolderRole.CUSTOM -> R.string.detail_folder_priority
        FolderRole.DISCARDED -> R.string.detail_folder_discarded
        FolderRole.JUNK -> R.string.detail_folder_junk
    }

/** Next steps (AI only): drawn only when the model ran and produced some. */
@Composable
fun NextSteps(steps: List<String>) {
    BrandCard {
        SectionLabel(stringResource(R.string.detail_next_steps))
        steps.forEachIndexed { i, step ->
            Text("${i + 1}. $step", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The original message, collapsed by default: headers and the full text as plain text. */
@Composable
fun OriginalEmail(
    state: DetailUiState,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    val header = state.header ?: return
    val toggle =
        stringResource(if (state.originalExpanded) R.string.detail_original_hide else R.string.detail_original_show)
    BrandCard(
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(0.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = toggle, role = Role.Button, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("original_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.detail_original))
                Text(
                    "${header.fromName ?: header.fromEmail} · ${header.date}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            Icon(
                if (state.originalExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = toggle,
            )
        }
        if (!state.originalExpanded) return@BrandCard
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(16.dp).testTag("original_body"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HeaderLine(
                stringResource(R.string.detail_from),
                header.fromName?.let { "$it <${header.fromEmail}>" } ?: header.fromEmail,
            )
            HeaderLine(stringResource(R.string.detail_to), header.to)
            header.cc?.let { HeaderLine(stringResource(R.string.detail_cc), it) }
            HeaderLine(stringResource(R.string.detail_date), header.date)
            header.account?.let { HeaderLine(stringResource(R.string.detail_account), it) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            BodyText(state.body, onRetry)
        }
    }
}

@Composable
private fun HeaderLine(
    label: String,
    value: String,
) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTheme.colors.textMuted,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BodyText(
    body: BodyState,
    onRetry: () -> Unit,
) {
    when (body) {
        BodyState.Loading -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.detail_body_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }

        is BodyState.Failed -> {
            Text(stringResource(body.problem.text()), style = MaterialTheme.typography.bodyMedium)
            if (body.problem == Problem.OFFLINE) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.detail_retry)) }
            }
        }

        is BodyState.Loaded -> {
            // Plain text only, never a WebView: no scripts, no remote images, no tracking pixels.
            SelectionContainer { Text(body.text, style = MaterialTheme.typography.bodyLarge) }
            if (body.fromHtml) Note(stringResource(R.string.detail_body_html))
            if (body.truncated) Note(stringResource(R.string.detail_body_truncated))
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = BrandTheme.colors.textMuted,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Attachments: names and sizes only; nothing is downloaded until one is tapped. */
@Composable
fun Attachments(
    items: List<AttachmentItem>,
    onOpen: (String) -> Unit,
) {
    if (items.isEmpty()) return
    BrandCard(
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(vertical = 6.dp),
    ) {
        SectionLabel(stringResource(R.string.detail_attachments), Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        items.forEach { a ->
            val label = stringResource(R.string.detail_attachment_open, a.name)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !a.downloading, onClickLabel = label, role = Role.Button) { onOpen(a.partId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("attachment:${a.partId}"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        a.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (a.downloading) {
                            stringResource(
                                R.string.detail_attachment_downloading,
                            )
                        } else {
                            formatSize(a.sizeBytes)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandTheme.colors.textMuted,
                    )
                }
                a.problem?.let { Note(stringResource(it.text())) }
            }
        }
    }
}

fun Problem.text(): Int =
    when (this) {
        Problem.GONE -> R.string.problem_gone
        Problem.NEEDS_SIGN_IN -> R.string.problem_sign_in
        Problem.OFFLINE -> R.string.problem_offline
        Problem.TOO_LARGE -> R.string.problem_too_large
        Problem.NO_APP -> R.string.problem_no_app
        Problem.REFUSED_TYPE -> R.string.problem_refused
        Problem.NOT_FOUND -> R.string.problem_not_found
    }

/** Approximate size from BODYSTRUCTURE (encoded bytes, so a little over the real size). */
fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }
