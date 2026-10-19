// Report-building lines are long text; wrapping them hurts readability.
@file:Suppress("ktlint:standard:max-line-length")

package tech.elephantclock.privatemail.core.data.eval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toBody
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.db.toHeader
import tech.elephantclock.privatemail.core.data.onboarding.SentHistoryBackfill
import tech.elephantclock.privatemail.core.data.repo.ImapConnector
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.settings.SentBackfillGate
import tech.elephantclock.privatemail.core.data.settings.StoredTriageSettings
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.data.triage.TriageAi
import tech.elephantclock.privatemail.core.data.triage.TriageMover
import tech.elephantclock.privatemail.core.data.triage.TriageOrchestrator
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.core.triage.MailItem
import tech.elephantclock.privatemail.core.triage.signals.SignalExtractor
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * T25: triage measured on the user's real mailbox, **read-only**. Skipped unless run on the Mac with
 * `PEA_LIVE_EVAL=1 ./gradlew :core:data:testDebugUnitTest --tests '*RealMailboxEval*'`.
 *
 * - Server access: EXAMINE / BODY.PEEK sync of the Inbox (30 days) and Sent headers (12 months),
 *   plus read-only SEARCHes of Sent. The mover's connector throws; label-only triage; AI off.
 * - Output: `<repo>/eval/` only (gitignored). Nothing about messages is printed (Gradle keeps stdout).
 * - Settings come from the Mac watcher's config; the password from its Keychain entry, in memory only.
 *
 * Fails if any DISCARD/JUNK hits a sender the user wrote to (whole Sent folder on the server) or a
 * thread the user replied in. Discards the watcher put in Urgent / Customer Support / Needs Reply
 * are listed for human review (the watcher is a heuristic, not evidence).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RealMailboxEvalTest {
    @Test
    fun evaluateOnRealMailbox() {
        assumeTrue(System.getProperty("pea.liveEval") == "1")
        val out = File(System.getProperty("pea.repoRoot")!!, "eval").apply { mkdirs() }
        val config =
            Json
                .parseToJsonElement(
                    File(System.getProperty("user.home"), ".config/claude-email/config.json").readText(),
                ).jsonObject

        fun str(key: String) = config.getValue(key).jsonPrimitive.content
        val username = str("username")
        val password = Password(keychainPassword(username))
        val account =
            Account(
                id = AccountId("eval"),
                email = Address.of(username),
                displayName = username,
                username = username,
                imap = ServerConfig(str("imap_host"), str("imap_port").toInt(), ConnectionSecurity.SSL_TLS),
                smtp = ServerConfig(str("smtp_host"), str("smtp_port").toInt(), ConnectionSecurity.STARTTLS),
            )

        // 1. Baseline first: the watcher's own read-only preview, straight to a file.
        val since = LocalDate.now().minusDays(30)
        val watcher = runWatcher(since, out)

        val db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dir = Files.createTempDirectory("eval-prefs").toFile()
        try {
            val client = ImapClient(CertificatePins.None)
            val signIns = AtomicInteger()
            val connector =
                ImapConnector { a ->
                    signIns.incrementAndGet()
                    client.connect(ImapConfig.from(a.imap), MailCredentials(a.username, password))
                }
            val moverSignIns = AtomicInteger()
            val noMoves =
                ImapConnector {
                    moverSignIns.incrementAndGet()
                    MailResult.Failed(AuthError.Unknown("evaluation is read-only"))
                }
            val repo = MailRepository(db, connector)
            val store = TriageSettingsStore(TriageSettingsStore.create(scope) { dir.resolve("triage.json") })
            val gate = SentBackfillGate(db, store)

            val report = EvalReport(username)
            runBlocking {
                db.accounts().upsert(account.toEntity(createdAtMillis = System.currentTimeMillis()))
                // 2. Sync (first) and a second sync to time the reconcile path.
                val t0 = System.nanoTime()
                val first = repo.syncAccount(account.id)
                val t1 = System.nanoTime()
                repo.syncAccount(account.id)
                val t2 = System.nanoTime()
                report.sync(first.toString().take(300), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)

                // 3. Which server writes the trusted Authentication-Results?
                val inbox =
                    db
                        .query("SELECT id FROM messages WHERE accountId = 'eval' AND folderPath = 'INBOX'", null)
                        .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
                        .mapNotNull { db.messages().get(it) }
                val auth = AuthIds.of(inbox.map { it.toHeader() })
                val trusted = auth.dominant()?.let { setOf(it) }.orEmpty()
                store.update(account.id) { it.copy(trustedAuthservIds = trusted) }
                report.auth(auth, trusted)

                // 4. Sent history (12 months of headers), then label-only triage, rules only.
                val backfill = SentHistoryBackfill(db, repo, connector, store).run(account.id)
                report.backfill(backfill.toString().take(200))
                val mover = TriageMover(db, repo, noMoves)
                val orchestrator =
                    TriageOrchestrator(
                        db,
                        repo,
                        mover,
                        ai = TriageAi.UNAVAILABLE,
                        settings = StoredTriageSettings.labelOnly(StoredTriageSettings(store, gate)),
                    )
                orchestrator.drain()

                // 5. Rows, signals and verdicts.
                val extractor = SignalExtractor(trustedAuthservIds = trusted)
                val rows =
                    inbox.map { e ->
                        val header = e.toHeader()
                        val body = e.toBody()
                        EvalRow(
                            uid = e.uid,
                            from = e.fromEmail.orEmpty(),
                            subject = e.subject,
                            snippetEmpty = e.snippet.isBlank(),
                            snippetHtml = e.snippetMimeType.contains("html"),
                            preview = e.snippet,
                            replyToDomains = header.replyTo.map { it.domain },
                            signals = extractor.extract(MailItem(header, body)),
                            verdict = db.verdicts().get(e.id),
                            inReplyTo = listOfNotNull(header.inReplyTo) + header.references,
                            watcher = watcher[e.uid.toString()],
                        )
                    }

                // 6. Independent safety check against the server's whole Sent folder (read-only).
                val sentPaths = db.triage().sentFolders(account.id.value)
                val risky = rows.filter { it.verdict?.action?.name in setOf("DISCARD", "JUNK") }
                val independent =
                    SentSearch(account.imap.host, account.imap.port, username, password).use { s ->
                        risky
                            .associateWith { r ->
                                listOfNotNull(
                                    "wrote to sender".takeIf { s.wroteTo(sentPaths, r.from) },
                                    "replied in thread".takeIf {
                                        r.inReplyTo.any { id ->
                                            s.ownMessage(sentPaths, id)
                                        }
                                    },
                                )
                            }.filterValues { it.isNotEmpty() }
                    }
                val capabilities =
                    SentSearch(
                        account.imap.host,
                        account.imap.port,
                        username,
                        password,
                    ).use { it.capabilities() }
                val folders = db.folders().forAccount(account.id.value).map { it.path }
                // The watcher's needs-you buckets are a heuristic, not evidence of a relationship: listed for review.
                val review = risky.filter { it !in independent && it.watcher?.bucket in WatcherBuckets.NEEDS_USER }
                report.rows(
                    rows,
                    independent,
                    review,
                    capabilities,
                    folders,
                    watcher.size,
                    moverSignIns.get(),
                    signIns.get(),
                )
                File(out, "report.md").writeText(report.render())
                assertEquals("the mover must never sign in", 0, moverSignIns.get())
                assertEquals("protected messages discarded or junked (see eval/report.md)", 0, independent.size)
            }
        } finally {
            db.close()
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private fun runWatcher(
        since: LocalDate,
        out: File,
    ): Map<String, WatcherEntry> {
        val json = File(out, "watcher.json")
        val process =
            ProcessBuilder(
                "/opt/homebrew/opt/python@3.14/bin/python3.14",
                "-I",
                // The Mac watcher's triage script (preview mode only; never --apply).
                File(System.getProperty("user.home"), "IZI/Email/scripts/triage.py").path,
                "--since",
                since.toString(),
                "--json",
            ).redirectOutput(json)
                .redirectError(File(out, "watcher.err"))
                .start()
        check(process.waitFor() == 0) { "watcher failed: see eval/watcher.err" }
        return Json
            .parseToJsonElement(json.readText())
            .jsonArray
            .associate { e ->
                val o = e.jsonObject
                o.getValue("uid").jsonPrimitive.content to
                    WatcherEntry(
                        o.getValue("bucket").jsonPrimitive.content,
                        o["label"]?.jsonPrimitive?.content.orEmpty(),
                    )
            }
    }

    private fun keychainPassword(account: String): String {
        val process =
            ProcessBuilder("security", "find-generic-password", "-s", "claude-email", "-a", account, "-w")
                .redirectError(File("/dev/null"))
                .start()
        val secret =
            process.inputStream
                .bufferedReader()
                .readText()
                .trimEnd('\n')
        check(process.waitFor() == 0 && secret.isNotEmpty()) { "Keychain entry 'claude-email' not readable" }
        return secret
    }
}
