package tech.elephantclock.privatemail.ai

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.core.data.triage.BacklogCursor
import tech.elephantclock.privatemail.core.data.triage.BacklogPass

/** The work the foreground drain does; the app backs it with the availability, the queue and the orchestrator. */
interface AiBacklog {
    /** Re-checks (and if needed probes) the model, in the foreground; true when it can run. */
    suspend fun prepare(): Boolean

    /** Re-classifies up to [limit] rules-only messages older than [after], newest first. */
    suspend fun pass(
        after: BacklogCursor,
        limit: Int,
    ): BacklogPass
}

/**
 * Classifies mail that background triage left rules-only (GenAI runs only for the top foreground
 * app), while the app is in the foreground: started when [owner] (the process lifecycle) is
 * RESUMED, cancelled when it drops below, which also cancels the model call in flight.
 *
 * Each time the app comes to the front: one [AiBacklog.prepare], then small batches, newest
 * first, down to the oldest. Batches are small so a verdict is stored every few seconds and
 * little is lost when the user leaves. A batch where the model classified nothing (busy,
 * quota, failing) waits [retryDelayMillis]; after [maxIdleBatches] such batches in a row the
 * drain stops until the next return to the foreground.
 */
class ForegroundAiDrain(
    private val owner: LifecycleOwner,
    private val backlog: AiBacklog,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val batchSize: Int = BATCH_SIZE,
    private val maxIdleBatches: Int = MAX_IDLE_BATCHES,
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
) {
    fun start(): Job =
        owner.lifecycleScope.launch(dispatcher) {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    val done = drain()
                    if (done > 0) Log.i(TAG, "classified $done messages")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never crash the app; the next return to the foreground starts over.
                    Log.w(TAG, "AI drain stopped: ${e.javaClass.simpleName}")
                }
            }
        }

    /** One foreground pass; returns how many messages the model classified. */
    suspend fun drain(): Int {
        if (!backlog.prepare()) return 0
        var cursor = BacklogCursor.START
        var idle = 0
        var done = 0
        while (true) {
            val pass = backlog.pass(cursor, batchSize)
            if (pass.considered == 0) break
            cursor = pass.next
            done += pass.report.byAi
            if (pass.report.byAi > 0) {
                idle = 0
            } else {
                if (++idle >= maxIdleBatches) break
                delay(retryDelayMillis)
            }
        }
        return done
    }

    companion object {
        const val BATCH_SIZE = 5
        const val MAX_IDLE_BATCHES = 2
        const val RETRY_DELAY_MILLIS = 30_000L
        private const val TAG = "ForegroundAiDrain"
    }
}
