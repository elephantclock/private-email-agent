package tech.elephantclock.privatemail.ai

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.data.triage.BacklogCursor
import tech.elephantclock.privatemail.core.data.triage.BacklogPass
import tech.elephantclock.privatemail.core.data.triage.TriageReport

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ForegroundAiDrainTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** A backlog of [messages] (received times, newest first) the "model" classifies when [answers]. */
    private class Backlog(
        messages: List<Long>,
        var ready: Boolean = true,
        var answers: (Long) -> Boolean = { true },
    ) : AiBacklog {
        val remaining = messages.sortedDescending().toMutableList()
        val asked = mutableListOf<Long>()
        val cursors = mutableListOf<BacklogCursor>()
        var prepared = 0

        override suspend fun prepare(): Boolean {
            prepared++
            return ready
        }

        override suspend fun pass(
            after: BacklogCursor,
            limit: Int,
        ): BacklogPass {
            cursors += after
            val batch = remaining.filter { it < after.receivedAtMillis }.take(limit)
            asked += batch
            val done = batch.filter(answers)
            remaining -= done.toSet()
            val next = batch.lastOrNull()?.let { BacklogCursor(it, it) } ?: after
            return BacklogPass(batch.size, TriageReport(triaged = done.size, byAi = done.size), next)
        }
    }

    private fun drain(backlog: Backlog) =
        ForegroundAiDrain(Owner(), backlog, batchSize = 2, maxIdleBatches = 2, retryDelayMillis = 30_000)

    @Test
    fun `classifies the backlog newest first in small batches`() =
        runTest {
            val backlog = Backlog(listOf(1, 5, 3, 2, 4))
            assertEquals(5, drain(backlog).drain())
            assertEquals(listOf(5L, 4L, 3L, 2L, 1L), backlog.asked)
            assertEquals(BacklogCursor.START, backlog.cursors.first())
            assertEquals(4, backlog.cursors.size) // 3 batches and the empty one
            assertEquals(0L, currentTime)
        }

    @Test
    fun `nothing happens when the model can't run`() =
        runTest {
            val backlog = Backlog(listOf(1, 2), ready = false)
            assertEquals(0, drain(backlog).drain())
            assertEquals(1, backlog.prepared)
            assertEquals(emptyList<Long>(), backlog.asked)
        }

    @Test
    fun `a batch the model can't answer waits, two in a row end the pass`() =
        runTest {
            val backlog = Backlog((1L..10L).toList(), answers = { false })
            assertEquals(0, drain(backlog).drain())
            assertEquals(listOf(10L, 9L, 8L, 7L), backlog.asked)
            assertEquals("one wait between the two idle batches", 30_000L, currentTime)
        }

    @Test
    fun `one success resets the idle count`() =
        runTest {
            val backlog = Backlog((1L..6L).toList(), answers = { it == 4L })
            assertEquals(1, drain(backlog).drain())
            assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L), backlog.asked)
            assertEquals(60_000L, currentTime)
        }

    @Test
    fun `verdicts from rules that decide now count as progress, not as an idle model`() =
        runTest {
            var batches = 0
            val backlog =
                object : AiBacklog {
                    override suspend fun prepare() = true

                    override suspend fun pass(
                        after: BacklogCursor,
                        limit: Int,
                    ): BacklogPass {
                        if (++batches > 4) return BacklogPass(0, TriageReport(), after)
                        return BacklogPass(2, TriageReport(triaged = 2, byAi = 0), BacklogCursor(10L - batches, 0))
                    }
                }
            assertEquals(0, drain(Backlog(emptyList())).drain())
            ForegroundAiDrain(Owner(), backlog, batchSize = 2, maxIdleBatches = 2, retryDelayMillis = 30_000).drain()
            assertEquals("all four batches, no stop after two", 5, batches)
            assertEquals(0L, currentTime)
        }

    @Test
    fun `drains while the app is resumed, stops when it leaves, and starts over next time`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        var runs = 0
        var stopped = 0
        val backlog =
            object : AiBacklog {
                override suspend fun prepare(): Boolean {
                    runs++
                    try {
                        awaitCancellation()
                    } finally {
                        stopped++
                    }
                }

                override suspend fun pass(
                    after: BacklogCursor,
                    limit: Int,
                ) = error("not reached")
            }
        ForegroundAiDrain(owner, backlog, Dispatchers.Main.immediate).start()
        val idle = { shadowOf(Looper.getMainLooper()).idle() }

        owner.registry.currentState = Lifecycle.State.STARTED // visible, not the top app
        idle()
        assertEquals(0 to 0, runs to stopped)
        owner.registry.currentState = Lifecycle.State.RESUMED
        idle()
        assertEquals(1 to 0, runs to stopped)
        owner.registry.currentState = Lifecycle.State.STARTED
        idle()
        assertEquals(1 to 1, runs to stopped)
        owner.registry.currentState = Lifecycle.State.RESUMED
        idle()
        assertEquals(2 to 1, runs to stopped)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        idle()
        assertEquals(2 to 2, runs to stopped)
    }

    @Test
    fun `a failing backlog never crashes the app`() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        var runs = 0
        val backlog =
            object : AiBacklog {
                override suspend fun prepare(): Boolean {
                    runs++
                    throw IllegalStateException("database closed")
                }

                override suspend fun pass(
                    after: BacklogCursor,
                    limit: Int,
                ) = error("not reached")
            }
        ForegroundAiDrain(owner, backlog, Dispatchers.Main.immediate).start()
        val idle = { shadowOf(Looper.getMainLooper()).idle() }
        owner.registry.currentState = Lifecycle.State.RESUMED
        idle()
        owner.registry.currentState = Lifecycle.State.CREATED
        idle()
        owner.registry.currentState = Lifecycle.State.RESUMED
        idle()
        assertEquals(2, runs)
    }
}
