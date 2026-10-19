package tech.elephantclock.privatemail.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunReview
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Stage
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.feature.shell.rememberWindowInfo
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.theme.Brand
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import kotlin.math.roundToInt

/** Screen 03 wired to its view model. */
@Composable
fun FirstRunRoute(
    viewModel: FirstRunViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.drillDown != null) { viewModel.onCloseGroup() }
    FirstRunScreen(state, viewModel, modifier)
}

/**
 * First run: keep vs discard (mockup 03). While the worker runs: its stage and progress. Then the
 * groups (tap one to see its messages and keep any of them), Apply and Label only. Apply stays
 * disabled, with the reason, until the Sent history is complete.
 */
@Composable
fun FirstRunScreen(
    state: FirstRunUiState,
    actions: FirstRunActions,
    modifier: Modifier = Modifier,
    windowInfo: WindowInfo = rememberWindowInfo(),
) {
    val review = state.review?.takeIf { !state.busy }
    OnboardingFrame(
        windowInfo = windowInfo,
        modifier = modifier,
        header = { FirstRunHeader(state, review) },
        actions = { FirstRunActionsBar(state, review, actions, onDark = !windowInfo.isCompactWidth) },
    ) {
        when {
            state.busy || review == null -> if (!state.failed) NothingMovesYet()
            state.drillDown != null -> DrillDown(state.drillDown, state.items, actions)
            else -> ReviewGroups(review, actions)
        }
    }
}

@Composable
private fun FirstRunHeader(
    state: FirstRunUiState,
    review: FirstRunReview?,
) {
    val brand = BrandTheme.colors
    when {
        state.failed -> {
            StepTitle(
                stringResource(R.string.first_run_step),
                stringResource(R.string.first_run_failed_title),
                stringResource(
                    if (state.work.blocked) R.string.first_run_blocked_body else R.string.first_run_failed_body,
                ),
            )
        }

        state.busy || review == null -> {
            StepTitle(stringResource(R.string.first_run_step), stringResource(R.string.first_run_busy_title), null)
            val w = state.work
            Text(
                stringResource(stageText(w.stage)),
                style = MaterialTheme.typography.bodyLarge,
                color = brand.onHeaderMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (w.total > 0) {
                LinearProgressIndicator(
                    progress = { (w.done.toFloat() / w.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = brand.accent,
                    trackColor = brand.headerOutline,
                )
                Text(
                    stringResource(R.string.first_run_progress, format(w.done), format(w.total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = brand.onHeaderMuted,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = brand.accent, trackColor = brand.headerOutline)
            }
        }

        review.total == 0 -> {
            StepTitle(stringResource(R.string.first_run_step), stringResource(R.string.first_run_title_empty), null)
        }

        else -> {
            val title =
                pluralStringResource(
                    R.plurals.first_run_title_sorted,
                    review.total,
                    format(review.total),
                    format(review.keepCount),
                    format(review.discardCount),
                )
            StepTitle(stringResource(R.string.first_run_step), title, null)
            ShareBar(review)
        }
    }
}

private fun stageText(stage: Stage?): Int =
    when (stage) {
        Stage.SENT -> R.string.first_run_stage_sent
        Stage.SORT -> R.string.first_run_stage_sort
        Stage.APPLY -> R.string.first_run_stage_apply
        Stage.SYNC, null -> R.string.first_run_stage_sync
    }

/** The keep (gold) / discard (red) bar with its two percentages. */
@Composable
private fun ShareBar(review: FirstRunReview) {
    val keep = review.keepCount.toFloat() / review.total
    val keepPercent = (keep * 100).roundToInt()
    Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
        if (review.keepCount > 0) Box(Modifier.weight(keep).fillMaxHeight().background(Brand.Gold))
        if (review.discardCount > 0) Box(Modifier.weight(1f - keep).fillMaxHeight().background(Brand.DarkRed))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val style = MaterialTheme.typography.bodySmall
        Text(
            stringResource(R.string.first_run_keep_share, keepPercent),
            style = style,
            color = BrandTheme.colors.onHeaderMuted,
        )
        Text(
            stringResource(R.string.first_run_discard_share, 100 - keepPercent),
            style = style,
            color = BrandTheme.colors.onHeaderMuted,
        )
    }
}

@Composable
private fun FirstRunActionsBar(
    state: FirstRunUiState,
    review: FirstRunReview?,
    actions: FirstRunActions,
    onDark: Boolean,
) {
    val brand = BrandTheme.colors
    val muted = if (onDark) brand.onHeaderMuted else MaterialTheme.colorScheme.onSurfaceVariant
    val gate =
        when (state.backfill) {
            BackfillStatus.PENDING -> R.string.first_run_gate_pending
            BackfillStatus.NO_SENT_FOLDER -> R.string.first_run_gate_no_sent
            else -> null
        }.takeIf { review != null && !state.busy }
    val note = gate ?: R.string.first_run_hint_hold.takeIf { review != null && !state.busy && !state.failed }
    note?.let {
        Text(
            stringResource(it),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val buttonColors =
        if (onDark) {
            ButtonDefaults.buttonColors(
                containerColor = brand.accent,
                contentColor = brand.onAccent,
            )
        } else {
            ButtonDefaults.buttonColors()
        }
    if (state.busy) {
        // Apply appears with the review; until then only "Label only" (and not while applying).
    } else if (state.failed) {
        Button(
            actions::onRetry,
            Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = buttonColors,
        ) {
            Text(stringResource(R.string.first_run_retry), style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Button(
            onClick = actions::onApply,
            enabled = state.canApply,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = buttonColors,
        ) {
            Text(
                stringResource(
                    R.string.first_run_apply,
                    format(review?.fileCount ?: 0),
                    format(review?.discardCount ?: 0),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    TextButton(
        onClick = actions::onLabelOnly,
        enabled = state.canLabelOnly,
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        Text(
            stringResource(R.string.first_run_label_only),
            style = MaterialTheme.typography.titleSmall,
            color = if (onDark) brand.onHeader else MaterialTheme.colorScheme.primary,
        )
    }
}

/** While the worker runs: what is happening, and the promise that nothing moves yet. */
@Composable
private fun NothingMovesYet() {
    BrandCard(CardPadding.fillMaxWidth().padding(top = 16.dp)) {
        Text(stringResource(R.string.first_run_nothing_moves), style = MaterialTheme.typography.bodyLarge)
    }
}
