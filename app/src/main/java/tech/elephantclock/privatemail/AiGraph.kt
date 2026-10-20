package tech.elephantclock.privatemail

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.elephantclock.privatemail.ai.AiBacklog
import tech.elephantclock.privatemail.ai.ForegroundAiDrain
import tech.elephantclock.privatemail.ai.QueueTriageAi
import tech.elephantclock.privatemail.core.ai.AiEngine
import tech.elephantclock.privatemail.core.ai.AiFeature
import tech.elephantclock.privatemail.core.ai.isAvailable
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitAiEngine
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitAvailability
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitClassifier
import tech.elephantclock.privatemail.core.ai.mlkit.MlKitPromptModel
import tech.elephantclock.privatemail.core.ai.mlkit.PromptModel
import tech.elephantclock.privatemail.core.ai.queue.AiPriority
import tech.elephantclock.privatemail.core.ai.queue.AiQueue
import tech.elephantclock.privatemail.core.ai.safety.SafeAiEngine
import tech.elephantclock.privatemail.core.data.triage.BacklogCursor
import tech.elephantclock.privatemail.core.data.triage.BacklogPass
import tech.elephantclock.privatemail.core.data.triage.TriageAi

/**
 * The on-device AI's objects (T31), kept beside [MailGraph] so that file stays the coordinator's.
 * One per process: one model client, one queue (single flight), one availability cache.
 *
 * Coordinator wiring (see each member):
 * - `MailGraph.orchestrator`: `TriageOrchestrator(..., ai = ai(appContext).triageAi, ...)`.
 * - `PrivateMailApp.onCreate`: `graph.ai(this).foregroundDrain().start()` next to `ForegroundSync`.
 * - T32/T33 plug their ML Kit components into [engine] (`MlKitAiEngine` constructor parameters)
 *   and use [queue] (`queue.foreground { it.summarize(…) }`) and [availability].
 */
class AiGraph internal constructor(
    context: Context,
    private val graph: MailGraph,
    private val process: LifecycleOwner = ProcessLifecycleOwner.get(),
) {
    private val appContext = context.applicationContext

    /** GenAI serves only the top foreground app: an activity of ours is resumed. */
    fun isForeground(): Boolean = process.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    val model: PromptModel by lazy { MlKitPromptModel() }

    /** Gemini Nano's status, probed in the foreground (the Fold7's `checkStatus()` is stale). */
    val availability: MlKitAvailability by lazy {
        MlKitAvailability(model, ::isForeground, { aicoreVersion(appContext) })
    }

    val classifier: MlKitClassifier by lazy { MlKitClassifier(model, onModelGone = availability::invalidate) }

    /** Redacting, injection-guarding engine; T32/T33 add their components to [MlKitAiEngine] here. */
    val engine: AiEngine by lazy { SafeAiEngine(MlKitAiEngine(classifier)) }

    /** Every model call goes through this queue: one at a time, foreground first. */
    val queue: AiQueue by lazy { AiQueue(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    private fun canRun() = isForeground() && availability.isAvailable(AiFeature.PROMPT)

    /** For `MailGraph.orchestrator`: new mail while the app is open; rules only otherwise. */
    val triageAi: TriageAi by lazy {
        QueueTriageAi(queue, graph.onboarding(appContext).triageSettings, ::canRun, AiPriority.FOREGROUND)
    }

    /** The rules-only backlog: bulk priority, so anything the user is waiting for goes first. */
    private val backlogAi: TriageAi by lazy {
        QueueTriageAi(queue, graph.onboarding(appContext).triageSettings, ::canRun, AiPriority.BULK)
    }

    /** Start once from `PrivateMailApp.onCreate`; drains while the app is resumed. */
    fun foregroundDrain(): ForegroundAiDrain =
        ForegroundAiDrain(
            process,
            object : AiBacklog {
                override suspend fun prepare(): Boolean {
                    queue.foreground { availability.refresh() }
                    return canRun()
                }

                override suspend fun pass(
                    after: BacklogCursor,
                    limit: Int,
                ): BacklogPass = graph.orchestrator.reclassifyRulesOnly(after, limit, backlogAi)
            },
        )

    internal companion object {
        private const val AICORE = "com.google.android.aicore"

        @Volatile private var instance: AiGraph? = null

        fun of(
            context: Context,
            graph: MailGraph,
        ): AiGraph =
            instance?.takeIf { it.graph === graph } ?: synchronized(this) {
                instance?.takeIf { it.graph === graph } ?: AiGraph(context, graph).also { instance = it }
            }

        /** AICore's last update: a cached probe result holds only for the same AICore. */
        fun aicoreVersion(context: Context): Long? =
            try {
                context.packageManager.getPackageInfo(AICORE, 0).lastUpdateTime
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
    }
}

/** The AI objects for this graph (one per process). */
fun MailGraph.ai(context: Context): AiGraph = AiGraph.of(context, this)
