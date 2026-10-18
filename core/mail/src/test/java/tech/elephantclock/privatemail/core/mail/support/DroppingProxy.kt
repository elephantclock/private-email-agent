package tech.elephantclock.privatemail.core.mail.support

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP pipe in front of a test server that can fail the way mobile networks do:
 * [dropAll] resets every connection (the server went away), [freeze] stops forwarding
 * without closing anything (a dead NAT mapping: nothing arrives, nothing errors).
 */
class DroppingProxy(
    private val backendPort: Int,
) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    val port: Int get() = server.localPort

    @Volatile var frozen = false
        private set

    @Volatile var accepted = 0
        private set

    init {
        thread(isDaemon = true, name = "dropping-proxy") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                accepted++
                val backend = Socket(InetAddress.getLoopbackAddress(), backendPort)
                sockets += client
                sockets += backend
                thread(isDaemon = true) { copy(client.getInputStream(), backend.getOutputStream()) }
                thread(isDaemon = true) { copy(backend.getInputStream(), client.getOutputStream()) }
            }
        }
    }

    fun dropAll() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    fun freeze() {
        frozen = true
    }

    private fun copy(
        from: InputStream,
        to: OutputStream,
    ) {
        val buf = ByteArray(8192)
        runCatching {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                // Frozen: swallow everything, like a connection whose NAT mapping has gone.
                if (frozen) continue
                to.write(buf, 0, n)
                to.flush()
            }
        }
        runCatching { to.close() }
    }

    override fun close() {
        server.close()
        dropAll()
    }
}
