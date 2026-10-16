package tech.elephantclock.privatemail.core.mail

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import tech.elephantclock.privatemail.core.mail.autoconfig.AndroidDnsClient
import tech.elephantclock.privatemail.core.mail.autoconfig.DnsMessage
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory
import tech.elephantclock.privatemail.core.mail.tls.UntrustedReason
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Checks on Android's own network stack (Conscrypt, DnsResolver) that JVM tests can't cover.
 * Needs internet; read-only (no sign-in). Run: `./gradlew :core:mail:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceNetworkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun androidDnsClientResolvesMx() {
        val answer = AndroidDnsClient(context).query("izi.travel", DnsMessage.TYPE_MX)!!
        assertEquals("mail.izi.travel", DnsMessage.parseMx(answer).first().host)
    }

    /** Angus's implicit-TLS path: unconnected socket, then connect(InetSocketAddress). */
    @Test
    fun implicitTlsHandshakeIsTrusted() {
        val tm = PinningTrustManager(CertificatePins.None)
        (TlsSocketFactory(tm).createSocket() as SSLSocket).use { s ->
            s.connect(InetSocketAddress("mail.izi.travel", 993), 10_000)
            s.soTimeout = 10_000
            s.startHandshake()
        }
        assertNull(tm.lastRejected)
    }

    /** Angus's STARTTLS path: the layered createSocket(socket, host, port, autoClose). */
    @Test
    fun startTlsLayeredHandshakeIsTrusted() {
        val tm = PinningTrustManager(CertificatePins.None)
        Socket().use { plain ->
            plain.connect(InetSocketAddress("mail.izi.travel", 587), 10_000)
            plain.soTimeout = 10_000
            val input = BufferedReader(InputStreamReader(plain.getInputStream(), Charsets.US_ASCII))
            val out = plain.getOutputStream()

            fun readReply(): String {
                var line: String
                do line = input.readLine() while (line.length > 3 && line[3] == '-')
                return line
            }
            readReply()
            out.write("EHLO device-test\r\n".toByteArray())
            readReply()
            out.write("STARTTLS\r\n".toByteArray())
            assertEquals("220", readReply().take(3))
            (
                TlsSocketFactory(
                    tm,
                ).createSocket(plain, "mail.izi.travel", 587, true) as SSLSocket
            ).use { it.startHandshake() }
        }
        assertNull(tm.lastRejected)
    }

    /** The trust manager must learn the real host and port from Conscrypt's handshake session. */
    @Test
    fun untrustedAndWrongHostCertificatesAreReportedWithHostAndPort() {
        fun handshake(host: String): PinningTrustManager {
            val tm = PinningTrustManager(CertificatePins.None)
            (TlsSocketFactory(tm).createSocket() as SSLSocket).use { s ->
                s.connect(InetSocketAddress(host, 443), 10_000)
                s.soTimeout = 10_000
                assertThrows(SSLException::class.java) { s.startHandshake() }
            }
            return tm
        }
        val selfSigned = handshake("self-signed.badssl.com").lastRejected!!
        assertEquals("self-signed.badssl.com", selfSigned.host)
        assertEquals(443, selfSigned.port)
        assertEquals(UntrustedReason.CHAIN, selfSigned.reason)

        val wrongHost = handshake("wrong.host.badssl.com").lastRejected!!
        assertEquals("wrong.host.badssl.com", wrongHost.host)
        assertEquals(UntrustedReason.HOSTNAME, wrongHost.reason)
    }
}
