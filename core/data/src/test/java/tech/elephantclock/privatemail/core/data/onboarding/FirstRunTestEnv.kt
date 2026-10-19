package tech.elephantclock.privatemail.core.data.onboarding

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.ImapConnector
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.SyncTestServer
import tech.elephantclock.privatemail.core.data.settings.SentBackfillGate
import tech.elephantclock.privatemail.core.data.settings.StoredTriageSettings
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.data.triage.TriageMover
import tech.elephantclock.privatemail.core.data.triage.TriageOrchestrator
import tech.elephantclock.privatemail.core.mail.sync.SentHistoryFetcher
import java.io.Closeable
import java.nio.file.Files
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** GreenMail, an in-memory database and a DataStore in a temp file, wired like the app's first run. */
class FirstRunTestEnv(
    pageSize: Int = 200,
) : Closeable {
    val server = SyncTestServer(EMAIL)
    val db: AppDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    val accountId = server.account.id
    val repo = MailRepository(db, server.connector())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dir = Files.createTempDirectory("prefs").toFile()
    val store = TriageSettingsStore(TriageSettingsStore.create(scope) { dir.resolve("triage.json") })
    val gate = SentBackfillGate(db, store)
    val settings = StoredTriageSettings(store, gate)

    /** Every sign-in the mover makes. Before Apply this must stay 0: nothing may move. */
    val moverSignIns = AtomicInteger()
    private val realConnector = server.connector()
    val mover =
        TriageMover(
            db,
            repo,
            ImapConnector {
                moverSignIns.incrementAndGet()
                realConnector.connect(it)
            },
        )
    val labelOnly = TriageOrchestrator(db, repo, mover, settings = StoredTriageSettings.labelOnly(settings))
    val backfill = SentHistoryBackfill(db, repo, server.connector(), store, SentHistoryFetcher(pageSize = pageSize))
    val firstRun = FirstRun(db, repo, mover, labelOnly, backfill, store)

    private val session = Session.getInstance(Properties())

    suspend fun addAccount() = db.accounts().upsert(server.account.toEntity(createdAtMillis = 1))

    /** A message the user sent to [to], [daysAgo] days ago (internal date and Date header). */
    fun sent(
        to: String,
        daysAgo: Int,
        id: String = "<sent-${counter.incrementAndGet()}@ourco.example>",
    ) {
        val at = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysAgo.toLong()))
        val message =
            object : MimeMessage(session) {
                override fun updateMessageID() = setHeader("Message-ID", id)
            }.apply {
                setFrom(InternetAddress(EMAIL))
                setRecipients(jakarta.mail.Message.RecipientType.TO, to)
                subject = "Hello $to"
                sentDate = at
                setText("Hi")
                saveChanges()
            }
        server.folder("Sent").appendMessage(message, Flags(Flags.Flag.SEEN), at)
    }

    /** A one-click-unsubscribe newsletter in the Inbox: triage's rules discard it unless [from] is protected. */
    fun newsletter(
        from: String,
        subject: String,
    ) {
        val message =
            MimeMessage(session).apply {
                setFrom(InternetAddress(from))
                setRecipients(jakarta.mail.Message.RecipientType.TO, EMAIL)
                setSubject(subject)
                setHeader("List-Unsubscribe", "<https://lists.example/unsub?u=1>")
                setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click")
                sentDate = Date()
                setText("This week's offers")
                saveChanges()
            }
        server.folder("INBOX").appendMessage(message, Flags(), Date())
    }

    fun serverSubjects(path: String): List<String> =
        server.greenMail.managers.imapHostManager
            .getFolder(server.user, path)
            ?.messages
            ?.map { it.mimeMessage.subject }
            .orEmpty()

    fun serverHasFolder(path: String): Boolean =
        server.greenMail.managers.imapHostManager
            .getFolder(server.user, path) != null

    fun count(sql: String): Int = db.query(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun close() {
        db.close()
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    companion object {
        const val EMAIL = "alex@ourco.example"
        private val counter = AtomicInteger()
    }
}
