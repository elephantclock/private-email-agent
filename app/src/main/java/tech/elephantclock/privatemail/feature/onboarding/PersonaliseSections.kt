package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.onboarding.SentPerson
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.VALUE_FOLDERS
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.ui.components.BrandCard
import tech.elephantclock.privatemail.ui.components.BrandSwitch
import tech.elephantclock.privatemail.ui.theme.Brand
import tech.elephantclock.privatemail.ui.theme.BrandColors
import tech.elephantclock.privatemail.ui.theme.BrandTheme
import tech.elephantclock.privatemail.ui.theme.DarkBrandColors
import java.text.NumberFormat

private const val SHOWN_PEOPLE = 30

/** "[N] people · Mark VIPs to always alert · Review", expanding to the list with VIP chips. */
@Composable
internal fun PeopleCard(
    state: PersonaliseUiState,
    actions: PersonaliseActions,
    modifier: Modifier = Modifier,
) {
    val people = state.people
    BrandCard(modifier.fillMaxWidth()) {
        if (people.isEmpty()) {
            Text(
                stringResource(
                    if (state.readingSent) R.string.personalise_people_reading else R.string.personalise_people_none,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.textMuted,
            )
            val w = state.work
            if (state.readingSent && w.stage == FirstRunWorkState.Stage.SENT && w.total > 0) {
                LinearProgressIndicator(
                    progress = { (w.done.toFloat() / w.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            return@BrandCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatars(people.take(3))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.personalise_people_count, people.size, format(people.size)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.personalise_people_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandTheme.colors.textMuted,
                )
            }
            val toggle =
                if (state.reviewingPeople) R.string.personalise_people_done else R.string.personalise_people_review
            TextButton(onClick = actions::onReviewPeople) {
                Text(
                    stringResource(toggle),
                    color = BrandTheme.colors.urgentLink(),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        if (state.work.running && state.work.stage == FirstRunWorkState.Stage.SENT && state.work.total > 0) {
            Text(
                stringResource(
                    R.string.personalise_people_reading_count,
                    format(state.work.done),
                    format(state.work.total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (state.reviewingPeople) {
            people.take(SHOWN_PEOPLE).forEach { person ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = BrandTheme.colors.divider)
                PersonRow(person, state.isVip(person)) { actions.onToggleVip(person.email) }
            }
            if (people.size > SHOWN_PEOPLE) {
                Text(
                    stringResource(R.string.personalise_more_people, format(people.size - SHOWN_PEOPLE)),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PersonRow(
    person: SentPerson,
    vip: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(person.name ?: person.email, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                buildString {
                    if (person.name != null) append(person.email).append(" · ")
                    append(pluralStringResourceText(person.count))
                },
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        FilterChip(
            selected = vip,
            onClick = onToggle,
            label = { Text(stringResource(R.string.personalise_vip)) },
            leadingIcon = if (vip) ({ Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) }) else null,
            colors =
                FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = BrandTheme.colors.accent,
                ),
        )
    }
}

@Composable
private fun pluralStringResourceText(count: Int) =
    pluralStringResource(R.plurals.personalise_person_sent, count, format(count))

@Composable
private fun Avatars(people: List<SentPerson>) {
    val colors = listOf(Brand.DarkRed, Brand.Cognac, Brand.DarkGreen)
    val ring = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(Modifier.width((36 + 26 * (people.size - 1).coerceAtLeast(0)).dp)) {
        people.forEachIndexed { i, p ->
            Box(
                Modifier
                    .offset(x = (26 * i).dp)
                    .size(36.dp)
                    .border(2.dp, ring, CircleShape)
                    .padding(2.dp)
                    .background(colors[i % colors.size], CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initials(p), style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

internal fun initials(person: SentPerson): String {
    val source = person.name?.takeIf { it.isNotBlank() } ?: person.email.substringBefore('@')
    val words = source.split(' ', '.', '_', '-').filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.size == 1 -> words[0].take(2)
        else -> "?"
    }.uppercase()
}

/** Switches for People, Money, Work updates, Orders & travel, Reports & alerts. */
@Composable
internal fun ValueFoldersCard(
    prefs: AccountPrefs,
    actions: PersonaliseActions,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        VALUE_FOLDERS.forEachIndexed { i, role ->
            if (i > 0) HorizontalDivider(color = BrandTheme.colors.divider)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FolderBar(role, height = 28)
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(stringResource(folderName(role)), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(folderHint(role)),
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandTheme.colors.textMuted,
                    )
                }
                BrandSwitch(checked = prefs.folderEnabled(role), onCheckedChange = { actions.onToggleFolder(role) })
            }
        }
    }
}

internal fun folderName(role: FolderRole): Int =
    when (role) {
        FolderRole.PEOPLE -> R.string.folder_people
        FolderRole.MONEY -> R.string.folder_money
        FolderRole.WORK -> R.string.folder_work
        FolderRole.ORDERS -> R.string.folder_orders
        FolderRole.REPORTS -> R.string.folder_reports
        FolderRole.PRIORITY -> R.string.first_run_group_priority
        FolderRole.DISCARDED -> R.string.first_run_group_discarded
        FolderRole.JUNK -> R.string.first_run_group_junk
        FolderRole.CUSTOM -> R.string.personalise_custom_label
    }

private fun folderHint(role: FolderRole): Int =
    when (role) {
        FolderRole.PEOPLE -> R.string.folder_people_hint
        FolderRole.MONEY -> R.string.folder_money_hint
        FolderRole.WORK -> R.string.folder_work_hint
        FolderRole.ORDERS -> R.string.folder_orders_hint
        else -> R.string.folder_reports_hint
    }

/** Hold 30 days (recommended) or straight to Trash. */
@Composable
internal fun DiscardModeCard(
    mode: DiscardMode,
    actions: PersonaliseActions,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
        Column(Modifier.selectableGroup()) {
            DiscardOption(mode == DiscardMode.HOLD_30_DAYS, { actions.onDiscardMode(DiscardMode.HOLD_30_DAYS) }) {
                Text(
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(fontWeight = FontWeight.Bold),
                        ) { append(stringResource(R.string.personalise_discard_hold)) }
                        append(stringResource(R.string.personalise_discard_hold_rest))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            DiscardOption(
                mode == DiscardMode.STRAIGHT_TO_TRASH,
                { actions.onDiscardMode(DiscardMode.STRAIGHT_TO_TRASH) },
            ) {
                Column {
                    Text(stringResource(R.string.personalise_discard_trash), style = MaterialTheme.typography.bodyLarge)
                    if (mode == DiscardMode.STRAIGHT_TO_TRASH) {
                        Text(
                            stringResource(R.string.personalise_discard_trash_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = BrandTheme.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscardOption(
    selected: Boolean,
    onSelect: () -> Unit,
    label: @Composable () -> Unit,
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
        label()
    }
}

internal fun format(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** The mockups' dark red link colour; gold reads better on the dark cards. */
@Composable
internal fun BrandColors.urgentLink(): Color = if (this == DarkBrandColors) accent else urgent
