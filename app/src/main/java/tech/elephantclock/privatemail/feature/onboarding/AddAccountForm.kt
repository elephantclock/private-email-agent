package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** The form column: email, what was found, password, errors, the main button, server settings. */
@Composable
internal fun AddAccountForm(
    state: AddAccountUiState,
    password: String,
    actions: AddAccountActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldLabel(stringResource(R.string.add_email_label))
        val emailLabel = stringResource(R.string.add_email_label)
        OutlinedTextField(
            value = state.email,
            onValueChange = actions::onEmailChange,
            singleLine = true,
            enabled = state.busy == null,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            keyboardActions = KeyboardActions(onNext = { actions.onEmailDone() }),
            colors = brandFieldColors(),
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = emailLabel
                        contentType = ContentType.EmailAddress + ContentType.Username
                    }.onFocusChanged { if (!it.isFocused) actions.onEmailDone() },
        )
        DiscoveryCard(state)
        state.note?.let { NoteCard(it, state.providerName) }
        if (!state.passwordSupported &&
            state.note == null
        ) {
            WarningText(stringResource(R.string.add_note_password_unsupported))
        }
        if (state.confirmationPending) ConfirmHostsCard(state, actions)

        FieldLabel(stringResource(R.string.add_password_label))
        PasswordField(password, state.busy == null, actions)

        state.problems.forEach { ProblemCard(it) }
        if (state.testPassed && state.busy == null) SuccessText(stringResource(R.string.add_test_passed))

        PrimaryButton(state, password, actions)
        TextButton(
            onClick = actions::onToggleServerSettings,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            val open = state.serverSettingsOpen
            val toggle = if (open) R.string.add_server_settings_collapse else R.string.add_server_settings_expand
            Text(
                stringResource(toggle),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (state.serverSettingsOpen) ServerSettingsSection(state, password, actions)
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun brandFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    )

/** Hidden by default; no autocorrect or suggestions; marked as a password for autofill. */
@Composable
private fun PasswordField(
    password: String,
    enabled: Boolean,
    actions: AddAccountActions,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.add_password_label)
    OutlinedTextField(
        value = password,
        onValueChange = actions::onPasswordChange,
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { actions.onAddAccount() }),
        colors = brandFieldColors(),
        shape = MaterialTheme.shapes.medium,
        textStyle = MaterialTheme.typography.bodyLarge,
        trailingIcon = {
            val description =
                stringResource(
                    if (visible) R.string.add_password_hide_description else R.string.add_password_show_description,
                )
            TextButton(
                onClick = { visible = !visible },
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(stringResource(if (visible) R.string.add_password_hide else R.string.add_password_show))
            }
        },
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                contentType = ContentType.Password
            },
    )
}

@Composable
private fun PrimaryButton(
    state: AddAccountUiState,
    password: String,
    actions: AddAccountActions,
) {
    val ready = canSignIn(state, password)
    Button(
        onClick = actions::onAddAccount,
        enabled = ready,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(56.dp),
    ) {
        if (state.busy != null) {
            CircularProgressIndicator(
                Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.size(12.dp))
        }
        Text(
            stringResource(
                when (state.busy) {
                    Busy.Testing -> R.string.add_testing
                    Busy.Saving -> R.string.add_saving
                    null -> R.string.add_account
                },
            ),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
internal fun SuccessText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CheckMark(MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun WarningText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = BrandTheme.colors.sectionLabelDanger)
}
