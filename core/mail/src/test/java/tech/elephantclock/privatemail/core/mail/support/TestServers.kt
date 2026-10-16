package tech.elephantclock.privatemail.core.mail.support

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket

/** GreenMail with plain and implicit-TLS IMAP and SMTP on free ports, and one user. */
class TestServers : Closeable {
    val greenMail =
        GreenMail(
            arrayOf(
                ServerSetupTest.IMAP.dynamicPort(),
                ServerSetupTest.IMAPS.dynamicPort(),
                ServerSetupTest.SMTP.dynamicPort(),
                ServerSetupTest.SMTPS.dynamicPort(),
            ),
        ).apply { start() }

    val user = greenMail.setUser(EMAIL, EMAIL, PASSWORD)

    val imapPort get() = greenMail.imap.port
    val imapsPort get() = greenMail.imaps.port
    val smtpPort get() = greenMail.smtp.port
    val smtpsPort get() = greenMail.smtps.port

    fun createFolder(path: String) {
        greenMail.managers.imapHostManager.createMailbox(user, path)
    }

    override fun close() = greenMail.stop()

    companion object {
        const val EMAIL = "tester@localhost"

        /** Distinctive, so tests can search logs and wire captures for it. */
        const val PASSWORD = "canary-Pa55w0rd-7f3a"

        val good = MailCredentials(EMAIL, Password(PASSWORD))
        val wrong = MailCredentials(EMAIL, Password("wrong-canary-Pa55w0rd-9c1d"))
        val fast = Timeouts(connectMillis = 2_000, readMillis = 2_000)

        /** A port with nothing listening. */
        fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    }
}
