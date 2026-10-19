package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** The dashed "Add your own folder, in plain words" box of mockup 02, with the folders added so far. */
@Composable
internal fun CustomFolderBox(
    state: PersonaliseUiState,
    actions: PersonaliseActions,
    modifier: Modifier = Modifier,
) {
    val dash = MaterialTheme.colorScheme.outline
    Column(
        modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style =
                        Stroke(
                            stroke,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                        ),
                )
            }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.personalise_custom_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        state.prefs.customFolders.forEach { folder ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folder.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        folder.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandTheme.colors.textMuted,
                    )
                }
                val remove = stringResource(R.string.personalise_custom_remove, folder.name)
                IconButton(onClick = { actions.onRemoveCustomFolder(folder.name) }) {
                    Icon(Icons.Filled.Close, contentDescription = remove)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.customDraft,
                onValueChange = actions::onCustomDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.personalise_custom_placeholder)) },
                isError = state.customProblem != null,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { actions.onAddCustomFolder() }),
                shape = MaterialTheme.shapes.small,
            )
            OutlinedButton(onClick = actions::onAddCustomFolder, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.personalise_custom_add))
            }
        }
        state.customProblem?.let { problem ->
            Text(
                stringResource(
                    when (problem) {
                        CustomFolderProblem.EMPTY -> R.string.personalise_custom_empty
                        CustomFolderProblem.DUPLICATE -> R.string.personalise_custom_duplicate
                        CustomFolderProblem.TOO_LONG -> R.string.personalise_custom_too_long
                        CustomFolderProblem.BAD_CHARACTER -> R.string.personalise_custom_bad_character
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
