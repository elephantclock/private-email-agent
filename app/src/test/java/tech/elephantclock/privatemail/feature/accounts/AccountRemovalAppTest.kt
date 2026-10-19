package tech.elephantclock.privatemail.feature.accounts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
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
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.data.accounts.AccountRemover
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.secure.SecretKeys
import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.data.secure.StoredSecret
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.feature.detail.AttachmentFiles
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures
import tech.elephantclock.privatemail.feature.onboarding.cert
import tech.elephantclock.privatemail.notify.AndroidTriageNotifier
import tech.elephantclock.privatemail.notify.ArchiveWorker
import tech.elephantclock.privatemail.security.CertificatePinStore
import tech.elephantclock.privatemail.work.WorkManagerFirstRunWork
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Remove account (T52) with the app's real parts: pins, WorkManager, notifications, attachment
 * files, the settings file, the password store (in-memory keys: Robolectric has no Keystore).
 * Anna's account goes; Ben's, which shares Anna's SMTP server, keeps everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountRemovalAppTest {
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

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val settings =
        TriageSettingsStore(
            TriageSettingsStore.create(scope) { Files.createTempDirectory("prefs").toFile().resolve("t.json") },
        )
    private val keys = MemoryKeys()
    private val secrets = SecretStore(app.getSharedPreferences(SecretStore.PREFS_NAME, 0), keys)
    private val pins = CertificatePinStore(app)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val workManager: WorkManager =
        WorkManagerTestInitHelper
            .initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
            .let { WorkManager.getInstance(app) }

    private val smtp = ServerConfig("smtp.shared.example", 465, ConnectionSecurity.SSL_TLS)
    private val anna: Account =
        ReadingFixtures
            .account("acc-anna", "anna@a.example")
            .copy(imap = ServerConfig("imap.a.example", 993, ConnectionSecurity.SSL_TLS), smtp = smtp)
    private val ben: Account =
        ReadingFixtures
            .account("acc-ben", "ben@b.example")
            .copy(imap = ServerConfig("imap.b.example", 993, ConnectionSecurity.SSL_TLS), smtp = smtp)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun post(
        account: Account,
        localId: Long,
    ) {
        val n = Notification.Builder(app, "test").setSmallIcon(android.R.drawable.ic_dialog_email).build()
        notifications.notify(AndroidTriageNotifier.tag(account.id), AndroidTriageNotifier.id(localId), n)
    }

    private fun states(name: String) = workManager.getWorkInfosForUniqueWork(name).get().map { it.state }

    @Test
    fun `removing Anna leaves nothing of hers and all of Ben's`() =
        runBlocking {
            val firstRun = WorkManagerFirstRunWork(workManager)
            listOf(anna to 1L, ben to 2L).forEachIndexed { i, (account, created) ->
                db.accounts().upsert(account.toEntity(created))
                db.messages().insertAll(listOf(ReadingFixtures.entity(10L + i, "x", "Hi", "p", 5, account)))
                secrets.put(account.id, Password("pw"))
                settings.update(account.id) { it.copy(vips = setOf("boss@x.example")) }
                pins.trust(cert(account.imap.host, 993))
                post(account, 10L + i)
                AttachmentFiles
                    .dirFor(app.cacheDir, 10L + i)
                    .apply { mkdirs() }
                    .resolve("a.pdf")
                    .writeText("pdf")
                firstRun.prepare(account.id)
                workManager.enqueueUniqueWork(
                    "archive:${account.id}:${10 + i}",
                    ExistingWorkPolicy.KEEP,
                    ArchiveWorker.request(
                        account.id.value,
                        10L + i,
                    ),
                )
            }
            pins.trust(cert("smtp.shared.example", 465))

            val hooks = AndroidRemovalHooks(app, workManager, pins)
            assertTrue(AccountRemover(db, secrets, settings, hooks).remove(anna.id))

            val reloaded = CertificatePinStore(app)
            assertFalse(reloaded.isPinned("imap.a.example", 993, "AA:BB:993"))
            assertTrue(reloaded.isPinned("imap.b.example", 993, "AA:BB:993"))
            assertTrue("shared with Ben", reloaded.isPinned("smtp.shared.example", 465, "AA:BB:465"))

            assertEquals(listOf(WorkInfo.State.CANCELLED), states(WorkManagerFirstRunWork.uniqueName(anna.id)))
            assertEquals(listOf(WorkInfo.State.CANCELLED), states("archive:${anna.id}:10"))
            assertEquals(listOf(WorkInfo.State.ENQUEUED), states(WorkManagerFirstRunWork.uniqueName(ben.id)))
            assertEquals(listOf(WorkInfo.State.ENQUEUED), states("archive:${ben.id}:11"))

            val tags = notifications.activeNotifications.map { it.tag }
            assertEquals(listOf(AndroidTriageNotifier.tag(ben.id)), tags)

            assertFalse(AttachmentFiles.dirFor(app.cacheDir, 10).exists())
            assertTrue(AttachmentFiles.dirFor(app.cacheDir, 11).resolve("a.pdf").exists())

            assertNull(db.accounts().get(anna.id.value))
            assertNull(db.messages().get(10))
            assertEquals(StoredSecret.Missing, secrets.get(anna.id))
            assertEquals(
                setOf(ben.id.value),
                settings.all
                    .first()
                    .accounts.keys,
            )
            assertEquals(StoredSecret.Found(Password("pw")), secrets.get(ben.id))
            assertEquals(
                listOf(ben.id.value),
                db
                    .accounts()
                    .all()
                    .first()
                    .map { it.id },
            )
        }
}
