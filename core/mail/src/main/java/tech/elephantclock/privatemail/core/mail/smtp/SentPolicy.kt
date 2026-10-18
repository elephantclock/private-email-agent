package tech.elephantclock.privatemail.core.mail.smtp

import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import java.util.Locale

/** Whether the provider files sent mail in Sent by itself, so we must not APPEND a second copy. */
object SentPolicy {
    private val providerIds = setOf("gmail", "microsoft")
    private val smtpHosts =
        setOf("smtp.gmail.com", "smtp.googlemail.com", "smtp.office365.com", "smtp-mail.outlook.com", "smtp.live.com")

    fun providerSavesSent(
        providerId: String?,
        smtpHost: String,
        capabilities: Capabilities?,
    ): Boolean =
        providerId in providerIds ||
            smtpHost.lowercase(Locale.ROOT).trimEnd('.') in smtpHosts ||
            capabilities?.gmail == true
}
