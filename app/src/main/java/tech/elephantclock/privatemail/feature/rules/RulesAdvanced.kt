package tech.elephantclock.privatemail.feature.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Hold 30 days or straight to Trash, with a note when the server has no Trash to move to. */
@Composable
internal fun DiscardCard(
    state: RulesUiState,
    actions: RulesActions,
    modifier: Modifier = Modifier,
) {
    val mode = state.prefs.discardMode
    BrandCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        Column(Modifier.selectableGroup()) {
            ModeOption(mode == DiscardMode.HOLD_30_DAYS, R.string.rules_discard_hold) {
                actions.onDiscardMode(DiscardMode.HOLD_30_DAYS)
            }
            ModeOption(mode == DiscardMode.STRAIGHT_TO_TRASH, R.string.rules_discard_trash) {
                actions.onDiscardMode(DiscardMode.STRAIGHT_TO_TRASH)
            }
        }
        if (state.trashMissing) {
            Text(
                stringResource(R.string.rules_discard_no_trash),
                style = MaterialTheme.typography.bodySmall,
                color = linkColor(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    label: Int,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Whether mail moves, and why not (the Sent-history gate of T43). */
@Composable
internal fun AutoFileCard(
    state: AutoFileState,
    modifier: Modifier = Modifier,
) {
    val text =
        when (state) {
            AutoFileState.ON -> R.string.rules_auto_on
            AutoFileState.NOT_REQUESTED -> R.string.rules_auto_not_requested
            AutoFileState.REREADING_SENT -> R.string.rules_auto_rereading
            AutoFileState.NO_SENT_FOLDER -> R.string.rules_auto_no_sent
        }
    BrandCard(modifier.fillMaxWidth()) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == AutoFileState.ON) MaterialTheme.colorScheme.onSurface else BrandTheme.colors.textMuted,
        )
    }
}

/** Trusted authserv-ids, behind "Show advanced settings", with a plain explanation. */
@Composable
internal fun AdvancedCard(
    state: RulesUiState,
    actions: RulesActions,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        TextButton(
            onClick = actions::onToggleAdvanced,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
        ) {
            Text(
                stringResource(if (state.showAdvanced) R.string.rules_advanced_hide else R.string.rules_advanced_show),
                color = linkColor(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
        }
        if (!state.showAdvanced) return@BrandCard
        HorizontalDivider(color = BrandTheme.colors.divider)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rules_authserv_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.rules_authserv_explain),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.textMuted,
            )
            val ids = state.prefs.trustedAuthservIds.sorted()
            if (ids.isEmpty()) {
                Text(
                    stringResource(R.string.rules_authserv_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandTheme.colors.textMuted,
                )
            }
            ids.forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(id, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { actions.onRemoveAuthserv(id) }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.rules_authserv_remove, id))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.authservDraft,
                    onValueChange = actions::onAuthservDraft,
                    singleLine = true,
                    label = { Text(stringResource(R.string.rules_authserv_hint)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = actions::onAddAuthserv) { Text(stringResource(R.string.rules_authserv_add)) }
            }
        }
    }
}

/** Export / import through the system file picker. */
@Composable
internal fun BackupCard(
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.rules_backup_hint),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.textMuted,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport) { Text(stringResource(R.string.rules_export)) }
            OutlinedButton(onClick = onImport) { Text(stringResource(R.string.rules_import)) }
        }
    }
}
