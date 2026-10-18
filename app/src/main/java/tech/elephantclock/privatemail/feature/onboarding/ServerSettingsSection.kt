package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.ui.components.SectionLabel

/** Editable settings found by discovery (or typed by hand): username, then IMAP and SMTP. */
@Composable
internal fun ServerSettingsSection(
    state: AddAccountUiState,
    password: String,
    actions: AddAccountActions,
) {
    val enabled = state.busy == null
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel(stringResource(R.string.add_server_settings).uppercase())
        OutlinedTextField(
            value = state.username,
            onValueChange = actions::onUsernameChange,
            label = { Text(stringResource(R.string.add_username_label)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            colors = brandFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        ServerBlock(stringResource(R.string.add_incoming), state.imap, enabled, actions::onImapChange)
        ServerBlock(stringResource(R.string.add_outgoing), state.smtp, enabled, actions::onSmtpChange)
        OutlinedButton(
            onClick = actions::onTestConnection,
            enabled = enabled && canSignIn(state, password),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.add_test_connection), style = MaterialTheme.typography.titleSmall) }
    }
}

@Composable
private fun ServerBlock(
    title: String,
    fields: ServerFields,
    enabled: Boolean,
    onChange: (ServerFields) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fields.host,
                onValueChange = { onChange(fields.copy(host = it)) },
                label = { Text(stringResource(R.string.add_host_label)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                colors = brandFieldColors(),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = fields.port,
                onValueChange = { v -> onChange(fields.copy(port = v.filter(Char::isDigit).take(5))) },
                label = { Text(stringResource(R.string.add_port_label)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                colors = brandFieldColors(),
                modifier = Modifier.width(96.dp),
            )
        }
        val options =
            listOf(
                ConnectionSecurity.SSL_TLS to R.string.add_security_ssl,
                ConnectionSecurity.STARTTLS to R.string.add_security_starttls,
            )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (security, label) ->
                SegmentedButton(
                    selected = fields.security == security,
                    onClick = { onChange(fields.copy(security = security)) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // The theme has no secondaryContainer of its own; use the brand beige pill.
                    colors =
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(label)) }
            }
        }
    }
}

internal fun canSignIn(
    state: AddAccountUiState,
    password: String,
) = state.busy == null &&
    !state.confirmationPending &&
    state.discovery != DiscoveryStatus.Running &&
    state.emailValid &&
    password.isNotEmpty() &&
    state.serversValid
