package tech.elephantclock.privatemail.feature.discarded

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.ui.components.BrandCard

/** The mockup's light beige subtitle on the dark-red header (5.6:1). */
internal val HeaderSubtitle = Color(0xFFF5E3C4)

@Composable
internal fun GroupCard(
    group: DiscardGroup,
    busy: Boolean,
    unsubscribed: Boolean,
    actions: DiscardedScreenActions,
) {
    BrandCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = group.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val description = pluralStringResource(R.plurals.discard_count_description, group.count, group.count)
            Text(
                text = group.count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            CardButton(stringResource(R.string.discard_restore), enabled = !busy) { actions.onRestore(group) }
            if (group.canNeverDiscard) {
                CardButton(stringResource(R.string.discard_never), enabled = !busy) { actions.onNeverDiscard(group) }
            }
            if (group.canUnsubscribe && !unsubscribed) {
                Button(
                    onClick = { actions.onUnsubscribe(group) },
                    enabled = !busy,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = BUTTON_PADDING,
                ) { Text(stringResource(R.string.discard_unsubscribe), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun CardButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = BUTTON_PADDING,
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

private val BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * Asks before anything leaves the device. The mailto approval is created here, in the confirm
 * button's tap handler, for exactly the message the dialog shows, and nowhere else.
 */
@OptIn(UserInitiatedSend::class)
@Composable
internal fun UnsubscribeDialog(
    plan: UnsubscribePlan,
    actions: DiscardedScreenActions,
) {
    AlertDialog(
        onDismissRequest = actions::onDismissConfirm,
        // The theme leaves surfaceContainerHigh at Material's default lilac; use the brand card colour.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text(stringResource(R.string.discard_dialog_title, plan.senderLabel)) },
        text = {
            when (plan) {
                is UnsubscribePlan.OneClick -> {
                    Text(stringResource(R.string.discard_dialog_one_click, plan.host))
                }

                is UnsubscribePlan.Mail -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.discard_dialog_mail, plan.message.from.email))
                        Text(stringResource(R.string.discard_dialog_to, plan.message.to.joinToString { it.email }))
                        Text(stringResource(R.string.discard_dialog_subject, plan.message.subject))
                    }
                }
            }
        },
        confirmButton = {
            when (plan) {
                is UnsubscribePlan.OneClick -> {
                    TextButton(onClick = actions::onConfirmOneClick) {
                        Text(stringResource(R.string.discard_dialog_confirm_one_click))
                    }
                }

                is UnsubscribePlan.Mail -> {
                    TextButton(onClick = { actions.onConfirmMail(SendApproval(plan.message)) }) {
                        Text(stringResource(R.string.discard_dialog_confirm_mail))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = actions::onDismissConfirm) { Text(stringResource(R.string.discard_dialog_cancel)) }
        },
    )
}

internal fun DiscardNotice.text(context: Context): String {
    val r = context.resources
    return when (this) {
        is DiscardNotice.Restored -> r.getQuantityString(R.plurals.discard_notice_restored, count, count)
        is DiscardNotice.RestoreFailed -> r.getQuantityString(R.plurals.discard_notice_restore_failed, count, count)
        is DiscardNotice.NeverDiscard -> r.getString(R.string.discard_notice_never, sender)
        is DiscardNotice.Unsubscribed -> r.getString(R.string.discard_notice_unsubscribed, sender)
        is DiscardNotice.UnsubscribeRefused -> r.getString(R.string.discard_notice_refused, sender)
        is DiscardNotice.UnsubscribeFailed -> r.getString(R.string.discard_notice_failed, sender)
        is DiscardNotice.UnsubscribeUnknown -> r.getString(R.string.discard_notice_unknown, sender)
    }
}
