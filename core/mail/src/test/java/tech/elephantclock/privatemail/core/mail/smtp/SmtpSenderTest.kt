package tech.elephantclock.privatemail.core.mail.smtp

import jakarta.mail.Flags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.support.Mailboxes
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.messageIds
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.stored
import tech.elephantclock.privatemail.core.mail.support.Mailboxes.uids
import tech.elephantclock.privatemail.core.mail.support.StartTlsFront
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.PASSWORD
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.fast
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.good
import tech.elephantclock.privatemail.core.mail.support.TestServers.Companion.wrong
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig

@OptIn(UserInitiatedSend::class)
class SmtpSenderTest {
    private val servers = TestServers()
    private val closeables = mutableListOf<AutoCloseable>(servers)
    private var now = 1_000_000L
    private val sender = SmtpSender(CertificatePins.None, TestCert.LOCALHOST.trustedBy(), clock = { now })
    private val smtps get() = ServerConfig("localhost", servers.smtpsPort, ConnectionSecurity.SSL_TLS)

    private val me = Address.of(TestServers.EMAIL, "Tester")
    private val bob = Address.of("bob@example.org", "Bob")
    private val carol = Address.of("carol@example.org")
    private val dave = Address.of("dave@example.org")

    @After
    fun tearDown() = closeables.reversed().forEach { runCatching { it.close() } }

    private fun imap(): ImapConnection = Mailboxes.connect(servers).also { closeables += it }

    private fun reply() =
        OutgoingMessage(
            from = me,
            subject = "Re: Привет",
            text = "Thanks, see you — Tester",
            to = listOf(bob),
            cc = listOf(carol),
            bcc = listOf(dave),
            parent = ReplyParent("<p@example.org>", listOf("<root@example.org>"), "<root@example.org>"),
        )

    private fun approve(message: OutgoingMessage) = SendApproval(message, clock = { now })

    private fun send(
        message: OutgoingMessage = reply(),
        approval: SendApproval = approve(message),
        server: ServerConfig = smtps,
        imap: ImapConnection? = imap(),
        providerId: String? = null,
    ) = sender.send(message, approval, server, good, imap, providerId, fast)

    /** What landed in [address]'s INBOX (GreenMail creates recipients on delivery). */
    private fun inbox(address: Address) =
        servers.greenMail.managers.userManager
            .getUserByEmail(address.email)
            ?.let {
                servers.greenMail.managers.imapHostManager
                    .getInbox(it)
            }?.let { folder -> folder.uids().map { folder.stored(it).mimeMessage } }
            .orEmpty()

    private fun deliveredCount() = listOf(bob, carol, dave).sumOf { inbox(it).size }

    @Test
    fun `reply is delivered with threading headers and the returned Message-ID`() {
        val message = reply()
        val result = send(message) as SendResult.Sent
        assertEquals(message.messageId, result.messageId)
        val delivered = listOf(bob, carol, dave).map { inbox(it).single() }
        delivered.forEach { m ->
            assertEquals(message.messageId, m.messageID)
            assertEquals("<p@example.org>", m.getHeader("In-Reply-To")?.single())
            assertEquals(
                "<root@example.org> <p@example.org>",
                m.getHeader("References")?.single()?.replace(Regex("\\s+"), " "),
            )
            assertEquals("Re: Привет", m.subject)
            assertNull("Bcc must never be in delivered headers", m.getHeader("Bcc"))
        }
    }

    @Test
    fun `a copy goes to Sent, marked read, with the same Message-ID and the Bcc`() {
        val message = reply()
        val result = send(message) as SendResult.Sent
        val copy = (result.sentCopy as SentCopy.Saved).stored
        assertEquals("Sent", copy.folderPath)
        val sent = Mailboxes.folder(servers, "Sent")
        assertEquals(listOf(message.messageId), sent.messageIds())
        val stored = sent.stored(sent.uids().single())
        assertEquals(stored.uid, copy.uid)
        assertEquals(sent.uidValidity, copy.uidValidity)
        assertTrue(stored.isSet(Flags.Flag.SEEN))
        assertEquals(
            dave.email,
            stored.mimeMessage
                .getHeader("Bcc")
                ?.single()
                ?.substringAfter('<')
                ?.trimEnd('>'),
        )
    }

