package tech.elephantclock.privatemail.core.ai.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.elephantclock.privatemail.core.ai.AiCall
import tech.elephantclock.privatemail.core.ai.AiEngine
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.AiTimeouts
import tech.elephantclock.privatemail.core.ai.ClassifyOutcome
import tech.elephantclock.privatemail.core.ai.ClassifyRequest
import tech.elephantclock.privatemail.core.ai.FakeAiAvailability
import tech.elephantclock.privatemail.core.ai.FakeAiEngine
import tech.elephantclock.privatemail.core.ai.TestData
import tech.elephantclock.privatemail.core.ai.TestData.item

class AiQueueTest {
    private val engine =
        FakeAiEngine(FakeAiAvailability(), AiTimeouts(classifyMillis = 60_000)).apply {
            latencyMillis =
                1_000
        }

    private fun TestScope.queue(cacheSize: Int = 100) = AiQueue(engine, backgroundScope, cacheSize)

    private fun TestScope.bulk(
        q: AiQueue,
        key: String,
        receivedAt: Long,
    ) = async { q.classify(item(key, receivedAt), TestData.folders, AiPriority.BULK) }

    @Test
    fun `one call at a time, newest message first`() =
        runTest {
            val q = queue()
            val jobs = listOf(bulk(q, "old", 1), bulk(q, "newest", 30), bulk(q, "mid", 20), bulk(q, "older", 2))
            jobs.awaitAll()
            assertEquals(listOf("newest", "mid", "older", "old"), engine.classifiedKeys)
            assertEquals(1, engine.maxConcurrent)
            assertEquals(4_000, testScheduler.currentTime)
        }

    @Test
    fun `foreground goes before queued bulk work`() =
        runTest {
            val q = queue()
            val first = async { q.foreground { it.summarize(TestData.thread) } }
            runCurrent()
            val b = bulk(q, "bulk", 99)
            val f = async { q.classify(item("fg", 1), TestData.folders) }
            awaitAll(first, b, f)
            assertEquals(listOf("fg", "bulk"), engine.classifiedKeys)
        }

    @Test
    fun `the same message is classified once, then served from the cache`() =
        runTest {
            val q = queue()
            val a = async { q.classify(item("m"), TestData.folders, AiPriority.BULK) }
            val b = async { q.classify(item("m"), TestData.folders) }
            assertEquals(a.await(), b.await())
            assertEquals(listOf("m"), engine.classifiedKeys)
            val before = testScheduler.currentTime
            assertTrue(q.classify(item("m"), TestData.folders).isOk)
            assertEquals(before, testScheduler.currentTime)
            assertEquals(1, engine.classifiedKeys.size)
            q.clearCache()
            q.classify(item("m"), TestData.folders)
            assertEquals(2, engine.classifiedKeys.size)
        }

    @Test
    fun `failures are not cached`() =
        runTest {
            val q = queue()
            engine.script("m", AiResult.Failed(AiFailure.Timeout(1)))
            assertFalse(q.classify(item("m"), TestData.folders).isOk)
            assertNull(q.cached("m"))
            engine.script("m", AiResult.Ok(TestData.classification("money")))
            assertTrue(q.classify(item("m"), TestData.folders).isOk)
            assertEquals(2, engine.classifiedKeys.size)
        }

    @Test
    fun `the cache is bounded, least recently used out first`() =
        runTest {
            val q = queue(cacheSize = 2)
            q.classify(item("a"), TestData.folders)
            q.classify(item("b"), TestData.folders)
            q.classify(item("a"), TestData.folders) // touch a
            q.classify(item("c"), TestData.folders)
            assertNotNull(q.cached("a"))
            assertNull(q.cached("b"))
            assertNotNull(q.cached("c"))
        }

    @Test
    fun `a foreground request preempts running bulk work, which runs again afterwards`() =
        runTest {
            val q = queue()
            val b = bulk(q, "bulk", 1)
            advanceTimeBy(500)
            runCurrent()
            val f = async { q.classify(item("fg", 2), TestData.folders) }
            f.await()
            assertEquals(1_500, testScheduler.currentTime) // bulk cut at 500 ms, fg took 1 s
            assertFalse(b.isCompleted)
            assertTrue(b.await().isOk)
            assertEquals(listOf("bulk", "fg", "bulk"), engine.classifiedKeys)
            assertEquals(2_500, testScheduler.currentTime)
            assertEquals(1, engine.maxConcurrent)
        }

