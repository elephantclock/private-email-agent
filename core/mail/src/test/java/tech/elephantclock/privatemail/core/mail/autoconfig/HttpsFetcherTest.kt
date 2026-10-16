package tech.elephantclock.privatemail.core.mail.autoconfig

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory

class HttpsFetcherTest {
    private val server = MockWebServer()
    private val fetcher =
        HttpsFetcher(
            socketFactory = TlsSocketFactory(PinningTrustManager(CertificatePins.None, TestCert.LOCALHOST.trustedBy())),
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 2_000,
            maxBytes = 1_000,
        )

    @Before
    fun setUp() {
        server.useHttps(TestCert.LOCALHOST.serverContext().socketFactory)
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).toString()

    private fun respond(
        code: Int,
        body: String = "",
        location: String? = null,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .apply { location?.let { addHeader("Location", it) } }
            .build(),
    )

    @Test
    fun `returns the body of a 200 over HTTPS`() {
        respond(200, "<clientConfig/>")
        assertEquals("<clientConfig/>", fetcher.get(url("/config")))
    }

    @Test
    fun `non-200 and oversized bodies give null`() {
        respond(404)
        assertNull(fetcher.get(url("/missing")))
        respond(200, "x".repeat(2_000))
        assertNull(fetcher.get(url("/big")))
    }

    @Test
    fun `plain http is refused, also as a redirect target`() {
        assertNull(fetcher.get(url("/x").replace("https://", "http://")))
        respond(302, location = url("/next").replace("https://", "http://"))
        assertNull(fetcher.get(url("/redirect")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `https redirects are followed`() {
        respond(301, location = "/moved")
        respond(200, "ok")
        assertEquals("ok", fetcher.get(url("/start")))
    }

    @Test
    fun `untrusted certificate gives null`() {
        respond(200, "secret")
        assertNull(HttpsFetcher().get(url("/config")))
    }
}