    @Test
    fun `existing Sent folder is used, nothing else is created`() {
        servers.createFolder("Sent Items")
        val result = send() as SendResult.Sent
        assertEquals("Sent Items", (result.sentCopy as SentCopy.Saved).stored.folderPath)
        assertFalse(Mailboxes.exists(servers, "Sent"))
    }

    @Test
    fun `Gmail and Microsoft accounts get no second Sent copy`() {
        val result = send(providerId = "gmail") as SendResult.Sent
        assertEquals(SentCopy.SavedByProvider, result.sentCopy)
        assertFalse(Mailboxes.exists(servers, "Sent"))
        assertEquals(3, deliveredCount())
    }

    @Test
    fun `without an IMAP connection the copy is skipped, the mail still goes`() {
        assertEquals(SentCopy.Skipped, (send(imap = null) as SendResult.Sent).sentCopy)
    }

    @Test
    fun `STARTTLS works and the password crosses only after TLS`() {
        val front = StartTlsFront(StartTlsFront.Protocol.SMTP, servers.smtpPort, TestCert.LOCALHOST.serverContext())
        closeables += front
        val result = send(server = ServerConfig("localhost", front.port, ConnectionSecurity.STARTTLS), imap = null)
        assertTrue(result is SendResult.Sent)
        assertTrue(front.sawStartTls)
        assertFalse(front.plaintextFromClient.contains(PASSWORD))
        // EHLO never names the device.
        assertTrue(front.plaintextFromClient, front.plaintextFromClient.contains("EHLO [127.0.0.1]"))
    }

    @Test
    fun `wrong password sends nothing and never leaks the password`() {
        val message = reply()
        val result = sender.send(message, approve(message), smtps, wrong, imap(), null, fast)
        assertEquals(SendResult.NotSent(SendError.Connection(AuthError.WrongCredentials)), result)
        assertFalse(result.toString().contains(wrong.password.reveal()))
        assertEquals(0, deliveredCount())
        assertFalse(Mailboxes.exists(servers, "Sent"))
    }

    @Test
    fun `an approval sends once only`() {
        val message = reply()
        val approval = approve(message)
        assertTrue(send(message, approval) is SendResult.Sent)
        assertEquals(SendResult.NotSent(SendError.NotApproved("approval already used")), send(message, approval))
        assertEquals(3, deliveredCount())
    }

    @Test
    fun `an approval for other content sends nothing`() {
        val approved = reply()
        val edited = approved.copy(text = "Something the user never saw")
        assertEquals(
            SendResult.NotSent(SendError.NotApproved("message changed after approval")),
            send(edited, approve(approved)),
        )
        assertEquals(0, deliveredCount())
    }

    @Test
    fun `an old approval sends nothing`() {
        val message = reply()
        val approval = approve(message)
        now += SendApproval.DEFAULT_VALIDITY_MILLIS + 1
        assertEquals(SendResult.NotSent(SendError.NotApproved("approval expired")), send(message, approval))
        assertEquals(0, deliveredCount())
    }

    @Test
    fun `no recipients is refused before contacting anyone`() {
        val message = OutgoingMessage(me, "Note", "to self")
        assertEquals(SendResult.NotSent(SendError.NoRecipients), send(message))
    }

    @Test
    fun `unreachable server reports a connection error`() {
        val result = send(server = ServerConfig("localhost", TestServers.closedPort(), ConnectionSecurity.SSL_TLS))
        assertTrue(result.toString(), (result as SendResult.NotSent).error is SendError.Connection)
    }
}
