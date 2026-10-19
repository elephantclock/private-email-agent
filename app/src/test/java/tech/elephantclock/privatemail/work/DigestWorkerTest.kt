package tech.elephantclock.privatemail.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.data.triage.TriageAlert
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.notify.AndroidTriageNotifier
import tech.elephantclock.privatemail.notify.ArchiveReceiver
import tech.elephantclock.privatemail.notify.ArchiveWorker
import tech.elephantclock.privatemail.notify.Channels
import tech.elephantclock.privatemail.notify.DigestNotification
import tech.elephantclock.privatemail.notify.NotificationArchiver
import tech.elephantclock.privatemail.notify.NotificationLink
import tech.elephantclock.privatemail.notify.NotificationLinks
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DigestWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val utc = ZoneId.of("UTC")
    private val berlin = ZoneId.of("Europe/Berlin")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun at(
        text: String,
        zone: ZoneId = utc,
    ) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)

    @Test
    fun `next run is the next 18 00, local and DST-aware`() {
        assertEquals(Duration.ofMinutes(1), DigestWorker.delayUntilNext(at("2026-10-18T17:59")))
        assertEquals(Duration.ofHours(24), DigestWorker.delayUntilNext(at("2026-10-18T18:00")))
        assertEquals(Duration.ofHours(24).minusMinutes(1), DigestWorker.delayUntilNext(at("2026-10-18T18:01")))
        // Clocks go forward on 29 March 2026 in Berlin: 18:30 → next day's 18:00 is 22.5 hours later.
        assertEquals(Duration.ofMinutes(22 * 60 + 30), DigestWorker.delayUntilNext(at("2026-03-28T18:30", berlin)))
        // ...and back on 25 October: 24.5 hours.
        assertEquals(Duration.ofMinutes(24 * 60 + 30), DigestWorker.delayUntilNext(at("2026-10-24T18:30", berlin)))
    }

    private fun worker(
        summary: DigestSummary?,
        now: ZonedDateTime,
        posts: MutableList<DigestSummary>,
        windows: MutableList<Pair<Long, Long>> = mutableListOf(),
    ): DigestWorker =
        TestListenableWorkerBuilder<DigestWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        DigestWorker(
                            appContext,
                            workerParameters,
                            source =
                                summary?.let { s ->
                                    DigestSource { from, to ->
                                        windows += from to to
                                        s
                                    }
                                },
                            clock = { now.toInstant().toEpochMilli() },
                            zone = { now.zone },
                            post = { _, s -> posts += s },
                        )
                },
            ).build()

    @Test
    fun `posts today's summary and schedules tomorrow's digest`() =
        runBlocking {
            val now = at("2026-10-18T18:00:05")
            val posts = mutableListOf<DigestSummary>()
            val windows = mutableListOf<Pair<Long, Long>>()
            val summary = DigestSummary(filed = 12, discarded = 61, junk = 4, needsYou = 3, unverified = 1)

            assertEquals(ListenableWorker.Result.success(), worker(summary, now, posts, windows).doWork())

            assertEquals(listOf(summary), posts)
            assertEquals(
                at("2026-10-18T00:00").toInstant().toEpochMilli() to now.toInstant().toEpochMilli(),
                windows.single(),
            )
            val next = WorkManager.getInstance(context).getWorkInfosForUniqueWork(DigestWorker.UNIQUE_NAME).get()
            assertEquals(1, next.size)
            assertTrue(next.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED))
            assertEquals(
                Duration.ofHours(24).minusSeconds(5).toMillis(),
                next.single().initialDelayMillis,
            )
        }

    @Test
    fun `an empty day posts nothing but still schedules the next digest`() =
        runBlocking {
            val posts = mutableListOf<DigestSummary>()
            worker(DigestSummary(), at("2026-10-18T18:00"), posts).doWork()
            assertTrue(posts.isEmpty())
            assertEquals(
                1,
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWork(DigestWorker.UNIQUE_NAME)
                    .get()
                    .count { !it.state.isFinished },
            )
        }

    @Test
    fun `without a database the run posts nothing and succeeds`() =
        runBlocking {
            val posts = mutableListOf<DigestSummary>()
            assertEquals(ListenableWorker.Result.success(), worker(null, at("2026-10-18T18:00"), posts).doWork())
            assertTrue(posts.isEmpty())
        }

    @Test
    fun `schedule keeps an already scheduled digest`() {
        val wm = WorkManager.getInstance(context)
        DigestWorker.schedule(wm, at("2026-10-18T09:00"))
        val first = wm.getWorkInfosForUniqueWork(DigestWorker.UNIQUE_NAME).get().single()
        assertEquals(Duration.ofHours(9).toMillis(), first.initialDelayMillis)
        DigestWorker.schedule(wm, at("2026-10-18T10:00"))
        assertEquals(
            first.id,
            wm
                .getWorkInfosForUniqueWork(DigestWorker.UNIQUE_NAME)
                .get()
                .single()
                .id,
        )
    }

    @Test
    fun `the digest notification shows counts only and opens Discarded`() {
        val app = context as Application
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        DigestNotification.post(context, DigestSummary(filed = 1, discarded = 61, needsYou = 3, unverified = 2))
        val n = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(Channels.DIGEST, n.channelId)
        assertEquals(
            "1 filed, 61 discarded\n3 messages still need you.\n" +
                "2 important-looking messages failed sender checks and didn't alert.",
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )
        assertEquals(NotificationLink.Discarded, NotificationLinks.parse(shadowOf(n.contentIntent).savedIntent))
    }

    @Test
    fun `archive worker calls the archiver once and fails without one`() =
        runBlocking {
            val calls = mutableListOf<Pair<AccountId, Long>>()
            val archiver =
                NotificationArchiver { a, id ->
                    calls += a to id
                    true
                }

            fun build(archiver: NotificationArchiver?) =
                TestListenableWorkerBuilder<ArchiveWorker>(context)
                    .setInputData(ArchiveWorker.request("acc", 42).workSpec.input)
                    .setWorkerFactory(
                        object : WorkerFactory() {
                            override fun createWorker(
                                appContext: Context,
                                workerClassName: String,
                                workerParameters: WorkerParameters,
                            ) = ArchiveWorker(appContext, workerParameters, archiver)
                        },
                    ).build()

            assertEquals(ListenableWorker.Result.success(), build(archiver).doWork())
            assertEquals(listOf(AccountId("acc") to 42L), calls)
            assertEquals(ListenableWorker.Result.failure(), build(null).doWork())
        }

    @Test
    fun `the Archive button removes the notification and queues one archive per message`() {
        val app = context as Application
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val alert =
            TriageAlert(AccountId("acc"), 42, null, "Hi", Importance.URGENT, "VIP sender")
        AndroidTriageNotifier(context, archiveAvailable = true).notify(alert)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val archive =
            shadowOf(
                manager.allNotifications
                    .single()
                    .actions[2]
                    .actionIntent,
            ).savedIntent

        ArchiveReceiver().onReceive(context, archive)
        ArchiveReceiver().onReceive(context, archive) // a double tap queues nothing more

        assertTrue(manager.allNotifications.isEmpty())
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("archive:acc:42").get()
        assertEquals(1, work.size)
        // A forged intent without the action does nothing.
        ArchiveReceiver().onReceive(context, Intent(archive).setAction("other"))
        assertEquals(
            1,
            WorkManager
                .getInstance(context)
                .getWorkInfosByTag(ArchiveWorker::class.java.name)
                .get()
                .size,
        )
    }
}
