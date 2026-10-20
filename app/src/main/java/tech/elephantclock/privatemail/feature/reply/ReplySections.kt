package tech.elephantclock.privatemail.feature.reply

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.components.SectionLabel
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** From (fixed), To and Subject on a white band (mockup 06). */
@Composable
internal fun AddressFields(
    state: ReplyUiState,
    actions: ReplyActions,
) {
    val editable = !state.sending && !state.sent && !state.loading
    val band = MaterialTheme.colorScheme.surfaceContainerLowest
    Column(Modifier.fillMaxWidth().background(band).padding(horizontal = 18.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(stringResource(R.string.reply_from))
            Text(state.from, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InlineField(stringResource(R.string.reply_to), state.to, actions.onTo, editable, "to", KeyboardType.Email)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InlineField(
            stringResource(R.string.reply_subject),
            state.subject,
            actions.onSubject,
            editable,
            "subject",
            KeyboardType.Text,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = BrandTheme.colors.textMuted,
        modifier = Modifier.width(72.dp),
    )
}

@Composable
private fun InlineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    tag: String,
    type: KeyboardType,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(label)
        TextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = type != KeyboardType.Email),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            modifier = Modifier.weight(1f).testTag("field_$tag"),
        )
    }
}

/** The editable draft. */
@Composable
internal fun DraftEditor(
    state: ReplyUiState,
    actions: ReplyActions,
) {
    Column(
        Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel(stringResource(R.string.reply_draft_label))
        OutlinedTextField(
            value = state.body,
            onValueChange = actions.onBody,
            enabled = !state.sending && !state.sent && !state.loading,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = MaterialTheme.shapes.medium,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).testTag("field_body"),
        )
    }
}

/** Outcome of the last send or draft save. [onDone] closes the editor after a send. */
@Composable
internal fun NoticeCard(
    notice: ReplyNotice,
    onDone: (() -> Unit)?,
) {
    val danger = !notice.sent && notice != ReplyNotice.DraftSaved
    Surface(
        color = if (danger) BrandTheme.colors.high else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (danger) BrandTheme.colors.onHigh else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp).testTag("notice"),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(notice.text),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                notice.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (onDone != null) TextButton(onClick = onDone) { Text(stringResource(R.string.reply_done)) }
        }
    }
}
