package tech.elephantclock.privatemail.core.mail.smtp

import jakarta.mail.Session
import org.eclipse.angus.mail.smtp.SMTPTransport
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.ErrorMapper
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import tech.elephantclock.privatemail.core.mail.auth.mailProperties
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import javax.net.ssl.X509TrustManager

/** Checks SMTP sign-in: EHLO, (STARTTLS,) AUTH, then QUIT. Never sends mail. */
class SmtpAuthCheck(
    private val pins: CertificatePins,
    private val baseTrust: X509TrustManager = PinningTrustManager.platformTrustManager(),
) {
    fun check(
        server: ServerConfig,
        credentials: MailCredentials,
        timeouts: Timeouts = Timeouts(),
    ): MailResult<Unit> {
        val trust = PinningTrustManager(pins, baseTrust)
        val props =
            mailProperties("smtp", server.security, TlsSocketFactory(trust), timeouts).apply {
                put("mail.smtp.auth", "true")
            }
        val transport = Session.getInstance(props).getTransport("smtp")
        return try {
            transport.connect(server.host, server.port, credentials.username, credentials.password.reveal())
            // Angus skips AUTH silently when the server doesn't offer it; that would "pass" unchecked.
            if ((transport as SMTPTransport).supportsExtension("AUTH")) {
                MailResult.Ok(Unit)
            } else {
                MailResult.Failed(AuthError.AuthMethodNotSupported("Server offers no AUTH; credentials not checked"))
            }
        } catch (e: Exception) {
            MailResult.Failed(ErrorMapper.map(e, server.host, server.port, credentials.password, trust.lastRejected))
        } finally {
            runCatching { transport.close() }
        }
    }

    companion object {
        /** Port that matches each security mode, for the manual-settings form. */
        fun defaultPort(security: ConnectionSecurity) = if (security == ConnectionSecurity.SSL_TLS) 465 else 587
    }
}
