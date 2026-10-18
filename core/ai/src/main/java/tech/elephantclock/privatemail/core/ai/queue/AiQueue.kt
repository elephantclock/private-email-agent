package tech.elephantclock.privatemail.core.ai.queue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.core.ai.AiClassification
import tech.elephantclock.privatemail.core.ai.AiEngine
import tech.elephantclock.privatemail.core.ai.AiFailure
import tech.elephantclock.privatemail.core.ai.AiResult
import tech.elephantclock.privatemail.core.ai.ClassifyItem
import tech.elephantclock.privatemail.core.ai.FolderOption
import tech.elephantclock.privatemail.core.ai.classifyOne
import java.util.TreeSet

enum class AiPriority {
    /** The user is waiting: a message on screen, a reply being drafted. Preempts [BULK]. */
    FOREGROUND,

    /** First run, re-sorting, background triage. Runs only when nothing foreground is waiting. */
    BULK,
}

/**
 * Serialises every call to the on-device model (it runs one inference at a time).
 *
 * - **Single flight:** one engine call at a time, in [scope].
 * - **Order:** foreground before bulk; within a priority, the newest message first
 *   (`receivedAtMillis`), then first come first served. Generic [foreground] tasks (summaries,
 *   drafts) go before foreground classifications, in arrival order.
 * - **Dedupe:** a classification already cached, queued or running for the same
 *   [ClassifyItem.key] is shared, not repeated; a foreground request upgrades a queued bulk one.
 * - **Cache:** successful classifications per key (Message-ID), LRU, at most [cacheSize]. Failures
 *   aren't cached, so a timeout is retried next time. Call [clearCache] when the folders change.
 * - **Preemption:** a foreground request cancels a running bulk call, which goes back in the
 *   queue and runs again later.
 * - **Cancellation:** a caller that is cancelled stops waiting; the work is dropped (or the running
 *   call cancelled) only when nobody else is waiting for it.
 *
 * Pass the app's `SafeAiEngine` as [engine] so the queue never sees unredacted text.
 */
