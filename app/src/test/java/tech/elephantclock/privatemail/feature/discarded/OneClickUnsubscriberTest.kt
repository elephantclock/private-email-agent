package tech.elephantclock.privatemail.feature.discarded

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** RFC 8058 POST against MockWebServer over HTTPS (the test certificate from `:core:mail`). */
class OneClickUnsubscriberTest {
    private val password = "changeit".toCharArray()
    private val keyStore =
        KeyStore.getInstance("PKCS12").apply {
            File(System.getProperty("pea.test.keystore")!!).inputStream().use { load(it, password) }
        }
    private val server = MockWebServer()
    private lateinit var unsubscriber: OneClickUnsubscriber

    @Before
    fun setUp() {
        val kmf =
            KeyManagerFactory
                .getInstance(
                    KeyManagerFactory.getDefaultAlgorithm(),
                ).apply { init(keyStore, password) }
        server.useHttps(SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }.socketFactory)
        server.start()
        val anchors = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        anchors.setCertificateEntry(
            "anchor",
            keyStore.getCertificate(keyStore.aliases().nextElement()) as X509Certificate,
        )
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        val client = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        unsubscriber =
            OneClickUnsubscriber(client.socketFactory, connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)
    }

    @After
    fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).toString()

    private fun respond(
        code: Int,
        location: String? = null,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Set-Cookie", "track=1")
            .apply { location?.let { addHeader("Location", it) } }
            .build(),
    )

    @Test
    fun `one POST with the RFC 8058 body and nothing that identifies the user`() {
        respond(200)
        assertEquals(UnsubscribeOutcome.DONE, unsubscriber.post(url("/unsub?u=1")))

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/unsub?u=1", request.target)
        assertEquals("List-Unsubscribe=One-Click", request.body?.utf8())
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals(OneClickUnsubscriber.USER_AGENT, request.headers["User-Agent"])
        assertNull(request.headers["Cookie"])
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["Referer"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `plain http is refused without any request`() {
        assertEquals(UnsubscribeOutcome.FAILED, unsubscriber.post(url("/unsub").replace("https://", "http://")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect to http is not followed`() {
        respond(302, location = url("/next").replace("https://", "http://"))
        assertEquals(UnsubscribeOutcome.REFUSED, unsubscriber.post(url("/unsub")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `no redirect is followed, not even to https, and the cookie it set is never sent back`() {
        respond(307, location = "/elsewhere")
        respond(200)
        assertEquals(UnsubscribeOutcome.REFUSED, unsubscriber.post(url("/unsub")))
        assertEquals(1, server.requestCount)

        assertEquals(UnsubscribeOutcome.DONE, unsubscriber.post(url("/unsub")))
        server.takeRequest(1, TimeUnit.SECONDS)
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.headers["Cookie"])
    }

    @Test
    fun `server errors are refusals and an unreachable server is a failure`() {
        respond(500)
        assertEquals(UnsubscribeOutcome.REFUSED, unsubscriber.post(url("/unsub")))
        val dead = url("/unsub")
        server.close()
        assertEquals(UnsubscribeOutcome.FAILED, unsubscriber.post(dead))
    }
}
