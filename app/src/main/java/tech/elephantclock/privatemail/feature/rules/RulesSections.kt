package tech.elephantclock.privatemail.feature.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.CustomFolder
import tech.elephantclock.privatemail.core.data.settings.FolderNames
import tech.elephantclock.privatemail.core.data.settings.VALUE_FOLDERS
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.feature.onboarding.FolderBar
import tech.elephantclock.privatemail.feature.onboarding.folderName
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.components.BrandSwitch
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import tech.elephantclock.privatemail.ui.theme.DarkBrandColors

/** Priority, the value folders (switch + description) and the custom folders, then "Add a folder". */
@Composable
internal fun FoldersCard(
    prefs: AccountPrefs,
    actions: RulesActions,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        FolderRow(
            role = FolderRole.PRIORITY,
            name = stringResource(R.string.rules_folder_priority),
            description =
                prefs.folderDescriptions[FolderRole.PRIORITY] ?: stringResource(
                    defaultDescription(FolderRole.PRIORITY),
                ),
            enabled = null,
            onEdit = { actions.onEditFolder(FolderTarget.Priority) },
            onToggle = {},
        )
        VALUE_FOLDERS.forEach { role ->
            HorizontalDivider(color = BrandTheme.colors.divider)
            val on = prefs.folderEnabled(role)
            FolderRow(
                role = role,
                name = stringResource(folderName(role)),
                description = prefs.folderDescriptions[role] ?: stringResource(defaultDescription(role)),
                enabled = on,
                onEdit = { actions.onEditFolder(FolderTarget(role)) },
                onToggle = { actions.onToggleFolder(role) },
            )
        }
        prefs.customFolders.forEach { folder ->
            HorizontalDivider(color = BrandTheme.colors.divider)
            CustomRow(folder, actions)
        }
        if (prefs.customFolders.size < FolderNames.MAX_CUSTOM_FOLDERS) {
            HorizontalDivider(color = BrandTheme.colors.divider)
            TextButton(
                onClick = actions::onAddFolder,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = linkColor())
                Text(
                    stringResource(R.string.rules_add_folder),
                    color = linkColor(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CustomRow(
    folder: CustomFolder,
    actions: RulesActions,
) {
    FolderRow(
        role = FolderRole.CUSTOM,
        name = folder.name,
        description = folder.description,
        enabled = folder.enabled,
        onEdit = { actions.onEditFolder(FolderTarget.custom(folder.name)) },
        onToggle = { actions.onToggleCustom(folder.name) },
    )
}

/** [enabled] null: no switch (Priority is always on). */
@Composable
private fun FolderRow(
    role: FolderRole,
    name: String,
    description: String,
    enabled: Boolean?,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    val editLabel = stringResource(R.string.rules_folder_edit, name)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(onClick = onEdit, onClickLabel = editLabel)
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(top = 2.dp)) { FolderBar(role, height = 36) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = BrandTheme.colors.textMuted)
                if (enabled == false) {
                    Text(
                        stringResource(R.string.rules_folder_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = linkColor(),
                    )
                }
            }
        }
        if (enabled != null) {
            val switchLabel = stringResource(R.string.rules_folder_switch, name)
            BrandSwitch(
                checked = enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.semantics { contentDescription = switchLabel },
            )
        }
    }
}

/** "appfigures.com → Reports & alerts", one row per rule; a tap opens the change/delete dialog. */
@Composable
internal fun LearnedCard(
    rules: List<RuleRow>,
    actions: RulesActions,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        if (rules.isEmpty()) {
            Text(
                stringResource(R.string.rules_learned_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.textMuted,
                modifier = Modifier.padding(16.dp),
            )
        }
        rules.forEachIndexed { i, rule ->
            if (i > 0) HorizontalDivider(color = BrandTheme.colors.divider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { actions.onRuleClick(rule) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        rule.who,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (rule.allAccounts) {
                        Text(
                            stringResource(R.string.rules_learned_all_accounts),
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandTheme.colors.textMuted,
                        )
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = BrandTheme.colors.textMuted,
                    modifier = Modifier.size(16.dp),
                )
                val label = targetLabel(rule.target)
                Text(
                    if (rule.vip) stringResource(R.string.rules_learned_vip, label) else label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = targetColor(rule.target, rule.vip),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Urgent, High (switches), Everything else (always off: only important mail ever alerts), digest. */
@Composable
internal fun AlertsCard(
    prefs: AccountPrefs,
    digest: Boolean,
    actions: RulesActions,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        SwitchRow(
            R.string.rules_alert_urgent,
            R.string.rules_alert_urgent_hint,
            prefs.alertUrgent,
            actions::onAlertUrgent,
        )
        HorizontalDivider(color = BrandTheme.colors.divider)
        SwitchRow(R.string.rules_alert_high, R.string.rules_alert_high_hint, prefs.alertHigh, actions::onAlertHigh)
        HorizontalDivider(color = BrandTheme.colors.divider)
        SwitchRow(R.string.rules_alert_else, R.string.rules_alert_else_hint, checked = false, onChange = null)
        HorizontalDivider(color = BrandTheme.colors.divider)
        SwitchRow(R.string.rules_digest, R.string.rules_digest_hint, digest, actions::onDigest)
    }
}

/** [onChange] null: shown but fixed. */
@Composable
internal fun SwitchRow(
    title: Int,
    hint: Int,
    checked: Boolean,
    onChange: ((Boolean) -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = BrandTheme.colors.textMuted)
        }
        val label = stringResource(title)
        BrandSwitch(
            checked = checked,
            onCheckedChange = { onChange?.invoke(it) },
            enabled = onChange != null,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

internal fun defaultDescription(role: FolderRole): Int =
    when (role) {
        FolderRole.PEOPLE -> R.string.rules_folder_people_desc
        FolderRole.MONEY -> R.string.rules_folder_money_desc
        FolderRole.WORK -> R.string.rules_folder_work_desc
        FolderRole.ORDERS -> R.string.rules_folder_orders_desc
        FolderRole.REPORTS -> R.string.rules_folder_reports_desc
        else -> R.string.rules_folder_priority_desc
    }

@Composable
internal fun targetLabel(target: FolderTarget): String =
    when (target.role) {
        FolderRole.PRIORITY -> stringResource(R.string.rules_folder_priority)
        FolderRole.CUSTOM -> target.customName.orEmpty()
        FolderRole.DISCARDED -> stringResource(R.string.rules_learned_discard)
        FolderRole.JUNK -> stringResource(R.string.rules_learned_junk)
        else -> stringResource(folderName(target.role))
    }

@Composable
private fun targetColor(
    target: FolderTarget,
    vip: Boolean,
): Color =
    when {
        vip || target.role.isDiscard || target.role == FolderRole.PRIORITY -> linkColor()
        else -> MaterialTheme.colorScheme.onSurface
    }

/** The mockups' dark red; gold reads better on the dark cards. */
@Composable
internal fun linkColor(): Color = BrandTheme.colors.let { if (it == DarkBrandColors) it.accent else it.urgent }
