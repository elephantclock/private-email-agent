package tech.elephantclock.privatemail.core.mail.tls

import org.junit.Assert.assertThrows
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException

class PinningTrustManagerTest {
    private val tm = PinningTrustManager(CertificatePins.None, TestCert.LOCALHOST.trustedBy())
    private val chain = arrayOf(TestCert.LOCALHOST.certificate)

    @Test
    fun `without a handshake session the host is unknown and nothing is trusted`() {
        // A socket to 127.0.0.1 whose reverse DNS is "localhost", which the certificate covers.
        // Trusting it would mean trusting whatever the peer IP's PTR record says.
        TestServers().use { servers ->
            Socket(InetAddress.getByName("127.0.0.1"), servers.imapPort).use { socket ->
                assertThrows(CertificateException::class.java) { tm.checkServerTrusted(chain, "RSA", socket) }
            }
        }
    }

    @Test
    fun `the socket-less overloads refuse too`() {
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(chain, "RSA") }
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(chain, "RSA", null as Socket?) }
    }

    @Test
    fun `client certificates are never accepted`() {
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(chain, "RSA") }
    }
}