    @Test
    fun `generic foreground calls preempt bulk too`() =
        runTest {
            val q = queue()
            val b = bulk(q, "bulk", 1)
            advanceTimeBy(100)
            runCurrent()
            val gist = q.foreground { it.summarize(TestData.thread).getOrNull()!!.gist }
            assertEquals("Can we meet on Friday?", gist)
            assertTrue(b.await().isOk)
            assertEquals(listOf(AiCall.CLASSIFY, AiCall.SUMMARIZE, AiCall.CLASSIFY), engine.calls)
        }

    @Test
    fun `a cancelled caller drops queued work nobody else wants`() =
        runTest {
            val q = queue()
            val running = bulk(q, "running", 1)
            runCurrent()
            val dropped = bulk(q, "dropped", 5)
            runCurrent()
            assertEquals(1, q.pendingCount)
            dropped.cancel()
            runCurrent()
            assertEquals(0, q.pendingCount)
            running.await()
            advanceUntilIdle()
            assertEquals(listOf("running"), engine.classifiedKeys)
        }

    @Test
    fun `shared work survives one waiter cancelling, and stops when the last one does`() =
        runTest {
            val q = queue()
            val a = bulk(q, "m", 1)
            val b = bulk(q, "m", 1)
            advanceTimeBy(500)
            a.cancel()
            assertTrue(b.await().isOk)
            assertEquals(1, engine.classifiedKeys.size)

            val c = bulk(q, "n", 1)
            advanceTimeBy(500)
            c.cancel()
            advanceUntilIdle()
            assertNull(q.cached("n"))
            // The running call was cancelled at 500 ms, not run to completion.
            assertEquals(listOf("m", "n"), engine.classifiedKeys)
            assertEquals(0, q.pendingCount)
            // The queue keeps working afterwards.
            assertTrue(q.classify(item("n"), TestData.folders).isOk)
        }

    @Test
    fun `a foreground request for queued bulk work moves it to the front`() =
        runTest {
            val q = queue()
            val first = bulk(q, "first", 1)
            runCurrent()
            val newer = bulk(q, "newer", 50)
            val older = bulk(q, "older", 2)
            runCurrent()
            val urgent = async { q.classify(item("older", 2), TestData.folders) }
            awaitAll(first, newer, older, urgent)
            // "first" was preempted by the upgraded request, then re-run after the newer bulk item.
            assertEquals(listOf("first", "older", "newer", "first"), engine.classifiedKeys)
            assertEquals(older.await(), urgent.await())
        }

    @Test
    fun `a caller that cancels and another that asks again at once still get a result`() =
        runTest {
            val q = queue()
            val c = bulk(q, "n", 1)
            advanceTimeBy(500)
            c.cancel()
            val again = async { q.classify(item("n", 1), TestData.folders) }
            assertTrue(again.await().isOk)
            assertEquals(0, q.pendingCount)
            assertNotNull(q.cached("n"))
        }

    @Test
    fun `a cancellation thrown by the work itself is a failure, not a retry loop`() =
        runTest {
            val q = queue()
            var runs = 0
            val failed =
                runCatching {
                    q.foreground<Unit> {
                        runs++
                        throw CancellationException("engine gave up")
                    }
                }.exceptionOrNull()
            assertTrue("$failed", failed != null && failed !is CancellationException)
            assertEquals(1, runs)

            var classifyRuns = 0
            val throwing =
                object : AiEngine by engine {
                    override suspend fun classify(request: ClassifyRequest): List<ClassifyOutcome> {
                        classifyRuns++
                        throw CancellationException("binder cancelled")
                    }
                }
            val q2 = AiQueue(throwing, backgroundScope)
            val r = q2.classify(item("x"), TestData.folders)
            assertTrue((r as AiResult.Failed).failure is AiFailure.EngineError)
            assertEquals(1, classifyRuns)
            // The queue is not stuck: the next request runs.
            assertTrue(q.classify(item("next"), TestData.folders).isOk)
        }
}
