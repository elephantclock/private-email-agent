package tech.elephantclock.privatemail.core.mail.autoconfig

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoverySource.AUTOCONFIG
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoverySource.ISPDB
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoverySource.MX
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoverySource.MX_HOST_GUESS
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoverySource.PRESET
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoverySource.SRV
import tech.elephantclock.privatemail.core.mail.autoconfig.DnsTestMessages.mx
import tech.elephantclock.privatemail.core.mail.autoconfig.DnsTestMessages.response
import tech.elephantclock.privatemail.core.mail.autoconfig.DnsTestMessages.srv
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.SSL_TLS
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.STARTTLS
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.io.File

class AutoconfigTest {
    private val server = MockWebServer()

    /** Path on the mock server → body. ISPDB lives under /ispdb/, domain autoconfig under /ac/<domain>. */
    private val pages = mutableMapOf<String, String>()
    private val requests = mutableListOf<String>()

    /** Name and type → DNS answer. */
    private val dns = mutableMapOf<Pair<String, Int>, ByteArray>()

    private val autoconfig: Autoconfig by lazy {
        server.useHttps(TestCert.LOCALHOST.serverContext().socketFactory)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    synchronized(requests) { requests += request.url.toString() }
                    return pages[path]?.let {
                        MockResponse
                            .Builder()
                            .code(200)
                            .body(it)
                            .build()
                    }
                        ?: MockResponse.Builder().code(404).build()
                }
            }
        server.start()
        val fetcher =
            HttpsFetcher(
                TlsSocketFactory(PinningTrustManager(CertificatePins.None, TestCert.LOCALHOST.trustedBy())),
                2_000,
                2_000,
            )
        Autoconfig(
            IspdbClient(
                fetcher,
                server.url("/ispdb/").toString(),
                { domain -> listOf(server.url("/ac/$domain").toString()) },
            ),
            SrvLookup { name, type -> dns[name to type] },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun found(email: String) = (autoconfig.discover(email) as DiscoveryResult.Found).settings

    private val exampleXml =
        """<clientConfig version="1.1"><emailProvider id="example.net"><displayName>Example</displayName>
        |<incomingServer type="imap"><hostname>imap.example.net</hostname><port>993</port><socketType>SSL</socketType>
        |<username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication></incomingServer>
        |<outgoingServer type="smtp"><hostname>smtp.example.net</hostname><port>587</port><socketType>STARTTLS</socketType>
        |<username>%EMAILADDRESS%</username><authentication>password-cleartext</authentication></outgoingServer>
        |</emailProvider></clientConfig>
        """.trimMargin()

    @Test
    fun `built-in preset first, nothing sent anywhere`() {
        val s = found("Someone@GMAIL.com")
        assertEquals(PRESET, s.source)
        assertEquals("gmail", s.providerId)
        assertTrue(s.note is ProviderNote.AppPasswordRequired)
        assertFalse(s.needsConfirmation)
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `ISPDB, sending only the domain`() {
        pages["/ispdb/example.net"] = exampleXml
        val s = found("me@example.net")
        assertEquals(ISPDB, s.source)
        assertEquals(ServerConfig("imap.example.net", 993, SSL_TLS), s.imap)
        assertEquals("me@example.net", s.username)
        assertFalse(requests.single().contains("me"))
    }

    @Test
    fun `domain autoconfig over HTTPS when ISPDB has nothing`() {
        pages["/ac/example.net"] = exampleXml
        assertEquals(AUTOCONFIG, found("me@example.net").source)
    }

    @Test
    fun `custom domain hosted by a known provider via MX`() {
        dns["company.com" to DnsMessage.TYPE_MX] = response("company.com", 15, listOf(mx(1, "aspmx.l.google.com")))
        val s = found("ceo@company.com")
        assertEquals(MX, s.source)
        assertEquals("gmail", s.providerId)
        assertFalse(s.needsConfirmation)
    }

    @Test
    fun `MX to an unknown provider via ISPDB needs the user's confirmation`() {
        dns["company.com" to DnsMessage.TYPE_MX] = response("company.com", 15, listOf(mx(1, "mx1.mail.example.net")))
        pages["/ispdb/example.net"] = exampleXml
        val s = found("ceo@company.com")
        assertEquals(MX, s.source)
        assertTrue(s.needsConfirmation)
    }

    @Test
    fun `SRV inside the domain is trusted, outside needs confirmation`() {
        dns["_imaps._tcp.company.com" to 33] =
            response("_imaps._tcp.company.com", 33, listOf(srv(0, 1, 993, "imap.company.com")))
        dns["_submission._tcp.company.com" to 33] =
            response("_submission._tcp.company.com", 33, listOf(srv(0, 1, 587, "smtp.company.com")))
        val inside = found("me@company.com")
        assertEquals(SRV, inside.source)
        assertEquals(ServerConfig("smtp.company.com", 587, STARTTLS), inside.smtp)
        assertFalse(inside.needsConfirmation)

        dns["_imaps._tcp.company.com" to 33] =
            response("_imaps._tcp.company.com", 33, listOf(srv(0, 1, 993, "mail.attacker.example")))
        assertTrue(found("me@company.com").needsConfirmation)
    }

    @Test
    fun `MX host inside the domain is used with standard ports (izi travel shape)`() {
        dns["izi.travel" to DnsMessage.TYPE_MX] = response("izi.travel", 15, listOf(mx(5, "mail.izi.travel")))
        val s = found("someone@izi.travel")
        assertEquals(MX_HOST_GUESS, s.source)
        assertEquals(ServerConfig("mail.izi.travel", 993, SSL_TLS), s.imap)
        assertEquals(ServerConfig("mail.izi.travel", 587, STARTTLS), s.smtp)
        assertFalse(s.needsConfirmation)
    }

    @Test
    fun `nothing found means manual settings`() {
        assertEquals(DiscoveryResult.Manual, autoconfig.discover("me@nowhere.example"))
        assertEquals(DiscoveryResult.Manual, autoconfig.discover("not-an-address"))
    }

    @Test
    fun `international domains are looked up in punycode`() {
        pages["/ispdb/xn--bcher-kva.example"] = exampleXml
        assertEquals(ISPDB, found("me@bücher.example").source)
    }

    @Test
    fun `OAuth-only ISPDB entry is flagged`() {
        pages["/ispdb/example.net"] = exampleXml.replace("password-cleartext", "OAuth2")
        assertFalse(found("me@example.net").passwordSupported)
    }

    @Test
    fun `fixture file is the real ISPDB answer`() {
        assertTrue(File("src/test/resources/autoconfig/ispdb-gmail.com.xml").readText().contains("imap.gmail.com"))
    }
}
