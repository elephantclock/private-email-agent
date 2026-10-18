package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.feature.shell.ShellDestination
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes
import tech.elephantclock.privatemail.ui.screenshot.captureScreen

/** Priority and Folders at the four sizes, light and dark, from synthetic rows. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class InboxScreenshotTest {
    private val rows = ReadingFixtures.priority.map { it.toRow(ReadingFixtures.times) }
    private val chips =
        listOf(
            AccountChip("acc-work", "work.example", 0, priorityCount = 4),
            AccountChip("acc-home", "home.example", 1, priorityCount = 1),
        )

    private val priority =
        InboxUiState(accounts = chips, today = TodaySummary(filed = 38, discarded = 61, labelOnly = false))

    private val folders =
        InboxUiState(
            accounts = chips.take(1),
            selectedAccount = "acc-work",
            folders =
                listOf(
                    FolderItem("INBOX", "INBOX", 4, 2),
                    FolderItem("Money", "Money", 12, 1),
                    FolderItem("Sent", "Sent", 30, 0),
                ),
            selectedFolder = "INBOX",
        )

    @Composable
    private fun List(
        state: InboxUiState,
        destination: ShellDestination,
        rows: kotlin.collections.List<MessageRow>,
    ) {
        val items = remember(rows) { flowOf(staticPage(rows)) }.collectAsLazyPagingItems()
        if (destination == ShellDestination.Folders) {
            FolderListScreen(state, items, selectedId = null, actions = ListActions())
        } else {
            InboxScreen(state, items, selectedId = null, actions = ListActions())
        }
    }

    @Test
    fun priority() {
        captureAllSizes("inbox_priority") { info ->
            AppShell(windowInfo = info, listPane = { _, _, _ -> List(priority, ShellDestination.Priority, rows) })
        }
    }

    @Test
    fun folders() {
        val inbox = rows.filter { it.accountId == "acc-work" }
        captureAllSizes("inbox_folders") { info ->
            AppShell(
                windowInfo = info,
                listPane = { _, _, _ -> List(folders, ShellDestination.Folders, inbox) },
                initialDestination = ShellDestination.Folders,
            )
        }
    }

    @Test
    fun emptyAndLabelOnly() {
        val state = priority.copy(today = TodaySummary(3, 12, labelOnly = true))
        listOf(false, true).forEach { dark ->
            captureScreen("inbox_empty", ScreenSize.Cover, dark) { info ->
                AppShell(windowInfo = info, listPane = {
                    _,
                    _,
                    _,
                    ->
                    List(state, ShellDestination.Priority, emptyList())
                })
            }
        }
    }

    @Test
    fun search() {
        val state = priority.copy(searching = true, query = "pay", results = rows.take(2))
        captureScreen("inbox_search", ScreenSize.Cover, dark = false) { info ->
            AppShell(windowInfo = info, listPane = { _, _, _ -> List(state, ShellDestination.Priority, rows) })
        }
    }
}
