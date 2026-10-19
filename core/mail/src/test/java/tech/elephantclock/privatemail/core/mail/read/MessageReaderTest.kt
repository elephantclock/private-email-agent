package tech.elephantclock.privatemail.core.mail.read

import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.elephantclock.privatemail.core.mail.support.Mailboxes
import tech.elephantclock.privatemail.core.mail.support.TestServers
import java.io.File
import java.util.Date
import java.util.Properties

class MessageReaderTest {
    private val servers = TestServers()
    private val connection = Mailboxes.connect(servers)
    private val inbox = Mailboxes.folder(servers, "INBOX")
    private val session = Session.getInstance(Properties())
    private val pdf = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @get:Rule
    val temp = TemporaryFolder()
    private val dir: File get() = temp.root

    @After
    fun tearDown() {
        runCatching { connection.close() }
        servers.close()
    }

    private fun base() =
        MimeMessage(session).apply {
            setFrom(InternetAddress("sender@shop.example"))
            setRecipients(jakarta.mail.Message.RecipientType.TO, TestServers.EMAIL)
            subject = "Invoice"
            sentDate = Date()
        }

    private fun append(message: MimeMessage): Long {
        message.saveChanges()
        return inbox.appendMessage(message, Flags(), Date())
    }

    private fun withPdf(): Long =
        append(
            base().apply {
                setContent(
                    MimeMultipart(
                        MimeBodyPart().apply { setText("Hello, the invoice is attached.", "UTF-8") },
                        MimeBodyPart().apply {
                            setContent(pdf, "application/pdf")
                            fileName = "invoice.pdf"
                            disposition = "attachment"
                            setHeader("Content-Transfer-Encoding", "base64")
                        },
                    ),
                )
            },
        )

    private fun seen(uid: Long) = inbox.getMessage(uid).flags.contains(Flags.Flag.SEEN)

    @Test
    fun `reads the whole text and an attachment without marking the message read`() {
        val uid = withPdf()
        val body = MessageReader.body(connection, "INBOX", inbox.uidValidity, uid)
        assertEquals(ReadResult.Found(FullText("Hello, the invoice is attached.", false, false)), body)

        val target = File(dir, "invoice.pdf")
        val result = MessageReader.attachment(connection, "INBOX", inbox.uidValidity, uid, "2", target)
        assertEquals(ReadResult.Found(pdf.size.toLong()), result)
        assertArrayEquals(pdf, target.readBytes())
        assertEquals("the staged raw file is removed", listOf("invoice.pdf"), dir.list()!!.toList())
        assertFalse(seen(uid))
    }

    @Test
    fun `HTML becomes plain text, without scripts, styles or remote images`() {
        val uid =
            append(
                base().apply {
                    setContent(
                        "<html><head><style>p{}</style><script>alert(1)</script></head><body><p>Hi&nbsp;there</p>" +
                            "<img src=\"https://tracker.example/p.gif\">" +
                            "<a href=\"https://x.example\">Pay now</a></body></html>",
                        "text/html; charset=UTF-8",
                    )
                },
            )
        val text = (MessageReader.body(connection, "INBOX", inbox.uidValidity, uid) as ReadResult.Found).value
        assertTrue(text.fromHtml)
        assertEquals("Hi there\nPay now", text.text)
    }

    @Test
    fun `long text is cut at the limit and says so`() {
        val uid = append(base().apply { setText("a".repeat(5_000), "UTF-8") })
        val text =
            (
                MessageReader.body(
                    connection,
                    "INBOX",
                    inbox.uidValidity,
                    uid,
                    maxBytes = 1_000,
                ) as ReadResult.Found
            ).value
        assertTrue(text.truncated)
        // 1,000 encoded bytes; quoted-printable soft breaks don't count as text.
        assertTrue(text.text.length in 900..1_000)
        assertTrue(text.text.all { it == 'a' })
    }

    @Test
    fun `a changed UIDVALIDITY or a missing UID reads nothing`() {
        val uid = withPdf()
        assertEquals(ReadResult.Gone, MessageReader.body(connection, "INBOX", inbox.uidValidity + 1, uid))
        assertEquals(ReadResult.Gone, MessageReader.body(connection, "INBOX", inbox.uidValidity, uid + 50))
        val target = File(dir, "x")
        assertEquals(
            ReadResult.Gone,
            MessageReader.attachment(connection, "INBOX", inbox.uidValidity + 1, uid, "2", target),
        )
        assertFalse(target.exists())
    }

    @Test
    fun `unknown, malformed and oversized parts are refused before any download`() {
        val uid = withPdf()
        val target = File(dir, "x")
        val v = inbox.uidValidity
        assertEquals(ReadResult.NoSuchPart, MessageReader.attachment(connection, "INBOX", v, uid, "7", target))
        assertEquals(ReadResult.NoSuchPart, MessageReader.attachment(connection, "INBOX", v, uid, "2] FLAGS (", target))
        assertEquals(ReadResult.NoSuchPart, MessageReader.attachment(connection, "INBOX", v, uid, "TEXT", target))
        assertTrue(
            MessageReader.attachment(connection, "INBOX", v, uid, "2", target, maxBytes = 1_000) is ReadResult.TooLarge,
        )
        assertFalse(target.exists())
        assertFalse(seen(uid))
    }
}
