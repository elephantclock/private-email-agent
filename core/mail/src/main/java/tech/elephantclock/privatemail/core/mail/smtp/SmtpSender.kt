package tech.elephantclock.privatemail.core.mail.smtp

import jakarta.mail.Flags
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.eclipse.angus.mail.smtp.SMTPTransport
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.ErrorMapper
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import tech.elephantclock.privatemail.core.mail.auth.mailProperties
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.FolderPaths
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.core.model.SpecialUse
import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/**
 * Sends one message over SMTP (implicit TLS or required STARTTLS), then files a copy in Sent
 * unless the provider does that itself. Sending needs a fresh [SendApproval] from the user's
 * Send tap; see [UserInitiatedSend]. There are no retries here: a message is offered to the
 * server at most once per approval, and a failed Sent copy never causes a resend.
 */
class SmtpSender(
    private val pins: CertificatePins,
    private val baseTrust: X509TrustManager = PinningTrustManager.platformTrustManager(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param imap signed-in IMAP connection for the Sent copy; null skips it.
     * @param providerId the account's preset id ("gmail", "microsoft", ...), see [SentPolicy].
     */
    @UserInitiatedSend
    fun send(
        message: OutgoingMessage,
        approval: SendApproval,
        server: ServerConfig,
        credentials: MailCredentials,
        imap: ImapConnection?,
        providerId: String? = null,
        timeouts: Timeouts = Timeouts(),
        imapCapabilities: Capabilities? = imap?.capabilities,
    ): SendResult {
        if (message.recipients.isEmpty()) return SendResult.NotSent(SendError.NoRecipients)
        approval.consumeFor(message)?.let { return SendResult.NotSent(it) }

        val mime = MimeBuilder.build(message, clock(), Flags(Flags.Flag.SEEN))
        transmit(mime, message, server, credentials, timeouts)?.let { return SendResult.NotSent(it) }

        val copy =
            when {
                SentPolicy.providerSavesSent(providerId, server.host, imapCapabilities) -> SentCopy.SavedByProvider
                imap == null -> SentCopy.Skipped
                else -> saveCopy(imap, mime, message, imapCapabilities ?: imap.capabilities, credentials)
            }
        return SendResult.Sent(message.messageId, copy)
    }

    /** Null when the server accepted the message for every recipient. */
    private fun transmit(
        mime: MimeMessage,
        message: OutgoingMessage,
        server: ServerConfig,
        credentials: MailCredentials,
        timeouts: Timeouts,
    ): SendError? {
        val trust = PinningTrustManager(pins, baseTrust)
        val props =
            mailProperties("smtp", server.security, TlsSocketFactory(trust), timeouts).apply {
                put("mail.smtp.auth", "true")
                // All or nothing: one refused recipient means nobody gets it.
                put("mail.smtp.sendpartial", "false")
                put("mail.smtp.from", message.from.email)
                // EHLO name: never the device's host name.
                put("mail.smtp.localhost", "[127.0.0.1]")
            }
        val transport = Session.getInstance(props).getTransport("smtp") as SMTPTransport
        return try {
            transport.connect(server.host, server.port, credentials.username, credentials.password.reveal())
            // Angus skips AUTH silently when the server doesn't offer it; refuse instead, as SmtpAuthCheck does.
            if (!transport.supportsExtension("AUTH") && !transport.supportsExtension("AUTH=LOGIN")) {
                return SendError.Connection(AuthError.AuthMethodNotSupported("Server offers no AUTH"))
            }
            val recipients = message.recipients.map { InternetAddress(it.email, true) }.toTypedArray()
            transport.sendMessage(mime, recipients)
            null
        } catch (e: SendFailedException) {
            val chain = generateSequence<Throwable>(e) { it.cause }
            if (chain.any { it is IOException || it is SSLException }) {
                SendError.Connection(
                    ErrorMapper.map(e, server.host, server.port, credentials.password, trust.lastRejected),
                )
            } else {
                val text = ErrorMapper.redact(chain.mapNotNull { it.message }.joinToString(" | "), credentials.password)
                val invalid = e.invalidAddresses.orEmpty().map { it.toString() }
                if (invalid.isNotEmpty()) SendError.RecipientsRejected(invalid, text) else SendError.Rejected(text)
            }
        } catch (e: Exception) {
            SendError.Connection(ErrorMapper.map(e, server.host, server.port, credentials.password, trust.lastRejected))
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun saveCopy(
        imap: ImapConnection,
        mime: MimeMessage,
        message: OutgoingMessage,
        capabilities: Capabilities,
        credentials: MailCredentials,
    ): SentCopy =
        try {
            val path = FolderPaths.ensureSpecial(imap, SpecialUse.SENT)
            SentCopy.Saved(Appender.append(imap, path, mime, message.messageId, capabilities))
        } catch (e: Exception) {
            SentCopy.Failed(ErrorMapper.redact(e.message ?: e.javaClass.simpleName, credentials.password))
        }
}
