package tech.elephantclock.privatemail.core.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.fast
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.good
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.SSL_TLS
import tech.elephantclock.privatemail.core.model.ServerConfig

class ConnectionTesterTest {
    private val servers = TestServers()
    private val tester = ConnectionTester(CertificatePins.None, TestCert.LOCALHOST.trustedBy(), fast)

    @After
    fun tearDown() = servers.close()

    @Test
    fun `both servers fine`() {
        val result =
            tester.test(
                ServerConfig("localhost", servers.imapsPort, SSL_TLS),
                ServerConfig("localhost", servers.smtpsPort, SSL_TLS),
                good,
            )
        assertTrue(result.ok)
    }

    @Test
    fun `IMAP and SMTP are reported separately`() {
        val closed = TestServers.closedPort()
        val result =
            tester.test(
                ServerConfig("localhost", servers.imapsPort, SSL_TLS),
                ServerConfig("localhost", closed, SSL_TLS),
                good,
            )
        assertFalse(result.ok)
        assertTrue(result.imap is MailResult.Ok)
        assertEquals(MailResult.Failed(AuthError.PortBlockedOrTimeout("localhost", closed)), result.smtp)
    }
}
