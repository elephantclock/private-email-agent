package tech.elephantclock.privatemail.feature.reply

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import java.util.Locale

/** The AI part of [ReplyActions]. None of these can send: they only change the editor's text. */
data class ReplyAiActions(
    val onAction: (AiAction) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onTranslate: () -> Unit = {},
    val onDownloadTranslation: () -> Unit = {},
)

internal val AiAction.label: Int
    get() =
        when (this) {
            AiAction.DRAFT -> R.string.reply_ai_draft
            AiAction.SHORTER -> R.string.reply_rewrite_shorter
            AiAction.FORMAL -> R.string.reply_rewrite_formal
            AiAction.FRIENDLIER -> R.string.reply_rewrite_friendlier
            AiAction.PROOFREAD -> R.string.reply_rewrite_proofread
        }

/** "Russian", in the phone's language. */
internal fun LanguageTag.displayName(): String =
    Locale.forLanguageTag(value).getDisplayLanguage(Locale.getDefault()).ifBlank { value }

private fun ReplyUiState.chipEnabled(action: AiAction) =
    ai.available(action) && ai.working == null && aiMayEdit && (action == AiAction.DRAFT || body.isNotBlank())

@Composable
internal fun AiChip(
    state: ReplyUiState,
    action: AiAction,
    onAction: (AiAction) -> Unit,
) {
    AssistChip(
        onClick = { onAction(action) },
        enabled = state.chipEnabled(action),
        label = { Text(stringResource(action.label)) },
        leadingIcon =
            if (state.ai.working == action) {
                { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        modifier = Modifier.heightIn(min = 48.dp).testTag("ai_${action.name.lowercase()}"),
    )
}

/** Rewrite chips, AI status (working, failed + Retry, Undo) and the translation preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiTools(
    state: ReplyUiState,
    actions: ReplyAiActions,
) {
    val ai = state.ai
    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.reply_rewrite_label))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val chips = listOf(AiAction.SHORTER, AiAction.FORMAL, AiAction.FRIENDLIER, AiAction.PROOFREAD)
            (if (ai.draftAvailable) listOf(AiAction.DRAFT) + chips else chips).forEach {
                AiChip(state, it, actions.onAction)
            }
        }
        when {
            !ai.rewriteAvailable && !ai.translateAvailable -> {
                Muted(
                    R.string.reply_ai_unavailable,
                    "reply_ai_unavailable",
                )
            }

            !ai.rewriteAvailable -> {
                Muted(R.string.reply_ai_rewrite_unavailable, "reply_ai_unavailable")
            }
        }
        AiStatus(ai, actions)
        TranslationCard(state, actions)
    }
}

@Composable
private fun Muted(
    text: Int,
    tag: String,
) = Text(
    stringResource(text),
    style = MaterialTheme.typography.bodySmall,
    color = BrandTheme.colors.textMuted,
    modifier = Modifier.testTag(tag),
)

/** One line under the chips: what the AI is doing or did, with Retry or Undo. */
@Composable
internal fun AiStatus(
    ai: ReplyAiUi,
    actions: ReplyAiActions,
) {
    val (text, button) =
        when {
            ai.working != null -> {
                stringResource(R.string.reply_ai_working) to null
            }

            ai.failed != null -> {
                stringResource(if (ai.failedUnavailable) R.string.reply_ai_not_ready else R.string.reply_ai_failed) to
                    (R.string.reply_ai_retry to actions.onRetry)
            }

            ai.skipped -> {
                stringResource(R.string.reply_ai_skipped) to null
            }

            ai.undo != null -> {
                (
                    if (ai.lastChange == AiAction.DRAFT) {
                        stringResource(R.string.reply_ai_drafted)
                    } else {
                        stringResource(
                            R.string.reply_ai_changed,
                            stringResource(ai.lastChange?.label ?: R.string.reply_ai_draft),
                        )
                    }
                ) to (R.string.reply_ai_undo to actions.onUndo)
            }

            else -> {
                return
            }
        }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ai_status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ai.working != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        button?.let { (label, onClick) ->
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_status_button")) {
                Text(stringResource(label), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TranslationCard(
    state: ReplyUiState,
    actions: ReplyAiActions,
) {
    val ai = state.ai
    val user = ai.userLanguage.displayName()
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).testTag("translation")) {
            if (!ai.translateAvailable) {
                SectionLabel(stringResource(R.string.reply_translation_label))
                CardText(stringResource(R.string.reply_translation_unavailable))
                return@Column
            }
            SectionLabel(stringResource(R.string.reply_translation_label_in, user))
            val t = ai.translation
            when (t) {
                TranslationUi.Idle -> {
                    CardText(stringResource(R.string.reply_translation_idle, user))
                }

                TranslationUi.Working -> {
                    CardText(stringResource(R.string.reply_translation_working))
                }

                is TranslationUi.Ready -> {
                    Text(
                        t.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 6.dp).testTag("translation_text"),
                    )
                    if (t.stale) CardText(stringResource(R.string.reply_translation_stale))
                }

                TranslationUi.SameLanguage -> {
                    CardText(stringResource(R.string.reply_translation_same, user))
                }

                TranslationUi.Undetermined -> {
                    CardText(stringResource(R.string.reply_translation_undetermined))
                }

                is TranslationUi.Unsupported -> {
                    CardText(stringResource(R.string.reply_translation_unsupported, t.from.displayName()))
                }

                is TranslationUi.NeedsDownload -> {
                    CardText(stringResource(R.string.reply_translation_needs_wifi, t.languages.names()))
                }

                is TranslationUi.Downloading -> {
                    CardText(stringResource(R.string.reply_translation_waiting, t.languages.names()))
                }

                TranslationUi.Failed -> {
                    CardText(stringResource(R.string.reply_translation_failed))
                }
            }
            val button =
                when (t) {
                    is TranslationUi.NeedsDownload -> {
                        R.string.reply_translation_download to
                            actions.onDownloadTranslation
                    }

                    TranslationUi.Working, is TranslationUi.Downloading, is TranslationUi.Unsupported -> {
                        null
                    }

                    TranslationUi.Idle -> {
                        R.string.reply_translation_show to actions.onTranslate
                    }

                    TranslationUi.Failed -> {
                        R.string.reply_ai_retry to actions.onTranslate
                    }

                    else -> {
                        R.string.reply_translation_update to actions.onTranslate
                    }
                }
            button?.let { (label, onClick) ->
                TextButton(
                    onClick = onClick,
                    enabled = state.body.isNotBlank() || t is TranslationUi.NeedsDownload,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("translation_button"),
                ) { Text(stringResource(label), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun List<LanguageTag>.names() = joinToString(", ") { it.displayName() }

@Composable
private fun CardText(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
        modifier = Modifier.padding(top = 4.dp),
    )

/** Flex mode's chip row: Draft (with AI), Shorter, More formal, Proofread, then Retry or Undo. */
@Composable
internal fun CompactAiChips(
    state: ReplyUiState,
    actions: ReplyAiActions,
) {
    val chips = listOf(AiAction.SHORTER, AiAction.FORMAL, AiAction.PROOFREAD)
    (if (state.ai.draftAvailable) listOf(AiAction.DRAFT) + chips else chips).forEach {
        AiChip(state, it, actions.onAction)
    }
    val ai = state.ai
    val button =
        when {
            ai.working != null -> null
            ai.failed != null -> R.string.reply_ai_retry to actions.onRetry
            ai.undo != null -> R.string.reply_ai_undo to actions.onUndo
            else -> null
        }
    button?.let { (label, onClick) ->
        TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("ai_status_button")) {
            Text(stringResource(label), fontWeight = FontWeight.Bold)
        }
    }
}
