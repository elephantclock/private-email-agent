package tech.elephantclock.privatemail.feature.reply

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/**
 * The reply editor below the hinge in Flex mode (mockup 10): who it goes to, the draft filling
 * the space, then the rewrite chips (disabled until on-device AI, T33), Close, Save draft and
 * Send. Send only opens the same confirmation as the full editor. To and Subject are edited in
 * the full editor (open the device flat); here they are shown so the user knows where it goes.
 * The confirmation is shown here too, below the hinge, instead of a centred dialog that would
 * sit on the fold.
 */
@Composable
internal fun CompactReplyEditor(
    state: ReplyUiState,
    actions: ReplyActions,
    modifier: Modifier = Modifier,
) {
    val editable = !state.sending && !state.sent && !state.loading
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp)
            .testTag("reply_compact"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.missing) {
            Text(stringResource(R.string.reply_missing), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        val pending = state.pending
        if (pending != null) {
            InlineSendConfirm(pending, actions)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.reply_draft_label))
            Text(
                listOf(state.to, state.subject).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("compact_recipient"),
            )
        }
        state.notice?.let { notice ->
            Text(
                stringResource(notice.text),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (notice.sent) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("notice"),
            )
        }
        OutlinedTextField(
            value = state.body,
            onValueChange = actions.onBody,
            enabled = editable,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = MaterialTheme.shapes.medium,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 72.dp)
                    .testTag("field_body"),
        )
        CompactActions(state, actions)
    }
}

/** "Send this reply?" in place of the editor; Send now is the confirmed tap (the only path to sending). */
@Composable
private fun InlineSendConfirm(
    message: OutgoingMessage,
    actions: ReplyActions,
) {
    Column(
        Modifier.fillMaxWidth().testTag("inline_confirm"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.reply_confirm_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.reply_confirm_to, message.to.joinToString(", ") { it.email }))
        Text(stringResource(R.string.reply_confirm_subject, message.subject))
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(
                onClick = actions.onCancelSend,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_send"),
            ) { Text(stringResource(R.string.reply_confirm_cancel), fontWeight = FontWeight.Bold) }
            Button(
                onClick = actions.onConfirmSend,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_send"),
            ) { Text(stringResource(R.string.reply_confirm_send), fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun CompactActions(
    state: ReplyUiState,
    actions: ReplyActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                R.string.reply_rewrite_shorter,
                R.string.reply_rewrite_formal,
                R.string.reply_rewrite_proofread,
            ).forEach { label ->
                // No rewrite engine is wired yet (T33), so the chips stay disabled even with AI.
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        TextButton(onClick = actions.onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("compact_close")) {
            Text(stringResource(R.string.reply_flex_close))
        }
        OutlinedButton(
            onClick = actions.onSaveDraft,
            enabled = !state.busy && !state.sent && !state.loading,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.heightIn(min = 48.dp).testTag("save_draft"),
        ) {
            Text(
                stringResource(if (state.savingDraft) R.string.reply_saving else R.string.reply_save_draft),
                fontWeight = FontWeight.Bold,
            )
        }
        Button(
            onClick = actions.onSend,
            enabled = !state.busy && !state.sent && !state.loading && !state.missing,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.heightIn(min = 48.dp).testTag("send"),
        ) {
            Text(
                stringResource(if (state.sending) R.string.reply_sending else R.string.reply_send),
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}
