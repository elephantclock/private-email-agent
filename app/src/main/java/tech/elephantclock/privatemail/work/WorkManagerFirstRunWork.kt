package tech.elephantclock.privatemail.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWork
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Stage
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Status
import java.util.concurrent.TimeUnit

/**
 * One unique work chain per account. Prepare is kept if it is already queued or running; Sort
 * and Apply are appended, so they start only after what came before (a failed chain is
 * replaced instead).
 */
class WorkManagerFirstRunWork(
    private val workManager: WorkManager,
) : FirstRunWork {
    override fun prepare(id: AccountId) = enqueue(id, Mode.PREPARE, ExistingWorkPolicy.KEEP)

    override fun sort(id: AccountId) = enqueue(id, Mode.SORT, ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun apply(id: AccountId) = enqueue(id, Mode.APPLY, ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun state(id: AccountId): Flow<FirstRunWorkState> =
        workManager
            .getWorkInfosForUniqueWorkFlow(uniqueName(id))
            .map(::stateOf)
            .distinctUntilChanged()

    private fun enqueue(
        id: AccountId,
        mode: Mode,
        policy: ExistingWorkPolicy,
    ) {
        workManager.enqueueUniqueWork(uniqueName(id), policy, request(id, mode))
    }

    companion object {
        fun uniqueName(id: AccountId) = "first-run:${id.value}"

        fun request(
            id: AccountId,
            mode: Mode,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<FirstRunWorker>()
                .setInputData(FirstRunWorker.input(id, mode))
                .addTag(TAG_PREFIX + mode.name)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

        private const val TAG_PREFIX = "first-run-mode:"

        /** The chain's current step: the first one not finished, else the last one. */
        fun stateOf(infos: List<WorkInfo>): FirstRunWorkState {
            if (infos.isEmpty()) return FirstRunWorkState()
            val current = infos.firstOrNull { !it.state.isFinished } ?: infos.last()
            val mode = current.tags.firstNotNullOfOrNull { tag -> tag.removePrefix(TAG_PREFIX).takeIf { it != tag } }
            val status =
                when (current.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> Status.RUNNING
                    WorkInfo.State.SUCCEEDED -> Status.SUCCEEDED
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Status.FAILED
                }
            val p = current.progress
            return FirstRunWorkState(
                mode = mode?.let { runCatching { Mode.valueOf(it) }.getOrNull() },
                status = status,
                stage = p.getString(FirstRunWorker.KEY_STAGE)?.let { runCatching { Stage.valueOf(it) }.getOrNull() },
                done = p.getInt(FirstRunWorker.KEY_DONE, 0),
                total = p.getInt(FirstRunWorker.KEY_TOTAL, 0),
                blocked = current.outputData.getBoolean(FirstRunWorker.KEY_BLOCKED, false),
            )
        }
    }
}
