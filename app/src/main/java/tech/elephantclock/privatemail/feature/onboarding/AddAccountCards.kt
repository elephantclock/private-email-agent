package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.autoconfig.ProviderNote
import tech.elephantclock.privatemail.ui.theme.BrandTheme

/** Beige card under the email field: looking up / found / not found (mockup 01). */
@Composable
internal fun DiscoveryCard(state: AddAccountUiState) {
    val (title, sub) =
        when (state.discovery) {
            DiscoveryStatus.Idle -> {
                return
            }

            DiscoveryStatus.Running -> {
                stringResource(R.string.add_discovery_running) to null
            }

            DiscoveryStatus.Found -> {
                val title =
                    state.providerName?.let { stringResource(R.string.add_discovery_found_provider, it) }
                        ?: stringResource(R.string.add_discovery_found)
                title to stringResource(R.string.add_servers_summary, state.imap.host, state.smtp.host)
            }

            DiscoveryStatus.Manual -> {
                stringResource(R.string.add_discovery_manual) to null
            }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.discovery) {
            DiscoveryStatus.Running -> {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            DiscoveryStatus.Found -> {
                CheckMark(MaterialTheme.colorScheme.onPrimaryContainer)
            }

            else -> {}
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** App-password help or the Microsoft warning; the link opens the browser only when tapped. */
@Composable
internal fun NoteCard(
    note: ProviderNote,
    providerName: String?,
) {
    val (title, body, link) =
        when (note) {
            is ProviderNote.AppPasswordRequired -> {
                Triple(
                    providerName?.let { stringResource(R.string.add_note_app_password_title, it) }
                        ?: stringResource(R.string.add_note_app_password_title_generic),
                    stringResource(R.string.add_note_app_password_body),
                    stringResource(R.string.add_note_app_password_link),
                )
            }

            is ProviderNote.MicrosoftPasswordBlocked -> {
                Triple(
                    stringResource(R.string.add_note_microsoft_title),
                    stringResource(R.string.add_note_microsoft_body),
                    stringResource(R.string.add_note_microsoft_link),
                )
            }
        }
    val warning = note is ProviderNote.MicrosoftPasswordBlocked
    OutlinedCard(if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (warning) BrandTheme.colors.sectionLabelDanger else MaterialTheme.colorScheme.onSurface,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinkButton(link, note.helpUrl)
    }
}

/** RFC 6186 § 6: hosts from DNS outside the domain are shown, and nothing is sent until OK. */
@Composable
internal fun ConfirmHostsCard(
    state: AddAccountUiState,
    actions: AddAccountActions,
) {
    val domain = state.email.substringAfterLast('@').trim()
    OutlinedCard(MaterialTheme.colorScheme.error) {
        Text(
            stringResource(R.string.add_confirm_title),
            style = MaterialTheme.typography.titleSmall,
            color = BrandTheme.colors.sectionLabelDanger,
        )
        Text(
            stringResource(R.string.add_confirm_body, domain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.add_confirm_incoming, "${state.imap.host}:${state.imap.port}"),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.add_confirm_outgoing, "${state.smtp.host}:${state.smtp.port}"),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(
                onClick = actions::onConfirmHosts,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.add_confirm_ok)) }
            OutlinedButton(
                onClick = { if (!state.serverSettingsOpen) actions.onToggleServerSettings() },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.add_confirm_edit)) }
        }
    }
}

/** One failed server: our headline first, the server's own words only under "Details". */
@Composable
internal fun ProblemCard(problem: ServerProblem) {
    val e = problem.explanation
    OutlinedCard(MaterialTheme.colorScheme.error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            stringResource(
                when (problem.role) {
                    ServerRole.Incoming -> R.string.add_problem_incoming
                    ServerRole.Outgoing -> R.string.add_problem_outgoing
                    ServerRole.Both -> R.string.add_problem_both
                },
            ).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = BrandTheme.colors.sectionLabelDanger,
        )
        Text(
            e.headline.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        e.detail?.let {
            Text(
                stringResource(R.string.add_problem_details, it),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.textMuted,
            )
        }
        e.helpUrl?.let { LinkButton(stringResource(R.string.add_problem_help), it) }
    }
}

@Composable
internal fun UiText.resolve(): String = stringResource(res, *args.toTypedArray())

@Composable
private fun OutlinedCard(
    border: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.small)
            .border(1.5.dp, border, MaterialTheme.shapes.small)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun LinkButton(
    text: String,
    url: String,
) {
    val uri = LocalUriHandler.current
    TextButton(
        onClick = { uri.openUri(url) },
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(text, style = MaterialTheme.typography.titleSmall) }
}

@Composable
internal fun CheckMark(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val u = size.width / 24f
        val path =
            listOf(Offset(5f * u, 12f * u), Offset(9f * u, 16f * u), Offset(19f * u, 6f * u))
        drawLine(color, path[0], path[1], 2.4f * u, StrokeCap.Round)
        drawLine(color, path[1], path[2], 2.4f * u, StrokeCap.Round)
    }
}
