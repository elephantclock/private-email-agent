package tech.elephantclock.privatemail.core.mail.support

import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val PASSWORD = "changeit".toCharArray()

/** The test certificates in `src/test/resources/tls` (see README there). */
enum class TestCert(
    file: String,
) {
    LOCALHOST("localhost.p12"),
    OTHER_HOST("other-host.p12"),
    ;

    private val keyStore: KeyStore =
        KeyStore.getInstance("PKCS12").apply {
            File("src/test/resources/tls/$file").inputStream().use { load(it, PASSWORD) }
        }

    val certificate: X509Certificate get() =
        keyStore.getCertificate(
            keyStore.aliases().nextElement(),
        ) as X509Certificate

    /** Server side: presents this certificate. */
    fun serverContext(): SSLContext {
        val kmf =
            KeyManagerFactory
                .getInstance(
                    KeyManagerFactory.getDefaultAlgorithm(),
                ).apply { init(keyStore, PASSWORD) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** Client side: a platform-style trust manager whose only anchor is this certificate. */
    fun trustedBy(): X509TrustManager {
        val anchors = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        anchors.setCertificateEntry("anchor", certificate)
        return PinningTrustManager.platformTrustManager(anchors)
    }
}
