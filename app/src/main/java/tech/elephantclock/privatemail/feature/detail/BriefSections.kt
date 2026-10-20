package tech.elephantclock.privatemail.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/**
 * The brief under the subject (mockups 08, 09). With a model brief: "Thread summary", the gist,
 * then status and deadline chips; otherwise the rules-only text exactly as before, plus a short
 * line while the model works or after it gave up.
 */
@Composable
fun BriefBlock(state: DetailUiState) {
    val thread = state.thread
    if (thread is ThreadBriefState.Ready) {
        Label(stringResource(R.string.brief_thread_summary))
        Column(Modifier.testTag("thread_brief"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(thread.gist, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("brief"))
            BriefFacts(thread)
            Text(
                stringResource(R.string.brief_ai_note),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.textMuted,
            )
        }
        return
    }
    if (state.brief.isNotBlank()) {
        Label(stringResource(if (state.briefFromAi) R.string.detail_brief_ai else R.string.detail_brief_preview))
        Text(state.brief, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("brief"))
    }
    when (thread) {
        ThreadBriefState.Loading -> BriefLoading()
        ThreadBriefState.Fallback -> Muted(stringResource(R.string.brief_fallback), "brief_fallback")
        else -> Unit
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = BrandTheme.colors.sectionLabel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BriefFacts(brief: ThreadBriefState.Ready) {
    val status = brief.status
    val deadline = brief.deadline?.let(::formatDeadline)
    if (status == null && deadline == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        status?.let { Fact(stringResource(it.label()), "brief_status") }
        deadline?.let { Fact(stringResource(R.string.brief_deadline, it), "brief_deadline") }
    }
}

@Composable
private fun Fact(
    text: String,
    tag: String,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 9.dp, vertical = 3.dp)
                .testTag(tag),
    )
}

/**
 * Static text, no spinner: the ViewModel ends this state within its timeout anyway, and an
 * endless animation keeps screenshot tests from ever going idle.
 */
@Composable
private fun BriefLoading() {
    Text(
        stringResource(R.string.brief_loading),
        style = MaterialTheme.typography.bodySmall,
        color = BrandTheme.colors.textMuted,
        modifier = Modifier.testTag("brief_loading").semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Muted(
    text: String,
    tag: String,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = BrandTheme.colors.textMuted,
        modifier = Modifier.testTag(tag),
    )
}

fun ThreadStatus.label(): Int =
    when (this) {
        ThreadStatus.WAITING_ON_YOU -> R.string.brief_status_waiting_on_you
        ThreadStatus.WAITING_ON_THEM -> R.string.brief_status_waiting_on_them
        ThreadStatus.RESOLVED -> R.string.brief_status_resolved
        ThreadStatus.INFO -> R.string.brief_status_info
    }

/** "2026-10-24" in the phone's date style; null for anything that isn't a date. */
fun formatDeadline(iso: String): String? =
    try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    } catch (_: DateTimeParseException) {
        null
    }
