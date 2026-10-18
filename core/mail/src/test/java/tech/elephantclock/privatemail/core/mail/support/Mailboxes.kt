package tech.elephantclock.privatemail.core.mail.support

import com.icegreen.greenmail.store.MailFolder
import com.icegreen.greenmail.store.StoredMessage
import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import java.util.Date
import java.util.Properties

/** Server-side views and seeding helpers for tests that write to GreenMail mailboxes. */
object Mailboxes {
    private val session = Session.getInstance(Properties())

    fun connect(servers: TestServers): ImapConnection {
        val result =
            ImapClient(CertificatePins.None, TestCert.LOCALHOST.trustedBy())
                .connect(
                    ImapConfig("localhost", servers.imapsPort, ConnectionSecurity.SSL_TLS, TestServers.fast),
                    TestServers.good,
                )
        return (result as MailResult.Ok).value
    }

    fun folder(
        servers: TestServers,
        path: String,
    ): MailFolder =
        servers.greenMail.managers.imapHostManager.let { m ->
            m.getFolder(servers.user, path) ?: m.createMailbox(servers.user, path)
        }

    fun exists(
        servers: TestServers,
        path: String,
    ): Boolean =
        servers.greenMail.managers.imapHostManager
            .getFolder(servers.user, path) != null

    /** A small message whose Message-ID is `<[tag]@test.example>`. */
    fun message(tag: String): MimeMessage =
        object : MimeMessage(session) {
            override fun updateMessageID() = setHeader("Message-ID", "<$tag@test.example>")
        }.apply {
            setFrom(InternetAddress("sender@shop.example"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, TestServers.EMAIL)
            subject = "Message $tag"
            sentDate = Date()
            setText("Body of $tag", "UTF-8")
            saveChanges()
        }

    /** Appends one message per tag; returns their UIDs in order. */
    fun seed(
        folder: MailFolder,
        tags: List<String>,
        flags: Flags = Flags(),
    ): List<Long> = tags.map { folder.appendMessage(message(it), flags, Date()) }

    fun MailFolder.uids(): List<Long> = messageUids.toList().sorted()

    fun MailFolder.stored(uid: Long): StoredMessage = requireNotNull(getMessage(uid)) { "no UID $uid in $fullName" }

    fun MailFolder.messageIds(): List<String> = uids().map { stored(it).mimeMessage.messageID }

    fun MailFolder.byMessageId(id: String): StoredMessage? =
        uids().map { stored(it) }.firstOrNull { it.mimeMessage.messageID == id }
}
