package tech.elephantclock.privatemail.core.mail.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import java.io.PrintWriter
import java.io.StringWriter

class RedactionTest {
    private val canary = "canary-Pa55w0rd-REDACT"

    @Test
    fun `password and credentials never print the secret`() {
        val creds = MailCredentials("me@example.com", Password(canary))
        assertFalse(creds.toString().contains(canary))
        assertFalse(creds.password.toString().contains(canary))
        assertTrue(creds.toString().contains("me@example.com"))
    }

    @Test
    fun `a failed login leaves no trace of the password in the error or the stack trace`() {
        TestServers().use { servers ->
            val creds = MailCredentials(TestServers.EMAIL, Password(canary))
            val captured = StringWriter()
            val result =
                ImapClient(CertificatePins.None, TestCert.LOCALHOST.trustedBy())
                    .connect(
                        ImapConfig("localhost", servers.imapsPort, ConnectionSecurity.SSL_TLS, TestServers.fast),
                        creds,
                    )
            assertTrue(result is MailResult.Failed)
            captured.append(result.toString())
            // The raw exception Angus throws, with every cause, as a crash reporter would print it.
            runCatching {
                jakarta.mail.Session
                    .getInstance(java.util.Properties())
                    .getStore("imap")
                    .connect("localhost", servers.imapPort, TestServers.EMAIL, canary)
            }.exceptionOrNull()?.printStackTrace(PrintWriter(captured))
            assertFalse(captured.toString().contains(canary))
        }
    }
}
