package tech.elephantclock.privatemail.core.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Flags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.smtp.DraftStore
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.ReplyParent
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.SendError
import tech.elephantclock.privatemail.core.mail.smtp.SendResult
import tech.elephantclock.privatemail.core.mail.smtp.SentCopy
import tech.elephantclock.privatemail.core.mail.smtp.SmtpSender
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.mail.tls.PinningTrustManager
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger

/** Draft save and send through the repository, against GreenMail over implicit TLS (IMAPS + SMTPS). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(UserInitiatedSend::class) // tests may opt in; app code only in the confirmed Send handler
class OutgoingMailRepositoryTest {
    private val greenMail =
        GreenMail(arrayOf(ServerSetupTest.IMAPS.dynamicPort(), ServerSetupTest.SMTPS.dynamicPort())).apply { start() }
    private val user = greenMail.setUser(EMAIL, EMAIL, PASSWORD)
    private val account =
        Account(
            id = AccountId("acc-send"),
            email = Address.of(EMAIL, "Sam"),
            displayName = "Test",
            username = EMAIL,
            imap = ServerConfig("localhost", greenMail.imaps.port, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("localhost", greenMail.smtps.port, ConnectionSecurity.SSL_TLS),
        )
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
    private val connects = AtomicInteger()
    private val connector =
        ImapConnector { a ->
            connects.incrementAndGet()
            ImapClient(CertificatePins.None, trust).connect(
                ImapConfig("localhost", a.imap.port, ConnectionSecurity.SSL_TLS, Timeouts(2_000, 2_000)),
                MailCredentials(a.username, Password(PASSWORD)),
            )
        }
    private val db =
        Room
            .inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AppDatabase::class.java,
            ).build()
    private val repo =
        OutgoingMailRepository(db, connector, passwords = {
            Password(PASSWORD)
        }, sender = SmtpSender(CertificatePins.None, trust), DraftStore())
    private val bob = Address.of("bob@localhost", "Bob")

    @After
    fun tearDown() {
        db.close()
        greenMail.stop()
    }

    private fun reply(text: String = "Thanks, Tuesday works.") =
        OutgoingMessage(
            from = account.email,
            subject = "Re: Chatbot for our hotels",
            text = text,
            to = listOf(bob),
            parent = ReplyParent("<parent@sender.example>"),
            messageId = "<reply-1@localhost>",
        )

    private fun folder(path: String) = greenMail.managers.imapHostManager.getFolder(user, path)

    private fun messages(path: String) = folder(path)?.messages.orEmpty()

    /** What reached Bob's Inbox (GreenMail creates him on delivery); drafts and Sent copies don't count. */
    private fun delivered() =
        greenMail.managers.userManager
            .getUserByEmail("bob@localhost")
            ?.let {
                greenMail.managers.imapHostManager
                    .getInbox(it)
                    .messages
                    .map { m -> m.mimeMessage }
            }.orEmpty()

    private fun withAccount() = runBlocking { db.accounts().upsert(account.toEntity(createdAtMillis = 1)) }

    @Test
    fun `a draft is saved, a second save replaces it, and sending delivers, files Sent and removes the draft`() =
        runBlocking {
            withAccount()
            val first = repo.saveDraft(account.id, reply("Thanks,"), replacing = null) as DraftOutcome.Saved
            val second = repo.saveDraft(account.id, reply(), replacing = first.stored) as DraftOutcome.Saved
            val drafts = messages(second.stored.folderPath).filterNot { it.isSet(Flags.Flag.DELETED) }
            assertEquals(1, drafts.size)
            assertTrue(drafts.single().isSet(Flags.Flag.DRAFT))

            val message = reply()
            val outcome = repo.send(account.id, message, SendApproval(message), second.stored) as SendOutcome.Done
            val sent = outcome.result as SendResult.Sent
            assertEquals("<reply-1@localhost>", sent.messageId)
            assertTrue(sent.sentCopy is SentCopy.Saved)
            assertTrue(outcome.draftRemoved)

            val delivered = delivered().single()
            assertEquals("Re: Chatbot for our hotels", delivered.subject)
            assertEquals("<parent@sender.example>", delivered.getHeader("In-Reply-To").single())
            assertEquals(0, messages(second.stored.folderPath).count { !it.isSet(Flags.Flag.DELETED) })
        }

    @Test
    fun `an approval for other text sends nothing`() =
        runBlocking {
            withAccount()
            val approved = reply("Yes.")
            val outcome = repo.send(account.id, reply("No."), SendApproval(approved), draft = null) as SendOutcome.Done
            assertTrue((outcome.result as SendResult.NotSent).error is SendError.NotApproved)
            assertEquals(0, delivered().size)
        }

    @Test
    fun `an account waiting for its password is never contacted`() =
        runBlocking {
            withAccount()
            db.accounts().setAuthProblem(account.id.value, "WrongCredentials")
            val message = reply()
            assertEquals(DraftOutcome.NeedsSignIn, repo.saveDraft(account.id, message, null))
            assertEquals(SendOutcome.NeedsSignIn, repo.send(account.id, message, SendApproval(message), null))
            assertEquals(0, connects.get())
            assertEquals(0, delivered().size)
        }

    @Test
    fun `a lost connection is a connection error, which the screen words as may not have been sent`() =
        runBlocking {
            val dead = account.copy(smtp = ServerConfig("localhost", 1, ConnectionSecurity.SSL_TLS))
            db.accounts().upsert(dead.toEntity(createdAtMillis = 1))
            val message = reply()
            val outcome = repo.send(account.id, message, SendApproval(message), null) as SendOutcome.Done
            assertTrue((outcome.result as SendResult.NotSent).error is SendError.Connection)
            assertTrue(connector.connect(account) is MailResult.Ok)
        }

    private companion object {
        const val EMAIL = "sam@localhost"
        const val PASSWORD = "canary-Send-Pa55-2c9d"
    }
}
