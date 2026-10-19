package tech.elephantclock.privatemail.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunProgress
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunResult
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunSteps
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.triage.MoveOutcome
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FirstRunWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = AccountId("acc-1")

    private class FakeSteps(
        var result: FirstRunResult = FirstRunResult.Done,
    ) : FirstRunSteps {
        val calls = mutableListOf<String>()

        override suspend fun prepare(
            id: AccountId,
            progress: suspend (FirstRunProgress) -> Unit,
        ): FirstRunResult {
            calls += "prepare:${id.value}"
            progress(FirstRunProgress.ReadingSent(5, 10))
            return result
        }

        override suspend fun sort(
            id: AccountId,
            progress: suspend (FirstRunProgress) -> Unit,
        ): FirstRunResult {
            calls += "sort:${id.value}"
            progress(FirstRunProgress.Sorting(3, 4))
            return result
        }

        override suspend fun apply(
            id: AccountId,
            progress: suspend (FirstRunProgress) -> Unit,
        ): FirstRunResult {
            calls += "apply:${id.value}"
            return result
        }
    }

    private fun worker(
        steps: FirstRunSteps,
        input: Data,
        attempt: Int = 0,
    ): FirstRunWorker =
        TestListenableWorkerBuilder<FirstRunWorker>(context)
            .setInputData(input)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = FirstRunWorker(appContext, workerParameters) { steps }
                },
            ).build()

    @Test
    fun `each mode runs its step`() =
        runBlocking {
            val steps = FakeSteps()
            Mode.entries.forEach { mode ->
                assertEquals(
                    ListenableWorker.Result.success(),
                    worker(steps, FirstRunWorker.input(account, mode)).doWork(),
                )
            }
            assertEquals(listOf("prepare:acc-1", "sort:acc-1", "apply:acc-1"), steps.calls)
        }

    @Test
    fun `a network problem is retried a few times, then reported as failed`() =
        runBlocking {
            val steps = FakeSteps(FirstRunResult.Retry("offline"))
            val input = FirstRunWorker.input(account, Mode.SORT)
            assertEquals(ListenableWorker.Result.retry(), worker(steps, input).doWork())
            assertEquals(
                ListenableWorker.Result.failure(),
                worker(steps, input, attempt = FirstRunWorker.MAX_ATTEMPTS - 1).doWork(),
            )
        }

    @Test
    fun `problems that need the user fail at once`() =
        runBlocking {
            val blocked =
                worker(
                    FakeSteps(FirstRunResult.Blocked("password")),
                    FirstRunWorker.input(account, Mode.SORT),
                ).doWork()
            assertEquals(ListenableWorker.Result.failure(workDataOf(FirstRunWorker.KEY_BLOCKED to true)), blocked)
            val notReady =
                worker(
                    FakeSteps(FirstRunResult.NotReady(BackfillStatus.PENDING)),
                    FirstRunWorker.input(account, Mode.APPLY),
                ).doWork()
            assertTrue(notReady is ListenableWorker.Result.Failure)
            val applied =
                worker(
                    FakeSteps(FirstRunResult.Applied(MoveOutcome(moved = 3), 0)),
                    FirstRunWorker.input(account, Mode.APPLY),
                ).doWork()
            assertEquals(ListenableWorker.Result.success(), applied)
        }

    @Test
    fun `bad input does nothing`() =
        runBlocking {
            val steps = FakeSteps()
            assertEquals(
                ListenableWorker.Result.failure(),
                worker(steps, workDataOf(FirstRunWorker.KEY_MODE to "SORT")).doWork(),
            )
            assertEquals(
                ListenableWorker.Result.failure(),
                worker(
                    steps,
                    workDataOf(FirstRunWorker.KEY_ACCOUNT to "a", FirstRunWorker.KEY_MODE to "NUKE"),
                ).doWork(),
            )
            assertTrue(steps.calls.isEmpty())
        }

    @Test
    fun `the chain's state is its first unfinished step, with its progress`() {
        fun info(
            state: WorkInfo.State,
            mode: Mode,
            progress: Data = Data.EMPTY,
        ) = WorkInfo(UUID.randomUUID(), state, setOf("first-run-mode:${mode.name}"), Data.EMPTY, progress, 0, 0)

        assertEquals(FirstRunWorkState(), WorkManagerFirstRunWork.stateOf(emptyList()))
        val running =
            WorkManagerFirstRunWork.stateOf(
                listOf(
                    info(WorkInfo.State.SUCCEEDED, Mode.PREPARE),
                    info(
                        WorkInfo.State.RUNNING,
                        Mode.SORT,
                        with(FirstRunWorker) { FirstRunProgress.Sorting(3, 4).toData() },
                    ),
                ),
            )
        assertEquals(
            FirstRunWorkState(Mode.SORT, FirstRunWorkState.Status.RUNNING, FirstRunWorkState.Stage.SORT, 3, 4),
            running,
        )
        val done =
            WorkManagerFirstRunWork.stateOf(
                listOf(info(WorkInfo.State.SUCCEEDED, Mode.PREPARE), info(WorkInfo.State.FAILED, Mode.SORT)),
            )
        assertEquals(FirstRunWorkState(Mode.SORT, FirstRunWorkState.Status.FAILED), done)
    }
}
