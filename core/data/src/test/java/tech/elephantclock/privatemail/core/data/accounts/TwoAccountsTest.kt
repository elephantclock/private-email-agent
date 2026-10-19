package tech.elephantclock.privatemail.core.data.accounts

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.FtsQuery
import tech.elephantclock.privatemail.core.data.db.LearnedRuleEntity
import tech.elephantclock.privatemail.core.data.db.MatcherType
import tech.elephantclock.privatemail.core.data.db.MessageWithVerdict
import tech.elephantclock.privatemail.core.data.db.RoomUndoLog
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.AccountSyncResult
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.SyncTestServer
import tech.elephantclock.privatemail.core.data.secure.SecretKeys
import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.data.secure.StoredSecret
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.ops.UndoEntry
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * T52 with two GreenMail accounts: both sync, Priority spans both, and removing one wipes
 * everything local of it (rows, search index, undo, rules, settings, password and key, pins,
 * files, work, notifications) without contacting a server, while the other stays as it was.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TwoAccountsTest {
    private class MemoryKeys : SecretKeys {
        val keys = mutableMapOf<String, SecretKey>()

        override fun get(alias: String) = keys[alias]

        override fun getOrCreate(alias: String) =
            keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }

        override fun delete(alias: String) {
            keys.remove(alias)
        }
    }

    private class RecordingHooks : AccountRemovalHooks {
        val calls = mutableListOf<String>()
        var pins: Set<HostPort>? = null
        var files: List<Long>? = null

        override suspend fun stopWork(id: AccountId) {
            calls += "work:$id"
        }

        override fun clearNotifications(id: AccountId) {
            calls += "notifications:$id"
        }

        override fun forgetPins(servers: Set<HostPort>) {
            pins = servers
        }

        override fun deleteFiles(messageIds: List<Long>) {
            files = messageIds
        }
    }

    private val serverA = SyncTestServer(email = "anna@localhost")
    private val serverB = SyncTestServer(email = "ben@localhost")
    private val a: Account = serverA.account.copy(id = AccountId("acc-a"))
    private val b: Account = serverB.account.copy(id = AccountId("acc-b"))
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    // One connector serves both: it signs in with each account's own port and username.
    private val repo = MailRepository(db, serverA.connector())
    private val keys = MemoryKeys()
    private val secrets = SecretStore(context.getSharedPreferences(SecretStore.PREFS_NAME, 0), keys)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings =
        TriageSettingsStore(
            TriageSettingsStore.create(scope) { Files.createTempDirectory("prefs").toFile().resolve("t.json") },
        )
    private val hooks = RecordingHooks()

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        serverA.close()
        serverB.close()
    }

    /** Both accounts added, their Inboxes synced and kept in Priority, plus rules, undo, settings, passwords. */
    private suspend fun setUp() {
        listOf(a to 1L, b to 2L).forEach { (account, created) ->
            db.accounts().upsert(account.toEntity(created))
            secrets.put(account.id, Password("pw-${account.id}"))
            settings.update(account.id) { it.copy(vips = setOf("boss@${account.id}.example")) }
            db.learnedRules().put(rule(account.id.value))
        }
        db.learnedRules().put(rule(null))
        serverA.append("INBOX", "Zebracorn invoice")
        serverA.append("INBOX", "Anna second")
        serverB.append("INBOX", "Ben only")
        assertTrue(repo.syncAll().accounts.all { it is AccountSyncResult.Synced })
        val stored = listOf(a, b).flatMap { db.accounts().messageIds(it.id.value) }.map { db.messages().get(it)!! }
        stored.forEach {
            db.verdicts().upsert(
                Verdict.keep(Importance.HIGH, VerdictSource.RULE, "VIP").toEntity(it.id, 5),
            )
        }
        val annas = stored.first { it.accountId == a.id.value }
        RoomUndoLog(db).record(
            listOf(
                UndoEntry(a.id, "batch-a", annas.id, VerdictAction.FILE, "INBOX", 1, annas.uid, "Work", 2, 3, null, 10),
            ),
        )
    }

    private fun rule(accountId: String?) =
        LearnedRuleEntity(
            accountId = accountId,
            matcherType = MatcherType.SENDER,
            matcherValue = "news@${accountId ?: "all"}.example",
            action = VerdictAction.DISCARD,
            targetRole = FolderRole.DISCARDED,
            targetCustomName = null,
            vip = false,
            createdAtMillis = 1,
        )

    private suspend fun priority(): List<MessageWithVerdict> {
        val page = db.messages().priority().load(PagingSource.LoadParams.Refresh(null, 50, false))
        return (page as PagingSource.LoadResult.Page).data
    }

    private suspend fun search(word: String) = db.messages().search(FtsQuery.of(word)!!)

    @Test
    fun `both accounts sync and Priority spans them`() =
        runBlocking {
            setUp()
            val subjects = priority().map { it.message.accountId to it.message.subject }.toSet()
            assertEquals(
                setOf("acc-a" to "Zebracorn invoice", "acc-a" to "Anna second", "acc-b" to "Ben only"),
                subjects,
            )
        }

    @Test
    fun `removing one account wipes everything local of it and leaves the other intact`() =
        runBlocking {
            setUp()
            val annasIds = db.accounts().messageIds(a.id.value)
            val bensBefore = priority().filter { it.message.accountId == b.id.value }
            val connects = serverA.connects.get()

            assertTrue(AccountRemover(db, secrets, settings, hooks).remove(a.id))

            assertEquals("never signed in to a server", connects, serverA.connects.get())
            // Database: row, folders, messages, verdicts, search index, undo, its learned rules.
            assertNull(db.accounts().get(a.id.value))
            assertTrue(db.folders().forAccount(a.id.value).isEmpty())
            assertTrue(db.accounts().messageIds(a.id.value).isEmpty())
            annasIds.forEach { assertNull(db.verdicts().get(it)) }
            assertTrue(search("zebracorn").isEmpty())
            assertTrue(db.undo().batch(a.id.value, "batch-a").isEmpty())
            assertEquals(listOf(null), db.learnedRules().forAccount(a.id.value).map { it.accountId })
            // Settings, password and its key.
            assertEquals(
                setOf(b.id.value),
                settings.all
                    .first()
                    .accounts.keys,
            )
            assertEquals(StoredSecret.Missing, secrets.get(a.id))
            assertFalse(keys.keys.containsKey("pea.secret.${a.id.value}"))
            // App side: work and notifications first; pins only for servers nobody else uses.
            assertEquals(listOf("work:acc-a", "notifications:acc-a"), hooks.calls)
            assertEquals("SMTP localhost:465 is shared with Ben", setOf(HostPort("localhost", a.imap.port)), hooks.pins)
            assertEquals(annasIds.sorted(), hooks.files!!.sorted())

            // The other account is untouched and still syncs.
            assertEquals(bensBefore, priority())
            assertTrue(db.accounts().get(b.id.value) != null)
            assertEquals(StoredSecret.Found(Password("pw-acc-b")), secrets.get(b.id))
            assertEquals(setOf("boss@acc-b.example"), settings.get(b.id).vips)
            assertEquals(2, db.learnedRules().forAccount(b.id.value).size)
            serverB.append("INBOX", "Ben again")
            assertTrue(repo.syncAccount(b.id) is AccountSyncResult.Synced)
            assertEquals(2, db.accounts().messageIds(b.id.value).size)
            assertFalse(AccountRemover(db, secrets, settings, hooks).remove(a.id))
        }
}
