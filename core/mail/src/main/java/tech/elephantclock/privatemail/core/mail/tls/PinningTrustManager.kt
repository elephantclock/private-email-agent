package tech.elephantclock.privatemail.core.mail.tls

import android.annotation.SuppressLint
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Accepts a server if the platform trusts its chain and the leaf matches the host,
 * or if the user pinned that exact leaf for that host and port. Records the
 * rejected certificate so the caller can offer to pin it.
 *
 * Lint's CustomX509TrustManager warning is suppressed on purpose: every check still goes
 * through the platform trust manager ([base]) first, and the only addition is an exact
 * SHA-256 pin the user approved for one host and port. Network Security Config can't hold
 * pins chosen at runtime, so a wrapper is the only way to offer "trust this certificate".
 * Tests: ImapClientTest, SmtpAuthCheckTest (untrusted, wrong host, stale pin, STARTTLS path).
 */
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(
    private val pins: CertificatePins,
    private val base: X509TrustManager = platformTrustManager(),
) : X509ExtendedTrustManager() {
    private val rejected = AtomicReference<CertificateInfo?>(null)

    /** The last certificate this trust manager refused, if any. */
    val lastRejected: CertificateInfo? get() = rejected.get()

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket?,
    ) {
        val session = (socket as? SSLSocket)?.handshakeSession
        verify(chain, authType, session?.peerHost ?: socket?.inetAddress?.hostName, session?.peerPort ?: -1)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = verify(chain, authType, engine?.peerHost, engine?.peerPort ?: -1)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = verify(chain, authType, null, -1)

    private fun verify(
        chain: Array<out X509Certificate>,
        authType: String,
        host: String?,
        port: Int,
    ) {
        if (host == null) throw CertificateException("Server host unknown; refusing to trust")
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
        if (pins.isPinned(host, port, sha256Fingerprint(leaf))) return

        val reason =
            try {
                base.checkServerTrusted(chain, authType)
                if (HostnameChecker.matches(host, leaf)) null else UntrustedReason.HOSTNAME
            } catch (e: CertificateException) {
                if (e.isExpiry()) UntrustedReason.EXPIRED else UntrustedReason.CHAIN
            }
        if (reason != null) {
            rejected.set(CertificateInfo.of(host, port, leaf, reason))
            throw CertificateException("Untrusted certificate for $host:$port ($reason)")
        }
    }

    private fun Throwable.isExpiry(): Boolean =
        generateSequence(this) { it.cause }.any {
            it is CertificateExpiredException || it is CertificateNotYetValidException
        }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        socket: Socket?,
    ) = throw CertificateException("Client certificates are not used")

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) = throw CertificateException("Client certificates are not used")

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = throw CertificateException("Client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers

    companion object {
        fun platformTrustManager(keyStore: KeyStore? = null): X509TrustManager =
            TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
    }
}
