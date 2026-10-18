package tech.elephantclock.privatemail.work

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BulkTriageWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun worker(
        source: BulkTriageSource,
        attempt: Int = 0,
    ) = TestListenableWorkerBuilder<BulkTriageWorker>(context)
        .setWorkerFactory(BulkTriageWorkerFactory { source })
        .setRunAttemptCount(attempt)
        .build()

    @Test
    fun `drains batches until nothing is left`() {
        val batches = ArrayDeque(listOf(20, 20, 7, 0))
        val limits = mutableListOf<Int>()
        val result =
            runBlocking {
                worker({ limit ->
                    limits += limit
                    batches.removeFirst()
                }).doWork()
            }
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(batches.isEmpty())
        assertEquals(List(4) { BulkTriageWorker.BATCH_SIZE }, limits)
    }

    @Test
    fun `a failing source is retried a few times, then given up`() {
        val failing = BulkTriageSource { error("db closed") }
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker(failing).doWork() })
        assertEquals(
            ListenableWorker.Result.failure(),
            runBlocking { worker(failing, attempt = BulkTriageWorker.MAX_ATTEMPTS - 1).doWork() },
        )
    }

    @Test
    fun `only one drain runs at a time, whichever trigger fired`() {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val slow =
            BulkTriageSource {
                calls++
                gate.await()
                0
            }
        runBlocking {
            val first = async { worker(slow).doWork() }
            while (calls == 0) yield()
            assertEquals(ListenableWorker.Result.success(), worker(slow).doWork())
            assertEquals(1, calls)
            gate.complete(Unit)
            assertEquals(ListenableWorker.Result.success(), first.await())
        }
        // The lock is released afterwards.
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker({ 0 }).doWork() })
    }

    @Test
    fun `bulk work waits for charging or idle and is scheduled once per trigger`() {
        val charging = BulkTriageWorker.chargingRequest().workSpec.constraints
        assertTrue(charging.requiresCharging())
        assertTrue(charging.requiresBatteryNotLow())
        assertFalse(charging.requiresDeviceIdle())
        val idle = BulkTriageWorker.idleRequest().workSpec.constraints
        assertTrue(idle.requiresDeviceIdle())
        assertFalse(idle.requiresCharging())

        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val workManager = WorkManager.getInstance(context)
        BulkTriageWorker.schedule(workManager)
        BulkTriageWorker.schedule(workManager)
        for (name in listOf(BulkTriageWorker.UNIQUE_CHARGING, BulkTriageWorker.UNIQUE_IDLE)) {
            val infos = workManager.getWorkInfosForUniqueWork(name).get()
            assertEquals(name, 1, infos.size)
            assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        }
        BulkTriageWorker.cancel(workManager)
        val after = workManager.getWorkInfosForUniqueWork(BulkTriageWorker.UNIQUE_IDLE).get()
        assertEquals(WorkInfo.State.CANCELLED, after.single().state)
    }
}
