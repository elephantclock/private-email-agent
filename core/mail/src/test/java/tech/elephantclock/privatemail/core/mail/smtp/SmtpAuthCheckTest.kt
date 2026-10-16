package tech.elephantclock.privatemail.core.mail.smtp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.imap.platformOnly
import tech.elephantclock.privatemail.core.mail.support.StartTlsFront
import tech.elephantclock.privatemail.core.mail.support.StartTlsFront.Protocol.SMTP
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.PASSWORD
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.fast
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.good
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.wrong
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.SSL_TLS
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.STARTTLS
import tech.elephantclock.privatemail.core.model.ServerConfig

class SmtpAuthCheckTest {
    private val servers = TestServers()
    private val closeables = mutableListOf<AutoCloseable>(servers)

    @After
    fun tearDown() = closeables.reversed().forEach { runCatching { it.close() } }

    private val check = SmtpAuthCheck(CertificatePins.None, TestCert.LOCALHOST.trustedBy())

    private fun front(tls: TestCert?) =
        StartTlsFront(SMTP, servers.smtpPort, tls?.serverContext()).also {
            closeables +=
                it
        }

    @Test
    fun `implicit TLS sign-in succeeds without sending mail`() {
        assertEquals(
            MailResult.Ok(Unit),
            check.check(ServerConfig("localhost", servers.smtpsPort, SSL_TLS), good, fast),
        )
        assertEquals(0, servers.greenMail.receivedMessages.size)
    }

    @Test
    fun `wrong password`() {
        assertEquals(
            MailResult.Failed(AuthError.WrongCredentials),
            check.check(ServerConfig("localhost", servers.smtpsPort, SSL_TLS), wrong, fast),
        )
    }

    @Test
    fun `STARTTLS sign-in, password only after TLS`() {
        val front = front(TestCert.LOCALHOST)
        assertEquals(MailResult.Ok(Unit), check.check(ServerConfig("localhost", front.port, STARTTLS), good, fast))
        assertTrue(front.sawStartTls)
        assertFalse(front.plaintextFromClient.contains(PASSWORD))
    }

    @Test
    fun `server without STARTTLS is refused before AUTH`() {
        val front = front(tls = null)
        val result = check.check(ServerConfig("localhost", front.port, STARTTLS), good, fast)
        assertEquals(MailResult.Failed(AuthError.StartTlsUnavailable), result)
        assertFalse(front.plaintextFromClient.contains("AUTH"))
    }

    @Test
    fun `untrusted certificate on SMTP is offered for pinning`() {
        val result =
            SmtpAuthCheck(
                CertificatePins.None,
                platformOnly(),
            ).check(ServerConfig("localhost", servers.smtpsPort, SSL_TLS), good, fast)
        val error = (result as MailResult.Failed).error
        assertTrue("got $error", error is AuthError.CertificateUntrusted)
    }
}
