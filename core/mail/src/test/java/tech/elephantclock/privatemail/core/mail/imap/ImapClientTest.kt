package tech.elephantclock.privatemail.core.mail.imap

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.support.StartTlsFront
import tech.elephantclock.privatemail.core.mail.support.StartTlsFront.Protocol.IMAP
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.PASSWORD
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.fast
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.good
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.wrong
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.UntrustedReason
import tech.elephantclock.privatemail.core.mail.tls.sha256Fingerprint
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.SSL_TLS
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.STARTTLS
import tech.elephantclock.privatemail.core.model.SpecialUse
import java.net.InetAddress
import java.net.ServerSocket
import javax.net.ssl.X509TrustManager

/** The real platform trust store: knows nothing about the test certificates. */
internal fun platformOnly() = PinningTrustManager.platformTrustManager()

class ImapClientTest {
    private val servers = TestServers()
    private val closeables = mutableListOf<AutoCloseable>(servers)

    @After
    fun tearDown() = closeables.reversed().forEach { runCatching { it.close() } }

    private fun client(
        pins: CertificatePins = CertificatePins.None,
        trust: X509TrustManager = TestCert.LOCALHOST.trustedBy(),
    ) = ImapClient(pins, trust)

    private fun implicit(
        host: String = "localhost",
        port: Int = servers.imapsPort,
    ) = ImapConfig(host, port, SSL_TLS, fast)

    private fun front(tls: TestCert?) =
        StartTlsFront(IMAP, servers.imapPort, tls?.serverContext()).also { closeables += it }

    private fun <T> MailResult<T>.ok(): T = (this as? MailResult.Ok)?.value ?: error("expected Ok, got $this")

    private fun MailResult<*>.error(): AuthError =
        (this as? MailResult.Failed)?.error ?: error("expected Failed, got $this")

    @Test
    fun `implicit TLS login reads capabilities and maps folders`() {
        listOf("Sent Items", "Drafts", "Deleted Items", "Spam", "Projects").forEach(servers::createFolder)
        val check = client().check(implicit(), good).ok()
        assertTrue(check.capabilities.idle)
        assertTrue(check.capabilities.move)
        assertTrue(check.capabilities.uidPlus)
        val byUse = check.folders.associateBy({ it.specialUse }, { it.path })
        assertEquals("INBOX", byUse[SpecialUse.INBOX])
        assertEquals("Sent Items", byUse[SpecialUse.SENT])
        assertEquals("Drafts", byUse[SpecialUse.DRAFTS])
        assertEquals("Deleted Items", byUse[SpecialUse.TRASH])
        assertEquals("Spam", byUse[SpecialUse.JUNK])
        assertEquals(null, check.folders.single { it.path == "Projects" }.specialUse)
    }

    @Test
    fun `wrong password is reported as such and never echoed`() {
        val error = client().connect(implicit(), wrong).error()
        assertEquals(AuthError.WrongCredentials, error)
        assertFalse(error.toString().contains(wrong.password.reveal()))
    }

    @Test
    fun `self-signed certificate is refused with its fingerprint, then accepted once pinned`() {
        val error = client(trust = platformOnly()).connect(implicit(), good).error() as AuthError.CertificateUntrusted
        val cert = error.certificate
        assertEquals("localhost", cert.host)
        assertEquals(servers.imapsPort, cert.port)
        assertEquals(sha256Fingerprint(TestCert.LOCALHOST.certificate), cert.sha256)
        assertEquals(UntrustedReason.CHAIN, cert.reason)

        client(CertificatePins.of(cert), platformOnly()).connect(implicit(), good).ok().close()
    }

    @Test
    fun `a pin is for one host and one fingerprint only`() {
        val localhostPin =
            (
                client(
                    trust = platformOnly(),
                ).connect(implicit(), good).error() as AuthError.CertificateUntrusted
            ).certificate

        val otherHost =
            client(
                CertificatePins.of(localhostPin),
                platformOnly(),
            ).connect(implicit("127.0.0.1"), good).error()
        assertEquals("127.0.0.1", (otherHost as AuthError.CertificateUntrusted).certificate.host)

        val stalePin = localhostPin.copy(sha256 = "00:" + localhostPin.sha256.drop(3))
        val changed = client(CertificatePins.of(stalePin), platformOnly()).connect(implicit(), good).error()
        assertEquals(localhostPin.sha256, (changed as AuthError.CertificateUntrusted).certificate.sha256)
    }

    @Test
    fun `STARTTLS login works and the password is never sent before TLS`() {
        val front = front(TestCert.LOCALHOST)
        client().connect(ImapConfig("localhost", front.port, STARTTLS, fast), good).ok().close()
        assertTrue(front.sawStartTls)
        assertFalse(front.plaintextFromClient.contains(PASSWORD))
    }

    @Test
    fun `STARTTLS goes through the pinning trust manager`() {
        val front = front(TestCert.LOCALHOST)
        val error =
            client(trust = platformOnly())
                .connect(ImapConfig("localhost", front.port, STARTTLS, fast), good)
                .error()
        assertTrue(error is AuthError.CertificateUntrusted)
    }

    @Test
    fun `certificate for another host name is refused, and can be pinned`() {
        val front = front(TestCert.OTHER_HOST)
        val config = ImapConfig("localhost", front.port, STARTTLS, fast)
        val error = client(trust = TestCert.OTHER_HOST.trustedBy()).connect(config, good).error()
        val cert = (error as AuthError.CertificateUntrusted).certificate
        assertEquals(UntrustedReason.HOSTNAME, cert.reason)

        client(CertificatePins.of(cert), TestCert.OTHER_HOST.trustedBy()).connect(config, good).ok().close()
    }

    @Test
    fun `server without STARTTLS is refused before any credentials are sent`() {
        val front = front(tls = null)
        val error = client().connect(ImapConfig("localhost", front.port, STARTTLS, fast), good).error()
        assertEquals(AuthError.StartTlsUnavailable, error)
        assertFalse(front.plaintextFromClient.contains(PASSWORD))
        assertFalse(front.plaintextFromClient.contains(TestServers.EMAIL))
    }

    @Test
    fun `implicit TLS against a plain-text port is a TLS failure`() {
        val error = client().connect(implicit(port = servers.imapPort), good).error()
        assertTrue("got $error", error is AuthError.TlsFailure)
    }

    @Test
    fun `silent server times out and closed port is reported as blocked`() {
        val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { closeables += it }
        assertEquals(
            AuthError.PortBlockedOrTimeout("localhost", silent.localPort),
            client().connect(ImapConfig("localhost", silent.localPort, STARTTLS, fast), good).error(),
        )
        val closed = TestServers.closedPort()
        assertEquals(
            AuthError.PortBlockedOrTimeout("localhost", closed),
            client().connect(implicit(port = closed), good).error(),
        )
    }

    @Test
    fun `unknown host`() {
        assertEquals(
            AuthError.HostNotFound("no-such-host.invalid"),
            client().connect(implicit("no-such-host.invalid", 993), good).error(),
        )
    }
}

/** The real platform trust store: knows nothing about the test certificates. */
private object PinningTrustManagerFactory {
    fun platform() =
        tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
            .platformTrustManager()
}
