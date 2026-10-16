package tech.elephantclock.privatemail.core.mail.sync

import com.icegreen.greenmail.store.MailFolder
import jakarta.mail.Flags
import jakarta.mail.internet.MimeMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.support.TestCert
import tech.elephantclock.privatemail.core.mail.support.TestServers
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.MessageFlag
import java.util.Date
import java.util.concurrent.TimeUnit

class HeaderFetcherTest {
    private val servers = TestServers()
    private val account = AccountId("acc")
    private val now = System.currentTimeMillis()
    private val fetcher = HeaderFetcher(clock = { now }, batchSize = 50)
    private val connections = mutableListOf<ImapConnection>()

    @After
    fun tearDown() {
        connections.forEach { runCatching { it.close() } }
        servers.close()
    }

    private fun connect(): ImapConnection {
        val result =
            ImapClient(CertificatePins.None, TestCert.LOCALHOST.trustedBy())
                .connect(
                    ImapConfig("localhost", servers.imapsPort, ConnectionSecurity.SSL_TLS, TestServers.fast),
                    TestServers.good,
                )
        return ((result as MailResult.Ok).value).also { connections += it }
    }

    private fun folder(path: String): MailFolder =
        servers.greenMail.managers.imapHostManager.let { m ->
            m.getFolder(servers.user, path)
                ?: m.createMailbox(servers.user, path)
        }

    private fun MailFolder.add(
        message: MimeMessage,
        daysAgo: Long = 1,
        seen: Boolean = false,
    ) = appendMessage(
        message,
        if (seen) Flags(Flags.Flag.SEEN) else Flags(),
        Date(now - TimeUnit.DAYS.toMillis(daysAgo)),
    )

    /** 200 messages: 180 in the last 30 days (every 3rd already read), 20 older. */
    private fun seed200(path: String = "INBOX"): MailFolder =
        folder(path).apply {
            repeat(180) { i -> add(SyncFixtures.plain(i), daysAgo = (i % 29).toLong() + 1, seen = i % 3 == 0) }
            repeat(20) { i -> add(SyncFixtures.plain(1000 + i), daysAgo = 40L + i) }
        }

    @Test
    fun `first sync takes the last 30 days and leaves flags untouched`() {
        val inbox = seed200()
        val batch = fetcher.sync(connect(), account, "INBOX", previous = null)
        assertEquals(180, batch.messages.size)
        assertFalse(batch.reset)
        // All 200 UIDs were considered (20 are just older than the window), so the next sync starts above them.
        assertEquals(200L, batch.state.highestUid)
        assertEquals(inbox.uidValidity, batch.state.uidValidity)
        assertEquals(60, batch.messages.count { MessageFlag.SEEN in it.header.flags })
        // Server side: still exactly the 60 seeded as read; nothing was marked \Seen by the sync.
        assertEquals(
            60,
            inbox.messages.count {
                it.isSet(Flags.Flag.SEEN) &&
                    it.receivedDate.time > now - TimeUnit.DAYS.toMillis(30)
            },
        )
        assertEquals(
            0,
            inbox.messages.count {
                it.isSet(Flags.Flag.SEEN) &&
                    it.receivedDate.time < now - TimeUnit.DAYS.toMillis(30)
            },
        )
    }

    @Test
    fun `incremental sync fetches only new UIDs`() {
        val inbox = seed200()
        val first = fetcher.sync(connect(), account, "INBOX", previous = null)

        val nothingNew = fetcher.sync(connect(), account, "INBOX", first.state)
        assertEquals(0, nothingNew.messages.size)
        assertEquals(first.state.highestUid, nothingNew.state.highestUid)

        repeat(5) { inbox.add(SyncFixtures.plain(5000 + it), daysAgo = 0) }
        val next = fetcher.sync(connect(), account, "INBOX", first.state)
        assertEquals(5, next.messages.size)
        assertTrue(next.messages.all { it.header.ref.uid > first.state.highestUid })
        assertEquals((0 until 5).map { "Plain ${5000 + it}" }, next.messages.map { it.header.subject })
    }

    @Test
    fun `UIDVALIDITY change resets to a full window sync`() {
        val m = servers.greenMail.managers.imapHostManager
        folder("Work").apply { repeat(3) { add(SyncFixtures.plain(it)) } }
        val first = fetcher.sync(connect(), account, "Work", previous = null)
        assertEquals(3, first.messages.size)

        m.deleteMailbox(servers.user, "Work")
        Thread.sleep(1_100) // GreenMail derives UIDVALIDITY from the creation time
        folder("Work").apply { repeat(2) { add(SyncFixtures.plain(10 + it)) } }
        val after = fetcher.sync(connect(), account, "Work", first.state)
        assertNotEquals(first.state.uidValidity, after.state.uidValidity)
        assertTrue(after.reset)
        assertEquals(2, after.messages.size)
    }

    @Test
    fun `headers, addresses and snippets across formats`() {
        folder("INBOX").apply {
            add(SyncFixtures.plain(1))
            add(SyncFixtures.alternative())
            add(SyncFixtures.withAttachment())
            add(SyncFixtures.russianHtml())
            add(SyncFixtures.longBase64())
            add(SyncFixtures.latin1())
        }
        val bySubject = fetcher.sync(connect(), account, "INBOX", null).messages.associateBy { it.header.subject }

        val plain = bySubject.getValue("Plain 1")
        assertEquals("sender@shop.example", plain.header.from?.email)
        assertEquals("Шоп Sender", plain.header.from?.name)
        assertEquals(listOf("tester@localhost"), plain.header.to.map { it.email })
        assertEquals("<mailto:unsub@shop.example>", plain.header.header("List-Unsubscribe"))
        assertEquals("bulk", plain.header.header("precedence"))
        assertTrue(plain.header.header("Authentication-Results")!!.contains("dmarc=pass"))
        assertEquals(listOf("<root@shop.example>", "<prev@shop.example>"), plain.header.references)
        assertEquals("<prev@shop.example>", plain.header.inReplyTo)
        assertFalse("x-unrelated" in plain.header.headers)
        assertTrue(plain.header.messageId!!.startsWith("<"))
        assertEquals("Hello number 1", plain.body.textPreview)

        assertEquals("Plain part wins", bySubject.getValue("Alternative").body.textPreview)

        val invoice = bySubject.getValue("Invoice")
        assertEquals("See the invoice attached.", invoice.body.textPreview)
        val pdf = invoice.body.attachments.single()
        assertEquals("invoice-12.pdf", pdf.fileName)
        assertEquals("application/pdf", pdf.mimeType)
        assertEquals("2", pdf.partId)

        val ru = bySubject.getValue("Заказ")
        assertEquals("Ваш заказ готов", ru.body.textPreview)
        assertEquals("text/html", ru.body.sourceMimeType)

        val long = bySubject.getValue("Long").body
        assertTrue(long.isTruncated)
        assertTrue(long.textPreview.startsWith("Привет мир."))
        assertFalse(long.textPreview.contains('�'))
        // 4 KB of base64 is ~3 KB of UTF-8, about 1,750 characters of this text.
        assertTrue(long.textPreview.length.toString(), long.textPreview.length in 1_500..3_072)

        assertEquals("Café crème brûlée", bySubject.getValue("Latin").body.textPreview)
    }
}
