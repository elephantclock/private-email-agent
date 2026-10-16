package tech.elephantclock.privatemail.core.mail.support

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * GreenMail has no STARTTLS, so tests put this in front of its plain IMAP/SMTP port.
 * With [tls] set it advertises STARTTLS, upgrades the client socket, then pipes to the
 * backend. Without [tls] it is a plain pipe. Either way it records every byte the client
 * sent in plain text ([plaintextFromClient]) so tests can prove no password crossed the wire.
 */
class StartTlsFront(
    private val protocol: Protocol,
    private val backendPort: Int,
    private val tls: SSLContext?,
) : Closeable {
    enum class Protocol { IMAP, SMTP }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val recorded = ByteArrayOutputStream()
    val port: Int get() = server.localPort

    @Volatile var sawStartTls = false
        private set

    val plaintextFromClient: String get() = synchronized(recorded) { recorded.toString(Charsets.ISO_8859_1) }

    init {
        thread(isDaemon = true, name = "starttls-front") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    private fun serve(client: Socket) {
        val backend = Socket(InetAddress.getLoopbackAddress(), backendPort)
        if (tls == null) {
            pipe(client, backend, record = true)
            return
        }
        readLine(backend.getInputStream()) // the backend's greeting; we send our own
        val out = client.getOutputStream()
        val input = client.getInputStream()
        out.say(
            if (protocol ==
                Protocol.IMAP
            ) {
                "* OK [CAPABILITY IMAP4rev1 STARTTLS LOGINDISABLED] front"
            } else {
                "220 front ESMTP"
            },
        )
        while (true) {
            val line = readLine(input, record = true) ?: return
            val words = line.split(' ')
            if (protocol == Protocol.IMAP) {
                val tag = words[0]
                when (words.getOrNull(1)?.uppercase()) {
                    "CAPABILITY" -> out.say("* CAPABILITY IMAP4rev1 STARTTLS LOGINDISABLED\r\n$tag OK done")
                    "STARTTLS" -> return upgrade(client, backend, "$tag OK begin TLS")
                    "LOGOUT" -> return out.say("* BYE\r\n$tag OK bye").also { client.close() }
                    else -> out.say("$tag BAD STARTTLS first")
                }
            } else {
                when (words[0].uppercase()) {
                    "EHLO", "HELO" -> out.say("250-front\r\n250 STARTTLS")
                    "STARTTLS" -> return upgrade(client, backend, "220 go ahead")
                    "QUIT" -> return out.say("221 bye").also { client.close() }
                    else -> out.say("530 STARTTLS first")
                }
            }
        }
    }

    private fun upgrade(
        client: Socket,
        backend: Socket,
        reply: String,
    ) {
        client.getOutputStream().say(reply)
        sawStartTls = true
        val ssl = tls!!.socketFactory.createSocket(client, null, client.port, true) as SSLSocket
        ssl.useClientMode = false
        ssl.startHandshake()
        // After TLS the client starts over (CAPABILITY / EHLO); the backend answers from here.
        pipe(ssl, backend, record = false)
    }

    private fun pipe(
        client: Socket,
        backend: Socket,
        record: Boolean,
    ) {
        thread(isDaemon = true) { copy(backend.getInputStream(), client.getOutputStream(), false) }
        copy(client.getInputStream(), backend.getOutputStream(), record)
    }

    private fun copy(
        from: InputStream,
        to: OutputStream,
        record: Boolean,
    ) {
        val buf = ByteArray(8192)
        runCatching {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                if (record) synchronized(recorded) { recorded.write(buf, 0, n) }
                to.write(buf, 0, n)
                to.flush()
            }
        }
        runCatching { to.close() }
    }

    /** Byte-at-a-time so nothing after the line (e.g. a TLS ClientHello) is swallowed. */
    private fun readLine(
        input: InputStream,
        record: Boolean = false,
    ): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (record) synchronized(recorded) { recorded.write(b) }
            if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1).trimEnd('\r')
            line.write(b)
        }
    }

    private fun OutputStream.say(text: String) {
        write("$text\r\n".toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    override fun close() = server.close()
}
