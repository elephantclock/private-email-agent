package tech.elephantclock.privatemail.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme

@Composable
internal fun AccountCard(
    account: AccountItem,
    lastSync: String?,
    removing: Boolean,
    actions: AccountsScreenActions,
    onSignInAgain: () -> Unit,
) {
    val brand = BrandTheme.colors
    BrandCard(Modifier.fillMaxWidth().testTag("account:${account.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).background(accountColour(account.colorIndex), CircleShape))
            Column(Modifier.weight(1f)) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (account.name != account.email) {
                    Text(
                        account.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = brand.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
        val status =
            when {
                removing -> stringResource(R.string.accounts_status_removing)
                account.needsPassword -> stringResource(R.string.accounts_status_password)
                lastSync != null -> stringResource(R.string.accounts_status_synced, lastSync)
                else -> stringResource(R.string.accounts_status_never)
            }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (account.needsPassword && !removing) brand.sectionLabelDanger else brand.textMuted,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        SectionLabel(stringResource(R.string.accounts_colour))
        ColourChoice(account, enabled = !removing, onColor = { actions.onColor(account.id, it) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (account.needsPassword) {
                TextButton(
                    onClick = onSignInAgain,
                    enabled = !removing,
                ) { Text(stringResource(R.string.accounts_sign_in_again)) }
            }
            TextButton(onClick = { actions.onRename(account.id) }, enabled = !removing) {
                Text(stringResource(R.string.accounts_rename))
            }
            TextButton(onClick = { actions.onRemove(account.id) }, enabled = !removing) {
                Text(
                    stringResource(R.string.accounts_remove),
                    color = if (removing) Color.Unspecified else brand.sectionLabelDanger,
                )
            }
        }
    }
}

/** One round swatch per palette colour; the chosen one has a ring and a check mark, and each says its name. */
@Composable
private fun ColourChoice(
    account: AccountItem,
    enabled: Boolean,
    onColor: (Int) -> Unit,
) {
    val selectedIndex = account.colorIndex.mod(AccountPalette.swatches.size)
    Row(Modifier.selectableGroup().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AccountPalette.swatches.forEachIndexed { index, swatch ->
            val selected = index == selectedIndex
            val colour = accountColour(index)
            val name = stringResource(swatch.name)
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onColor(index) }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .then(
                            if (selected) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape,
                                )
                            } else {
                                Modifier
                            },
                        ).padding(if (selected) 4.dp else 0.dp)
                        .background(colour, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        val tint =
                            if (colour.luminance() >
                                0.4f
                            ) {
                                BrandTheme.colors.header
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            }
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RenameDialog(
    dialog: AccountsDialog.Rename,
    actions: AccountsScreenActions,
) {
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        // The theme leaves surfaceContainerHigh at Material's default lilac; use the brand card colour.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text(stringResource(R.string.accounts_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.accounts_rename_hint, dialog.email))
                OutlinedTextField(
                    value = dialog.text,
                    onValueChange = actions::onRenameText,
                    singleLine = true,
                    placeholder = { Text(dialog.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth().testTag("rename-field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions::onRenameConfirm,
            ) { Text(stringResource(R.string.accounts_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = actions::onDismissDialog,
            ) { Text(stringResource(R.string.accounts_cancel)) }
        },
    )
}

@Composable
internal fun RemoveDialog(
    dialog: AccountsDialog.ConfirmRemove,
    actions: AccountsScreenActions,
) {
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text(stringResource(R.string.accounts_remove_title, dialog.email)) },
        text = { Text(stringResource(R.string.accounts_remove_body)) },
        confirmButton = {
            TextButton(onClick = actions::onRemoveConfirm) {
                Text(stringResource(R.string.accounts_remove_confirm), color = BrandTheme.colors.sectionLabelDanger)
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions::onDismissDialog,
            ) { Text(stringResource(R.string.accounts_cancel)) }
        },
    )
}
