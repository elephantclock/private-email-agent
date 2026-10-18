package tech.elephantclock.privatemail.core.mail.tls

import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.WeakHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Socket factory for implicit TLS and STARTTLS (the layered [createSocket] overload)
 * that routes every handshake through one [PinningTrustManager] and allows TLS 1.2+ only.
 * It remembers the sockets it made, so [closeAll] can unblock a thread stuck in a read
 * (IMAP IDLE, a dead connection) without waiting for the server.
 */
class TlsSocketFactory(
    val trustManager: PinningTrustManager,
) : SSLSocketFactory() {
    private val delegate: SSLSocketFactory =
        SSLContext
            .getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), null) }
            .socketFactory

    private val created: MutableSet<Socket> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Closes every socket made by this factory. A blocked read on one of them fails at once. */
    fun closeAll() {
        val sockets = synchronized(created) { created.toList().also { created.clear() } }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun Socket.secured(): Socket =
        apply {
            created += this
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
    ): Socket {
        // STARTTLS: closing the plain socket underneath is what unblocks a reader.
        created += s
        return delegate.createSocket(s, host, port, autoClose).secured()
    }

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
