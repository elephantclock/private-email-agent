package tech.elephantclock.privatemail.core.data.triage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.ImapConnector
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.SyncTestServer
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.RuleMatcher
import tech.elephantclock.privatemail.core.model.TriageRule
import tech.elephantclock.privatemail.core.triage.rules.LearnedRules
import java.io.Closeable
import java.util.Date
import java.util.Properties

/**
 * GreenMail with the golden fixtures' mailbox (`alex@ourco.example`, see T23's `context.json`),
 * an in-memory database and the triage pieces wired as the app would.
 */
class TriageTestEnv : Closeable {
    val server = SyncTestServer(EMAIL)
    val db: AppDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries() // only for the tests' raw queries below
            .build()
    val repo = MailRepository(db, server.connector())
    val accountId = server.account.id
    val settings = InMemoryTriageSettings()

    /** The mover's sign-in; tests swap it to simulate an unreachable server. */
    @Volatile var moveConnector: ImapConnector = server.connector()
    val mover = TriageMover(db, repo, { moveConnector.connect(it) })
    val alerts = mutableListOf<TriageAlert>()

    private val session = Session.getInstance(Properties())
    private val context: JsonObject = json("context.json")

    /** `expected.json`'s fixtures: file name → fields. */
    val expected: Map<String, JsonObject> =
        json("expected.json").getValue("fixtures").jsonObject.mapValues { it.value.jsonObject }

    fun orchestrator(ai: TriageAi = TriageAi.UNAVAILABLE) =
        TriageOrchestrator(db, repo, mover, ai, settings, notifier = { synchronized(alerts) { alerts += it } })

    /** The account, the fixture context's settings (auto-file per [autoFile]) and learned rules. */
    suspend fun setUp(autoFile: AutoFile) {
        db.accounts().upsert(server.account.toEntity(createdAtMillis = 1))
        settings.set(
            accountId,
            AccountTriageSettings(
                autoFile = autoFile,
                trustedAuthservIds = strings("trustedAuthservIds"),
                vips = strings("vips"),
                ownAddresses = strings("me"),
            ),
        )
        context.getValue("learnedRules").jsonArray.map { it.jsonObject }.forEach { o ->
            val target = FolderTarget(FolderRole.valueOf(o.str("target")), o["customName"]?.jsonPrimitive?.content)
            val value = o.str("value")
            val rule =
                TriageRule(
                    id =
                        o
                            .getValue("id")
                            .jsonPrimitive.int
                            .toLong(),
                    accountId = null,
                    matcher =
                        if (o.str("matcher") ==
                            "sender"
                        ) {
                            RuleMatcher.Sender(value)
                        } else {
                            RuleMatcher.Domain(value)
                        },
                    action = LearnedRules.actionFor(target),
                    target = target,
                    createdAtMillis = 1,
                    vip = o["vip"]?.jsonPrimitive?.boolean ?: false,
                )
            db.learnedRules().put(rule.toEntity())
        }
    }

    /** The user's Sent folder on the server: [count] messages to each address in the context, plus own Message-IDs. */
    fun seedSent() {
        val sent = server.folder("Sent")
        var n = 0
        context.getValue("sent").jsonArray.map { it.jsonObject }.forEach { o ->
            repeat(o.getValue("count").jsonPrimitive.int) {
                sent.appendMessage(sentMessage(o.str("email"), "<sent-${n++}@ourco.example>"), Flags(), Date())
            }
        }
        strings(
            "ownMessageIds",
        ).forEach { sent.appendMessage(sentMessage("someone@else.example", it), Flags(), Date()) }
    }

    /** Appends the fixture to the server's INBOX, unread, received now. */
    fun deliver(fixture: String) {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$fixture")) { fixture }
        server.folder("INBOX").appendMessage(stream.use { MimeMessage(session, it) }, Flags(), Date())
    }

    fun serverMessageIds(path: String): Set<String> {
        val folder =
            server.greenMail.managers.imapHostManager
                .getFolder(server.user, path) ?: return emptySet()
        return folder.messages.mapNotNull { it.mimeMessage.messageID }.toSet()
    }

    private fun sentMessage(
        to: String,
        id: String,
    ) = object : MimeMessage(session) {
        override fun updateMessageID() = setHeader("Message-ID", id)
    }.apply {
        setFrom(InternetAddress(EMAIL))
        setRecipients(jakarta.mail.Message.RecipientType.TO, to)
        subject = "Sent to $to"
        setText("Hi")
        saveChanges()
    }

    private fun strings(key: String) =
        context
            .getValue(key)
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    private fun json(name: String): JsonObject =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")).use {
                    it.reader().readText()
                },
            ).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    override fun close() {
        db.close()
        server.close()
    }

    companion object {
        const val EMAIL = "alex@ourco.example"
    }
}