class AiQueue(
    private val engine: AiEngine,
    private val scope: CoroutineScope,
    private val cacheSize: Int = DEFAULT_CACHE_SIZE,
) {
    init {
        require(cacheSize > 0) { "cacheSize must be positive" }
    }

    private class Entry(
        val key: String?,
        var priority: AiPriority,
        val receivedAtMillis: Long,
        val seq: Long,
        val work: suspend (AiEngine) -> Any?,
    ) {
        val result = CompletableDeferred<Any?>()
        var waiters = 1
        var execution: Deferred<Any?>? = null

        /** Set whenever the queue itself cancels the running call (preemption, nobody waiting). */
        var cancelledByQueue = false
    }

    private val lock = Any()
    private val order =
        compareBy<Entry> { it.priority }
            .thenByDescending { if (it.key == null) Long.MAX_VALUE else it.receivedAtMillis }
            .thenBy { it.seq }
    private val pending = TreeSet(order)
    private val byKey = HashMap<String, Entry>()
    private val cache =
        object : LinkedHashMap<String, AiClassification>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiClassification>) = size > cacheSize
        }
    private var current: Entry? = null
    private var runner: Job? = null
    private var nextSeq = 0L

    /** Queued (not running) requests. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    fun cached(key: String): AiClassification? = synchronized(lock) { cache[key] }

    fun clearCache() = synchronized(lock) { cache.clear() }

    suspend fun classify(
        item: ClassifyItem,
        folders: List<FolderOption>,
        priority: AiPriority = AiPriority.FOREGROUND,
    ): AiResult<AiClassification> {
        val work: suspend (AiEngine) -> Any? = { it.classifyOne(folders, item) }
        val entry =
            synchronized(lock) {
                cache[item.key]?.let { return AiResult.Ok(it) }
                val existing = byKey[item.key]
                if (existing != null) {
                    existing.waiters++
                    if (priority < existing.priority) {
                        if (pending.remove(existing)) {
                            existing.priority = priority
                            pending.add(existing)
                        } else {
                            existing.priority = priority
                        }
                    }
                    existing
                } else {
                    Entry(item.key, priority, item.receivedAtMillis, nextSeq++, work).also {
                        byKey[item.key] = it
                        enqueueLocked(it)
                    }
                }.also { if (priority == AiPriority.FOREGROUND) preemptBulkLocked() }
            }
        @Suppress("UNCHECKED_CAST")
        return await(entry) as AiResult<AiClassification>
    }

    /** Runs any other model call (summary, draft, rewrite …) as a foreground request. */
    suspend fun <T> foreground(block: suspend (AiEngine) -> T): T {
        val entry =
            synchronized(lock) {
                Entry(null, AiPriority.FOREGROUND, 0, nextSeq++, block).also {
                    enqueueLocked(it)
                    preemptBulkLocked()
                }
            }
        @Suppress("UNCHECKED_CAST")
        return await(entry) as T
    }

    private suspend fun await(entry: Entry): Any? =
        try {
            entry.result.await()
        } catch (e: CancellationException) {
            // Only our caller's cancellation lands here (entries never complete as cancelled).
            synchronized(lock) {
                if (--entry.waiters == 0 && !entry.result.isCompleted) {
                    if (pending.remove(entry)) {
                        forgetLocked(entry)
                    } else {
                        entry.cancelledByQueue = true
                        entry.execution?.cancel()
                    }
                }
            }
            throw e
        }

    private fun enqueueLocked(entry: Entry) {
        pending.add(entry)
        if (runner?.isActive != true) runner = scope.launch { runLoop() }
    }

    private fun preemptBulkLocked() {
        val running = current ?: return
        if (running.priority == AiPriority.BULK && !running.cancelledByQueue) {
            running.cancelledByQueue = true
            running.execution?.cancel()
        }
    }

    private fun forgetLocked(entry: Entry) {
        if (entry.key != null && byKey[entry.key] === entry) byKey.remove(entry.key)
    }

    private suspend fun runLoop() {
        while (true) {
            val entry =
                synchronized(lock) {
                    val next = pending.pollFirst()
                    current = next
                    if (next == null) runner = null
                    next
                } ?: return
            val value =
                try {
                    coroutineScope {
                        val exec = async { execute(entry) }
                        synchronized(lock) {
                            entry.execution = exec
                            if (entry.waiters == 0) entry.cancelledByQueue = true
                            if (entry.cancelledByQueue) exec.cancel()
                        }
                        exec.await()
                    }
                } catch (e: CancellationException) {
                    // Only the queue's own cancellations get here ([execute] turns the work's
                    // own ones into failures), or the queue's scope is shutting down.
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        current = null
                        entry.execution = null
                        entry.cancelledByQueue = false
                        // Preempted, or abandoned and then joined again: someone still waits.
                        if (entry.waiters > 0) pending.add(entry) else forgetLocked(entry)
                    }
                    continue
                } catch (e: Exception) {
                    synchronized(lock) {
                        current = null
                        forgetLocked(entry)
                    }
                    entry.result.completeExceptionally(e)
                    continue
                }
            synchronized(lock) {
                current = null
                forgetLocked(entry)
                if (entry.key != null) {
                    @Suppress("UNCHECKED_CAST")
                    (value as AiResult<AiClassification>).getOrNull()?.let { cache[entry.key] = it }
                }
            }
            entry.result.complete(value)
        }
    }

    /**
     * Runs the work. A [CancellationException] while this execution is still active came from the
     * work itself (an engine cancelling its future, a timeout inside a foreground block): it is a
     * failure, delivered to the waiters, never a re-run and never a cancellation of the caller.
     */
    private suspend fun execute(entry: Entry): Any? =
        try {
            entry.work(engine)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (entry.key !=
                null
            ) {
                AiResult.Failed(AiFailure.EngineError("cancelled by engine"))
            } else {
                throw WorkCancelledException(e)
            }
        } catch (e: Exception) {
            if (entry.key != null) AiResult.Failed(AiFailure.EngineError(e.javaClass.simpleName)) else throw e
        }

    companion object {
        const val DEFAULT_CACHE_SIZE = 2_000
    }
}

/** A [AiQueue.foreground] block ended in its own (not its caller's) cancellation. */
class WorkCancelledException(
    cause: CancellationException,
) : IllegalStateException("AI work was cancelled from inside: ${cause.message}", cause)
