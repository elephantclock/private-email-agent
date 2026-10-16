package tech.elephantclock.privatemail.core.mail

import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import tech.elephantclock.privatemail.core.mail.imap.ImapCheck
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.smtp.SmtpAuthCheck
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.model.ServerConfig
import javax.net.ssl.X509TrustManager

/** IMAP and SMTP are reported separately: "IMAP fine, SMTP wrong password" is common. */
data class ConnectionTestResult(
    val imap: MailResult<ImapCheck>,
    val smtp: MailResult<Unit>,
) {
    val ok: Boolean get() = imap is MailResult.Ok && smtp is MailResult.Ok
}

/** The "Test connection" button: signs in to both servers read-only, then signs out. */
class ConnectionTester(
    pins: CertificatePins,
    baseTrust: X509TrustManager = PinningTrustManager.platformTrustManager(),
    private val timeouts: Timeouts = Timeouts(),
) {
    private val imap = ImapClient(pins, baseTrust)
    private val smtp = SmtpAuthCheck(pins, baseTrust)

    fun test(
        imapServer: ServerConfig,
        smtpServer: ServerConfig,
        credentials: MailCredentials,
    ) = ConnectionTestResult(
        imap = imap.check(ImapConfig.from(imapServer, timeouts), credentials),
        smtp = smtp.check(smtpServer, credentials, timeouts),
    )
}
