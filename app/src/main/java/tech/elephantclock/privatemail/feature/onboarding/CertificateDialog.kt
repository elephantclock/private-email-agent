package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import tech.elephantclock.privatemail.core.mail.tls.UntrustedReason
import tech.elephantclock.privatemail.ui.components.SectionLabel

/**
 * Asks whether to trust one certificate for one server. Shows host, port and the SHA-256
 * fingerprint so the user can compare it with what their admin gave them. Cancel is the
 * safe default and is offered first in reading order.
 */
@Composable
internal fun CertificateDialog(
    certificate: CertificateInfo,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    val reason =
        stringResource(
            when (certificate.reason) {
                UntrustedReason.CHAIN -> R.string.cert_reason_chain
                UntrustedReason.HOSTNAME -> R.string.cert_reason_hostname
                UntrustedReason.EXPIRED -> R.string.cert_reason_expired
            },
        )
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text(stringResource(R.string.cert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.cert_body, "${certificate.host}:${certificate.port}", reason),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SectionLabel(stringResource(R.string.cert_fingerprint).uppercase())
                Text(
                    // Eight bytes per line, so a byte is never split across lines.
                    certificate.sha256
                        .split(':')
                        .chunked(8)
                        .joinToString("\n") { it.joinToString(":") },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(10.dp),
                )
                Text(
                    stringResource(R.string.cert_subject, certificate.subject),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.cert_issuer, certificate.issuer),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cert_cancel)) } },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.cert_trust)) } },
    )
}
