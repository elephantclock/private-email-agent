package tech.elephantclock.privatemail.feature.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.core.data.settings.CustomFolder
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.OnboardingStep
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode

@OptIn(ExperimentalCoroutinesApi::class)
class PersonaliseViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val data = FakeOnboardingData()
    private val work = FakeWork()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PersonaliseViewModel(ACCOUNT, data, work)

    @Test
    fun `opening the screen starts the sync and Sent backfill, and shows people from Sent`() {
        val vm = vm()
        assertEquals(listOf(Mode.PREPARE), work.calls)
        assertEquals(SAMPLE_PEOPLE, vm.state.value.people)
        work.state.value = FirstRunWorkState(Mode.PREPARE, FirstRunWorkState.Status.RUNNING)
        assertTrue(vm.state.value.readingSent)
    }

    @Test
    fun `VIPs are saved lower-case without the plus tag, and toggle off again`() {
        val vm = vm()
        vm.onToggleVip("Irina+Work@Partner.example")
        assertEquals(setOf("irina@partner.example"), data.prefs.value.vips)
        assertTrue(vm.state.value.isVip(SAMPLE_PEOPLE[0]))
        vm.onToggleVip("irina@partner.example")
        assertEquals(emptySet<String>(), data.prefs.value.vips)
    }

    @Test
    fun `folder switches and the discard mode are saved`() {
        val vm = vm()
        vm.onToggleFolder(FolderRole.WORK)
        assertEquals(setOf(FolderRole.WORK), data.prefs.value.disabledFolders)
        assertFalse(
            vm.state.value.prefs
                .folderEnabled(FolderRole.WORK),
        )
        vm.onToggleFolder(FolderRole.WORK)
        assertTrue(
            data.prefs.value.disabledFolders
                .isEmpty(),
        )
        vm.onDiscardMode(DiscardMode.STRAIGHT_TO_TRASH)
        assertEquals(DiscardMode.STRAIGHT_TO_TRASH, data.prefs.value.discardMode)
    }

    @Test
    fun `a custom folder is described in plain words`() {
        val vm = vm()
        vm.onCustomDraftChange("Partners: anyone asking to integrate or resell our product")
        vm.onAddCustomFolder()
        assertEquals(
            listOf(CustomFolder("Partners", "anyone asking to integrate or resell our product")),
            data.prefs.value.customFolders,
        )
        assertEquals("", vm.state.value.customDraft)

        vm.onCustomDraftChange("partners: someone else")
        vm.onAddCustomFolder()
        assertEquals(CustomFolderProblem.DUPLICATE, vm.state.value.customProblem)
        vm.onCustomDraftChange("   ")
        vm.onAddCustomFolder()
        assertEquals(CustomFolderProblem.EMPTY, vm.state.value.customProblem)

        vm.onRemoveCustomFolder("Partners")
        assertTrue(
            data.prefs.value.customFolders
                .isEmpty(),
        )
    }

    @Test
    fun `parsing a folder without a colon names it after its first words`() {
        val parsed = parseCustomFolder("Conference speakers and their agents", emptyList())
        assertEquals(
            ParsedFolder.Ok(CustomFolder("Conference speakers and", "Conference speakers and their agents")),
            parsed,
        )
        assertEquals(
            ParsedFolder.Problem(CustomFolderProblem.DUPLICATE),
            parseCustomFolder("money: bills", emptyList()),
        )
        assertEquals(
            ParsedFolder.Problem(CustomFolderProblem.TOO_LONG),
            parseCustomFolder("x".repeat(41) + ": y", emptyList()),
        )
    }

    @Test
    fun `Sort my last 30 days moves to the first run and starts the label-only sort`() {
        val vm = vm()
        vm.onSort()
        assertEquals(OnboardingStep.FIRST_RUN, data.prefs.value.step)
        assertEquals(listOf(Mode.PREPARE, Mode.SORT), work.calls)
        assertFalse("sorting never turns auto-file on", data.prefs.value.autoFileRequested)
    }
}
