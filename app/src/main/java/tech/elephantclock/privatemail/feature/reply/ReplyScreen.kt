package tech.elephantclock.privatemail.feature.reply

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Everything the reply editor can do; the stateless screen takes one parameter for it. */
data class ReplyActions(
    val onTo: (String) -> Unit = {},
    val onSubject: (String) -> Unit = {},
    val onBody: (String) -> Unit = {},
    /** Opens the confirmation only. */
    val onSend: () -> Unit = {},
    val onCancelSend: () -> Unit = {},
    /** The confirmation's Send button: the only path to sending. */
    val onConfirmSend: () -> Unit = {},
    val onSaveDraft: () -> Unit = {},
    val onClose: () -> Unit = {},
)

@Composable
fun ReplyRoute(
    viewModel: ReplyViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Flex mode: the short editor that fits below the hinge (mockup 10). */
    compact: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    val actions =
        ReplyActions(
            onTo = viewModel::setTo,
            onSubject = viewModel::setSubject,
            onBody = viewModel::setBody,
            onSend = viewModel::requestSend,
            onCancelSend = viewModel::cancelSend,
            onConfirmSend = { sendConfirmed(viewModel) },
            onSaveDraft = viewModel::saveDraft,
            onClose = onClose,
        )
    if (compact) {
        CompactReplyEditor(state, actions, modifier)
    } else {
        ReplyScreen(state = state, actions = actions, modifier = modifier)
    }
}

/**
 * The confirmed Send tap: the **only** place in the app that opts in to [UserInitiatedSend] and
 * creates a [SendApproval]. It approves exactly the message the dialog showed; a second tap
 * finds no pending message and does nothing.
 */
@OptIn(UserInitiatedSend::class)
private fun sendConfirmed(viewModel: ReplyViewModel) {
    val message = viewModel.state.value.pending ?: return
    viewModel.send(message, SendApproval(message))
}

/** Reply editor (mockup 06): header with Send, From/To/Subject, the draft, AI tools (disabled without AI). */
@Composable
fun ReplyScreen(
    state: ReplyUiState,
    actions: ReplyActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("reply")) {
        ReplyHeader(state, actions)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            if (state.missing) {
                Text(
                    stringResource(R.string.reply_missing),
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }
            AddressFields(state, actions)
            state.notice?.let { NoticeCard(it, if (it.sent) actions.onClose else null) }
            DraftEditor(state, actions)
            AiTools(state)
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = actions.onSaveDraft,
                    enabled = !state.busy && !state.sent && !state.loading,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_draft"),
                ) {
                    Text(
                        stringResource(if (state.savingDraft) R.string.reply_saving else R.string.reply_save_draft),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    stringResource(R.string.reply_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
    SendConfirmDialog(state, actions)
}

@Composable
private fun ReplyHeader(
    state: ReplyUiState,
    actions: ReplyActions,
) {
    val brand = BrandTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(brand.header)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reply_back), tint = brand.onHeader)
        }
        Text(
            stringResource(R.string.reply_title),
            style = MaterialTheme.typography.titleLarge,
            color = brand.onHeader,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Button(
            onClick = actions.onSend,
            enabled = !state.busy && !state.sent && !state.loading && !state.missing,
            colors = ButtonDefaults.buttonColors(containerColor = brand.accent, contentColor = brand.onAccent),
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

/** "Send this reply?" for [ReplyUiState.pending]; its Send button is the only path to sending. */
@Composable
internal fun SendConfirmDialog(
    state: ReplyUiState,
    actions: ReplyActions,
) {
    state.pending?.let { message ->
        AlertDialog(
            onDismissRequest = actions.onCancelSend,
            title = { Text(stringResource(R.string.reply_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.reply_confirm_to, message.to.joinToString(", ") { it.email }))
                    Text(stringResource(R.string.reply_confirm_subject, message.subject))
                }
            },
            // The theme leaves surfaceContainerHigh at Material's default; use the brand surface.
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            confirmButton = {
                TextButton(onClick = actions.onConfirmSend, modifier = Modifier.testTag("confirm_send")) {
                    Text(stringResource(R.string.reply_confirm_send), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelSend, modifier = Modifier.testTag("cancel_send")) {
                    Text(stringResource(R.string.reply_confirm_cancel))
                }
            },
        )
    }
}
