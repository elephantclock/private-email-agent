package tech.elephantclock.privatemail.core.data.repo

import com.icegreen.greenmail.store.MailFolder
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import java.util.Date
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

/** GreenMail (plain IMAP for "another client", implicit TLS for the app) with one account. */
class SyncTestServer : Closeable {
    val greenMail =
        GreenMail(arrayOf(ServerSetupTest.IMAP.dynamicPort(), ServerSetupTest.IMAPS.dynamicPort())).apply { start() }
    val user = greenMail.setUser(EMAIL, EMAIL, PASSWORD)
    private val session = Session.getInstance(Properties())

    val account =
        Account(
            id = AccountId("acc-sync"),
            email = Address.of(EMAIL),
            displayName = "Test",
            username = EMAIL,
            imap = ServerConfig("localhost", greenMail.imaps.port, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("localhost", 465, ConnectionSecurity.SSL_TLS),
        )

    /** Sign-in attempts made through [connector]. */
    val connects = AtomicInteger()

    private val trust =
        PinningTrustManager.platformTrustManager(
            KeyStore.getInstance("PKCS12").let { p12 ->
                File(
                    System.getProperty("pea.test.keystore")!!,
                ).inputStream().use { p12.load(it, "changeit".toCharArray()) }
                KeyStore.getInstance("PKCS12").apply {
                    load(null, null)
                    setCertificateEntry("anchor", p12.getCertificate(p12.aliases().nextElement()))
                }
            },
        )

    fun connector(password: String = PASSWORD): ImapConnector =
        ImapConnector { account ->
            connects.incrementAndGet()
            ImapClient(CertificatePins.None, trust).connect(
                ImapConfig("localhost", account.imap.port, ConnectionSecurity.SSL_TLS, Timeouts(2_000, 2_000)),
                MailCredentials(account.username, Password(password)),
            )
        }

    fun folder(path: String): MailFolder =
        greenMail.managers.imapHostManager.let { it.getFolder(user, path) ?: it.createMailbox(user, path) }

    fun message(
        subject: String,
        received: Date = Date(),
    ): MimeMessage =
        MimeMessage(session).apply {
            setFrom(InternetAddress("sender@shop.example"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, EMAIL)
            setSubject(subject)
            sentDate = received
            setText("Body of $subject")
            saveChanges()
        }

    fun append(
        path: String,
        subject: String,
        flags: Flags = Flags(),
    ) {
        folder(path).appendMessage(message(subject), flags, Date())
    }

    /** Another mail client, read-write over plain IMAP. */
    fun otherClient(
        path: String = "INBOX",
        block: (Folder, UIDFolder) -> Unit,
    ) {
        val store = session.getStore("imap")
        store.connect("localhost", greenMail.imap.port, EMAIL, PASSWORD)
        try {
            val folder = store.getFolder(path).apply { open(Folder.READ_WRITE) }
            block(folder, folder as UIDFolder)
            folder.close(true)
        } finally {
            store.close()
        }
    }

    override fun close() = greenMail.stop()

    companion object {
        const val EMAIL = "sync@localhost"
        const val PASSWORD = "canary-Sync-Pa55-4e1b"
    }
}
