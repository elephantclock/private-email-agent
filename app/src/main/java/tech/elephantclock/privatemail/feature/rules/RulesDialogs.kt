package tech.elephantclock.privatemail.feature.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.FolderNameProblem
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.ui.theme.BrandTheme

@Composable
internal fun RulesDialogHost(
    state: RulesUiState,
    actions: RulesActions,
) {
    when (val d = state.dialog) {
        is RulesDialog.EditFolder -> EditFolderDialog(d, actions)
        is RulesDialog.AddFolder -> AddFolderDialog(d, actions)
        is RulesDialog.Retarget -> RetargetDialog(d.rule, state, actions)
        is RulesDialog.ConfirmImport -> ImportDialog(d, actions)
        null -> Unit
    }
}

@Composable
private fun EditFolderDialog(
    d: RulesDialog.EditFolder,
    actions: RulesActions,
) {
    val custom = d.target.role == FolderRole.CUSTOM
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        title = { Text(targetLabel(d.target)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = d.draft,
                    onValueChange = { actions.onDialogChange(d.copy(draft = it, problem = null)) },
                    label = { Text(stringResource(R.string.rules_dialog_description)) },
                    placeholder = if (custom) null else ({ Text(stringResource(defaultDescription(d.target.role))) }),
                    isError = d.problem != null,
                    supportingText = {
                        Text(
                            d.problem?.let { stringResource(problemText(it)) }
                                ?: if (custom) "" else stringResource(R.string.rules_dialog_description_hint),
                        )
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (custom) {
                    TextButton(onClick = { actions.onRemoveCustom(d.target.customName.orEmpty()) }) {
                        Text(stringResource(R.string.rules_dialog_remove), color = linkColor())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions::onSaveDialog,
            ) { Text(stringResource(R.string.rules_dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = actions::onDismissDialog) { Text(stringResource(R.string.rules_dialog_cancel)) }
        },
    )
}

@Composable
private fun AddFolderDialog(
    d: RulesDialog.AddFolder,
    actions: RulesActions,
) {
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        title = { Text(stringResource(R.string.rules_dialog_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.rules_dialog_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.textMuted,
                )
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { actions.onDialogChange(d.copy(name = it.take(60), problem = null)) },
                    label = { Text(stringResource(R.string.rules_dialog_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = d.description,
                    onValueChange = { actions.onDialogChange(d.copy(description = it.take(400), problem = null)) },
                    label = { Text(stringResource(R.string.rules_dialog_description)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                d.problem?.let { Text(stringResource(problemText(it)), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions::onSaveDialog,
            ) { Text(stringResource(R.string.rules_dialog_save)) }
        },
        dismissButton = {
            TextButton(onClick = actions::onDismissDialog) { Text(stringResource(R.string.rules_dialog_cancel)) }
        },
    )
}

@Composable
private fun RetargetDialog(
    rule: RuleRow,
    state: RulesUiState,
    actions: RulesActions,
) {
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        title = { Text(stringResource(R.string.rules_rule_from, rule.who)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rule.allAccounts) Note(R.string.rules_rule_all_note)
                if (rule.vip) {
                    Note(R.string.rules_rule_vip_note)
                } else {
                    Text(stringResource(R.string.rules_rule_change), style = MaterialTheme.typography.titleSmall)
                    state.ruleTargets.forEach { target ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    target == rule.target,
                                    role = Role.RadioButton,
                                ) { actions.onRetarget(rule, target) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = target == rule.target,
                                onClick = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(targetLabel(target), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Note(R.string.rules_rule_protected_note)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onDeleteRule(rule) }) {
                Text(stringResource(R.string.rules_rule_delete), color = linkColor())
            }
        },
        dismissButton = {
            TextButton(onClick = actions::onDismissDialog) { Text(stringResource(R.string.rules_dialog_cancel)) }
        },
    )
}

@Composable
private fun ImportDialog(
    d: RulesDialog.ConfirmImport,
    actions: RulesActions,
) {
    val file = d.plan.file
    AlertDialog(
        onDismissRequest = actions::onDismissDialog,
        title = { Text(stringResource(R.string.rules_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rules_import_summary))
                Text(pluralStringResource(R.plurals.rules_import_rules, d.plan.rules.size, d.plan.rules.size))
                if (file.discardMode == DiscardMode.STRAIGHT_TO_TRASH) Note(R.string.rules_import_trash_warning)
                if (d.plan.authservIds.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(d.trustAuthservIds, role = Role.Checkbox) {
                                actions.onDialogChange(d.copy(trustAuthservIds = it))
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = d.trustAuthservIds,
                            onCheckedChange = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            stringResource(
                                R.string.rules_import_authserv,
                                d.plan.authservIds
                                    .sorted()
                                    .joinToString(", "),
                            ),
                        )
                    }
                    Note(R.string.rules_import_authserv_warning)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions::onSaveDialog,
            ) { Text(stringResource(R.string.rules_import_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = actions::onDismissDialog) { Text(stringResource(R.string.rules_dialog_cancel)) }
        },
    )
}

@Composable
private fun Note(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = BrandTheme.colors.textMuted)
}

internal fun problemText(p: FolderNameProblem): Int =
    when (p) {
        FolderNameProblem.EMPTY -> R.string.rules_problem_empty
        FolderNameProblem.TOO_LONG -> R.string.rules_problem_too_long
        FolderNameProblem.BAD_CHARACTER -> R.string.rules_problem_bad_character
        FolderNameProblem.DUPLICATE -> R.string.rules_problem_duplicate
        FolderNameProblem.RESERVED -> R.string.rules_problem_reserved
    }
