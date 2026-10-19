package tech.elephantclock.privatemail.feature.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.OnboardingStep
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Status

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val data = FakeOnboardingData()
    private val work = FakeWork()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): FirstRunViewModel {
        work.state.value = sorting(10, 100)
        return FirstRunViewModel(ACCOUNT, data, work)
    }

    private fun FirstRunViewModel.finishSort() {
        work.state.value = FirstRunWorkState(Mode.SORT, Status.SUCCEEDED)
    }

    @Test
    fun `follows the worker, then shows the review`() {
        val vm = vm()
        assertTrue(vm.state.value.busy)
        assertFalse(vm.state.value.canApply)

        vm.finishSort()

        val review = checkNotNull(vm.state.value.review)
        assertEquals(2060, review.total)
        assertEquals(412, review.keepCount)
        assertEquals(1648, review.discardCount)
        assertEquals(388, review.fileCount)
        assertTrue(vm.state.value.canApply)
    }

    @Test
    fun `Apply is refused until the Sent backfill is complete`() {
        data.backfill = BackfillStatus.PENDING
        val vm = vm()
        vm.finishSort()

        assertFalse(vm.state.value.canApply)
        vm.onApply()
        assertTrue("nothing handed to the worker", Mode.APPLY !in work.calls)

        data.backfill = BackfillStatus.NO_SENT_FOLDER
        work.state.value = FirstRunWorkState(Mode.PREPARE, Status.SUCCEEDED)
        assertFalse(vm.state.value.canApply)
    }

    @Test
    fun `Apply hands the moves to the worker`() {
        val vm = vm()
        vm.finishSort()
        vm.onApply()
        assertEquals(listOf(Mode.APPLY), work.calls)
        assertTrue(vm.state.value.busy)
        assertFalse("auto-file is set by the worker after the moves, not here", data.prefs.value.autoFileRequested)
    }

    @Test
    fun `Label only finishes onboarding with auto-file off and no work`() {
        val vm = vm()
        vm.finishSort()
        vm.onLabelOnly()
        assertEquals(OnboardingStep.DONE, data.prefs.value.step)
        assertFalse(data.prefs.value.autoFileRequested)
        assertTrue(work.calls.isEmpty())
    }

    @Test
    fun `drill-down lists a group's messages and keeping one takes it out of the discards`() {
        val vm = vm()
        vm.finishSort()
        val discard = checkNotNull(vm.state.value.review).discard.first { it.action == VerdictAction.DISCARD }

        vm.onOpenGroup(discard)
        assertEquals(discard, vm.state.value.drillDown)
        assertEquals(
            3,
            vm.state.value.items
                ?.size,
        )

        vm.onKeep(SAMPLE_ITEMS[0])
        assertEquals(listOf(1L), data.kept)
        assertEquals(
            2,
            vm.state.value.items
                ?.size,
        )
        assertEquals(1647, checkNotNull(vm.state.value.review).discardCount)

        vm.onCloseGroup()
        assertNull(vm.state.value.drillDown)
    }

    @Test
    fun `a switched-off folder's mail stays in Priority in the review`() {
        data.prefs.value = data.prefs.value.copy(disabledFolders = setOf(FolderRole.WORK))
        val vm = vm()
        vm.finishSort()
        val review = checkNotNull(vm.state.value.review)
        assertEquals(24 + 214, review.keep.first { it.target.role == FolderRole.PRIORITY }.count)
        assertTrue(review.keep.none { it.target.role == FolderRole.WORK })
    }

    @Test
    fun `a failed sort can be retried`() {
        val vm = vm()
        work.state.value = FirstRunWorkState(Mode.SORT, Status.FAILED)
        assertTrue(vm.state.value.failed)
        vm.onRetry()
        assertEquals(listOf(Mode.SORT), work.calls)
    }
}
