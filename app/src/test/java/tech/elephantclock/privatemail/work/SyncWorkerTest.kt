package tech.elephantclock.privatemail.work

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.ImapConnector
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A plain Application: these tests build their own WorkManager and repository. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val connects = AtomicInteger()

    @Volatile private var signIn: AuthError = AuthError.PortBlockedOrTimeout("imap.example.com", 993)
    private val repository =
        MailRepository(
            db,
            ImapConnector {
                connects.incrementAndGet()
                MailResult.Failed(signIn)
            },
        )

    private val account =
        Account(
            id = AccountId("acc-w"),
            email = Address.of("me@example.com"),
            displayName = "Work",
            username = "me@example.com",
            imap = ServerConfig("imap.example.com", 993, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.example.com", 587, ConnectionSecurity.STARTTLS),
        )

    @After
    fun tearDown() = db.close()

    private fun withAccount() = runBlocking { db.accounts().upsert(account.toEntity(createdAtMillis = 1)) }

    private fun run(attempt: Int = 0): ListenableWorker.Result =
        runBlocking {
            TestListenableWorkerBuilder<SyncWorker>(context)
                .setWorkerFactory(SyncWorkerFactory({ repository }))
                .setRunAttemptCount(attempt)
                .build()
                .doWork()
        }

    @Test
    fun `triage is drained after every sync, and a failing drain never fails the run`() {
        val drains = AtomicInteger()

        fun runWith(afterSync: suspend () -> Unit) =
            runBlocking {
                TestListenableWorkerBuilder<SyncWorker>(context)
                    .setWorkerFactory(SyncWorkerFactory({ repository }, afterSync))
                    .build()
                    .doWork()
            }
        assertEquals(ListenableWorker.Result.success(), runWith { drains.incrementAndGet() })
        assertEquals(1, drains.get())
        assertEquals(ListenableWorker.Result.success(), runWith { error("triage broke") })
    }

    @Test
    fun `nothing to sync succeeds`() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, connects.get())
    }

    @Test
    fun `an unreachable server is retried a few times per period, then left for the next one`() {
        withAccount()
        assertEquals(ListenableWorker.Result.retry(), run(attempt = 0))
        assertEquals(ListenableWorker.Result.retry(), run(attempt = 1))
        assertEquals(ListenableWorker.Result.success(), run(attempt = SyncWorker.MAX_ATTEMPTS - 1))
        assertNull(runBlocking { db.accounts().get("acc-w")!!.authProblem })
    }

    @Test
    fun `a wrong password is never retried and stops later runs from signing in`() {
        withAccount()
        signIn = AuthError.WrongCredentials
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(1, connects.get())
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(1, connects.get())
    }

    @Test
    fun `the periodic sync is scheduled once, every 15 minutes, with a network`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val workManager = WorkManager.getInstance(context)
        SyncWorker.schedule(workManager)
        SyncWorker.schedule(workManager)
        val infos = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)

        val spec = SyncWorker.request().workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(15), spec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }
}
