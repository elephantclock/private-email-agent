package tech.elephantclock.privatemail.core.mail.tls

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Socket factory for implicit TLS and STARTTLS (the layered [createSocket] overload)
 * that routes every handshake through one [PinningTrustManager] and allows TLS 1.2+ only.
 */
class TlsSocketFactory(
    val trustManager: PinningTrustManager,
) : SSLSocketFactory() {
    private val delegate: SSLSocketFactory =
        SSLContext
            .getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), null) }
            .socketFactory

    private fun Socket.secured(): Socket =
        apply {
            (this as? SSLSocket)?.let { ssl ->
                ssl.enabledProtocols =
                    ssl.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            }
        }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(): Socket = delegate.createSocket().secured()

    override fun createSocket(
        s: Socket,
        host: String,
        port: Int,
        autoClose: Boolean,
    ): Socket = delegate.createSocket(s, host, port, autoClose).secured()

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = delegate.createSocket(host, port).secured()

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = delegate.createSocket(host, port, localHost, localPort).secured()

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = delegate.createSocket(host, port).secured()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = delegate.createSocket(address, port, localAddress, localPort).secured()
}
