package tech.elephantclock.privatemail.feature.inbox

import androidx.paging.testing.asSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.core.data.db.FolderSummary
import tech.elephantclock.privatemail.core.data.db.TriageCounts
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.ui.components.ImportanceLevel

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private val source = FakeInboxSource()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(mode: InboxMode = InboxMode.PRIORITY) = InboxViewModel(source, mode, ReadingFixtures.times)

    /** Keeps [InboxViewModel.state] subscribed (it's WhileSubscribed) for the test's duration. */
    private fun kotlinx.coroutines.test.TestScope.watch(vm: InboxViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    @Test
    fun `every domain importance maps to the pill level of the same name`() {
        assertEquals(
            listOf(ImportanceLevel.Urgent, ImportanceLevel.High, ImportanceLevel.Normal, ImportanceLevel.Low),
            Importance.entries.map { it.toLevel() },
        )
    }

    @Test
    fun `priority lists every account until one is chosen, then only that one`() =
        runTest {
            val vm = vm()
            watch(vm)
            assertEquals(listOf(1L, 2, 3, 4, 5), vm.messages.asSnapshot().map { it.id })
            val chips = vm.state.value.accounts
            assertEquals(listOf("work.example" to 4, "home.example" to 1), chips.map { it.label to it.priorityCount })
            assertEquals(5, vm.state.value.priorityTotal)

            vm.selectAccount("acc-home")
            assertEquals(listOf(4L), vm.messages.asSnapshot().map { it.id })
            assertEquals("acc-home", source.priorityRequests.last())
        }

    @Test
    fun `rows show the pill, the AI summary over the preview, unread and today's time`() =
        runTest {
            val rows = vm().messages.asSnapshot()
            val first = rows.first()
            assertEquals(ImportanceLevel.Urgent, first.level)
            assertEquals("15:02", first.time)
            assertTrue(first.unread)
            assertEquals("Wants a proposal and a call next week; 12 properties.", rows[3].preview)
            assertTrue(rows[3].hasAttachments)
            assertFalse(rows[2].unread)
            assertEquals("17 Oct", rows[4].time)
        }

    @Test
    fun `today card counts from midnight and says when mail only got a label`() =
        runTest {
            source.counts = TriageCounts(filed = 38, discarded = 61, stillInInbox = 99)
            val vm = vm()
            watch(vm)
            assertEquals(TodaySummary(38, 61, labelOnly = true), vm.state.value.today)
            assertEquals(1_792_281_600_000L, source.countRequests.last().first) // 18 Oct 00:00 UTC
        }

    @Test
    fun `no card on a day nothing was triaged`() =
        runTest {
            val vm = vm()
            watch(vm)
            assertNull(vm.state.value.today)
        }

    @Test
    fun `folders show one account, Inbox first by default, and follow the chosen folder`() =
        runTest {
            source.folderLists["acc-work"] =
                listOf(
                    FolderSummary("acc-work", "INBOX", "INBOX", null, null, 4, 2),
                    FolderSummary("acc-work", "Sent", "Sent", null, null, 9, 0),
                )
            val vm = vm(InboxMode.FOLDERS)
            watch(vm)
            vm.messages.asSnapshot()
            assertEquals("acc-work", vm.state.value.selectedAccount)
            assertEquals("INBOX", vm.state.value.selectedFolder)
            assertEquals("acc-work" to "INBOX", source.folderRequests.last())

            vm.selectFolder("Sent")
            vm.messages.asSnapshot()
            assertEquals("acc-work" to "Sent", source.folderRequests.last())
            assertNull(vm.state.value.today)
        }

    @Test
    fun `search runs as the user types and respects the account filter`() =
        runTest {
            val vm = vm()
            watch(vm)
            vm.openSearch()
            vm.setQuery("p")
            assertEquals(
                listOf(1L, 2, 3, 5),
                vm.state.value.results!!
                    .map { it.id },
            )
            vm.selectAccount("acc-work")
            vm.setQuery("pa")
            assertEquals(
                listOf(1L, 2, 3),
                vm.state.value.results!!
                    .map { it.id },
            )
            vm.closeSearch()
            assertFalse(vm.state.value.searching)
            assertNull(vm.state.value.results)
        }

    @Test
    fun `pull to refresh syncs and reports problems`() =
        runTest {
            source.refreshResult = { RefreshOutcome.PARTLY_FAILED }
            val vm = vm()
            watch(vm)
            vm.refresh()
            assertEquals(1, source.refreshes)
            assertEquals(RefreshOutcome.PARTLY_FAILED, vm.state.first().refreshProblem)
            vm.dismissProblem()
            assertNull(vm.state.value.refreshProblem)
        }

    @Test
    fun `a sync that throws ends the spinner with a problem instead of crashing`() =
        runTest {
            source.refreshResult = { error("boom") }
            val vm = vm()
            watch(vm)
            vm.refresh()
            assertFalse(vm.state.value.refreshing)
            assertEquals(RefreshOutcome.PARTLY_FAILED, vm.state.value.refreshProblem)
        }
}
