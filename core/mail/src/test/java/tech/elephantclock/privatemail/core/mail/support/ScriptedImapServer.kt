package tech.elephantclock.privatemail.core.mail.support

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * A tiny implicit-TLS IMAP server with CONDSTORE, which GreenMail lacks. It knows one folder
 * (INBOX) and only the read-only commands the sync uses; anything else gets `BAD` and is
 * recorded, so tests can prove nothing writes. Mutate [messages], [uidValidity] and
 * [highestModSeq] between sessions to play "another client".
 */
class ScriptedImapServer(
    private val capabilities: String = "IMAP4rev1 AUTH=PLAIN ENABLE CONDSTORE IDLE",
    /** While a client idles, send `* OK Still here` this often (Dovecot does, every 2 minutes); 0 = never. */
    private val keepaliveMillis: Long = 0,
) : Closeable {
    data class Msg(
        val uid: Long,
        val flags: String,
        val modSeq: Long,
    )

    @Volatile var uidValidity = 7L

    @Volatile var highestModSeq = 0L
    val messages = CopyOnWriteArrayList<Msg>()

    /** Every command line received (passwords never appear: AUTHENTICATE data is not recorded). */
    val commands = CopyOnWriteArrayList<String>()

    private val server =
        (
            TestCert.LOCALHOST.serverContext().serverSocketFactory.createServerSocket(
                0,
                50,
                InetAddress.getLoopbackAddress(),
            )
                as SSLServerSocket
        )
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "scripted-imap") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { client.use { serve(it) } } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val out = socket.getOutputStream()
        out.say("* OK [CAPABILITY $capabilities] scripted ready")
        while (true) {
            val line = input.readLine() ?: return
            val tag = line.substringBefore(' ')
            val command = line.substringAfter(' ')
            commands += command
            val verb = command.substringBefore(' ').uppercase()
            when {
                verb == "CAPABILITY" -> {
                    out.say("* CAPABILITY $capabilities\r\n$tag OK done")
                }

                verb == "AUTHENTICATE" -> {
                    if (command.trim().split(' ').size < 3) {
                        out.say("+ ")
                        input.readLine()
                    }
                    out.say("$tag OK [CAPABILITY $capabilities] signed in")
                }

                verb == "ENABLE" -> {
                    out.say("* ENABLED ${command.substringAfter(' ')}\r\n$tag OK enabled")
                }

                verb == "EXAMINE" -> {
                    out.say(examine(tag))
                }

                command.uppercase().startsWith("UID FETCH") -> {
                    out.say(uidFetch(tag, command))
                }

                verb == "FETCH" -> {
                    out.say(seqFetch(tag, command))
                }

                verb == "IDLE" -> {
                    idle(tag, input, out)
                }

                verb == "NOOP" || verb == "CLOSE" || verb == "UNSELECT" -> {
                    out.say("$tag OK done")
                }

                verb == "LOGOUT" -> {
                    out.say("* BYE bye\r\n$tag OK done")
                    return
                }

                else -> {
                    out.say("$tag BAD not in this script")
                }
            }
        }
    }

    private fun examine(tag: String): String {
        val next = (messages.maxOfOrNull { it.uid } ?: 0) + 1
        return listOf(
            "* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)",
            "* ${messages.size} EXISTS",
            "* 0 RECENT",
            "* OK [UIDVALIDITY $uidValidity] ok",
            "* OK [UIDNEXT $next] ok",
            "* OK [HIGHESTMODSEQ $highestModSeq] ok",
            "$tag OK [READ-ONLY] EXAMINE done",
        ).joinToString("\r\n")
    }

    /** `UID FETCH a:b (UID)`, `UID FETCH a:b (FLAGS)` and `UID FETCH a:b (FLAGS) (CHANGEDSINCE m)`. */
    private fun uidFetch(
        tag: String,
        command: String,
    ): String {
        val parts = command.split(' ')
        val (lo, hi) = parts[2].split(':').map { it.toLong() }
        val changedSince =
            Regex("CHANGEDSINCE (\\d+)")
                .find(command)
                ?.groupValues
                ?.get(1)
                ?.toLong()
        val wantFlags = "FLAGS" in command.uppercase()
        val lines =
            messages
                .withIndex()
                .filter { (_, m) -> m.uid in lo..hi && (changedSince == null || m.modSeq > changedSince) }
                .map { (i, m) ->
                    val items =
                        buildList {
                            add("UID ${m.uid}")
                            if (wantFlags) add("FLAGS (${m.flags})")
                            if (changedSince != null) add("MODSEQ (${m.modSeq})")
                        }
                    "* ${i + 1} FETCH (${items.joinToString(" ")})"
                }
        return (lines + "$tag OK FETCH done").joinToString("\r\n")
    }

    /** `+ idling`, keepalives until the client sends DONE, then the tagged OK. */
    private fun idle(
        tag: String,
        input: BufferedReader,
        out: OutputStream,
    ) {
        out.say("+ idling")
        val keepalive =
            keepaliveMillis.takeIf { it > 0 }?.let { every ->
                thread(isDaemon = true) {
                    runCatching {
                        while (true) {
                            Thread.sleep(every)
                            out.say("* OK Still here")
                        }
                    }
                }
            }
        val done = input.readLine()
        keepalive?.interrupt()
        keepalive?.join()
        if (done != null) commands += done
        out.say("$tag OK IDLE done")
    }

    /** `FETCH 1:3,5 (FLAGS)` by sequence number. */
    private fun seqFetch(
        tag: String,
        command: String,
    ): String {
        val wanted =
            command.split(' ')[1].split(',').flatMap { part ->
                val ends = part.split(':').map { it.toInt() }
                (ends.first()..ends.last()).toList()
            }
        val lines =
            wanted.mapNotNull { n ->
                messages.getOrNull(n - 1)?.let { "* $n FETCH (UID ${it.uid} FLAGS (${it.flags}))" }
            }
        return (lines + "$tag OK FETCH done").joinToString("\r\n")
    }

    private fun OutputStream.say(text: String) =
        synchronized(this) {
            write("$text\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }

    override fun close() = server.close()
}
