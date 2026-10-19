package tech.elephantclock.privatemail.core.data.settings

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
import tech.elephantclock.privatemail.core.data.onboarding.SentHistoryBackfill
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.SyncTestServer
import tech.elephantclock.privatemail.core.data.triage.TriageMover
import tech.elephantclock.privatemail.core.data.triage.TriageOrchestrator
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.triage.rules.LearnedRules
import java.io.Closeable
import java.nio.file.Files
import java.util.Date
import java.util.Properties

/** GreenMail, Room and the DataStore wired as the app does, with the stored settings and discard mode. */
class RulesTestEnv : Closeable {
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
    val mover = TriageMover(db, repo, server.connector(), discardMode = { store.get(it).discardMode })
    val orchestrator = TriageOrchestrator(db, repo, mover, settings = settings)
    val backfill = SentHistoryBackfill(db, repo, server.connector(), store)
    val rerun = SentBackfillRerun(db, store, gate, backfill)
    private val session = Session.getInstance(Properties())

    /** The account with one Sent message, synced and backfilled, auto-file requested: the gate is open. */
    suspend fun readyForAutoFile() {
        db.accounts().upsert(server.account.toEntity(createdAtMillis = 1))
        server.folder("Sent").appendMessage(mail(EMAIL, "friend@people.example", "Hi"), Flags(Flags.Flag.SEEN), Date())
        repo.syncAccount(accountId)
        backfill.run(accountId)
        store.update(accountId) { it.copy(autoFileRequested = true, step = OnboardingStep.DONE) }
    }

    suspend fun rule(
        sender: String,
        target: FolderTarget,
    ) {
        val rule =
            TriageRule(0, accountId, RuleMatcher.Sender(sender), LearnedRules.actionFor(target), target, 1)
        db.learnedRules().put(rule.toEntity())
    }

    fun deliver(
        from: String,
        subject: String,
    ) = server.folder("INBOX").appendMessage(mail(from, EMAIL, subject), Flags(), Date())

    fun serverSubjects(path: String): List<String> =
        server.greenMail.managers.imapHostManager
            .getFolder(server.user, path)
            ?.messages
            ?.map { it.mimeMessage.subject }
            .orEmpty()

    fun hasFolder(path: String) =
        server.greenMail.managers.imapHostManager
            .getFolder(server.user, path) != null

    suspend fun roleOf(path: String): FolderRole? = db.folders().get(accountId.value, path)?.role

    private fun mail(
        from: String,
        to: String,
        subject: String,
    ) = MimeMessage(session).apply {
        setFrom(InternetAddress(from))
        setRecipients(jakarta.mail.Message.RecipientType.TO, to)
        setSubject(subject)
        sentDate = Date()
        setText("Body of $subject")
        saveChanges()
    }

    override fun close() {
        db.close()
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    companion object {
        const val EMAIL = "alex@ourco.example"
    }
}
