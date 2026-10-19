package tech.elephantclock.privatemail.feature.inbox

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.feature.shell.WindowInfo
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureScreen
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

val suppressedSample =
    listOf(
        SuppressedItem(11, "Irina (partner)", "Contract signed copy", "14:20"),
        SuppressedItem(12, "Bank notices", "Unusual sign-in", "09:02"),
    )

/** The suppressed-alert badge (T49 carry-over): hidden at zero, counts, lists, opens. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class SuppressedBadgeTest {
    @get:Rule
    val compose = createComposeRule()

    private val rows = ReadingFixtures.priority.map { it.toRow(ReadingFixtures.times) }
    private val state = InboxUiState(accounts = listOf(AccountChip("acc-work", "work.example", 0, 5)))

    @Composable
    private fun Priority(
        count: Int,
        onOpen: (Long) -> Unit = {},
    ) {
        val items = remember { flowOf(staticPage(rows)) }.collectAsLazyPagingItems()
        InboxScreen(
            state,
            items,
            selectedId = null,
            actions = ListActions(onOpen = onOpen),
            suppressedCount = count,
            suppressed = suppressedSample.take(count),
        )
    }

    @Test
    fun hiddenWhenNothingWasSuppressed() {
        compose.setContent { PrivateMailTheme { Priority(0) } }
        compose.onNodeWithTag("suppressed_badge").assertDoesNotExist()
    }

    @Test
    fun countsAndOpensTheSuppressedMessages() {
        val opened = mutableListOf<Long>()
        compose.setContent { PrivateMailTheme { Priority(2) { opened += it } } }
        compose.onNodeWithText("2 messages didn’t alert: sender checks failed").assertIsDisplayed()
        compose.onNodeWithTag("suppressed:12").assertDoesNotExist()
        compose.onNodeWithTag("suppressed_badge").performClick()
        compose.onNodeWithTag("suppressed:12").performClick()
        assertEquals(listOf(12L), opened)
    }

    @Test
    fun singular() {
        compose.setContent { PrivateMailTheme { Priority(1) } }
        compose.onNodeWithText("1 message didn’t alert: sender checks failed").assertIsDisplayed()
    }
}

/** Screenshots: the badge in the Priority list (cover and Flex), and opened. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class SuppressedBadgeScreenshotTest {
    private val rows = ReadingFixtures.priority.map { it.toRow(ReadingFixtures.times) }
    private val state =
        InboxUiState(
            accounts = listOf(AccountChip("acc-work", "work.example", 0, 5)),
            today = TodaySummary(filed = 38, discarded = 61, labelOnly = false),
        )

    @Composable
    private fun Priority(info: WindowInfo) {
        AppShell(windowInfo = info, listPane = { _, _, _ ->
            val items = remember { flowOf(staticPage(rows)) }.collectAsLazyPagingItems()
            InboxScreen(state, items, null, ListActions(), suppressedCount = 2, suppressed = suppressedSample)
        })
    }

    @Test
    fun badge() {
        listOf(ScreenSize.Cover, ScreenSize.Flex).forEach { size ->
            listOf(false, true).forEach { dark ->
                captureScreen("inbox_suppressed_badge", size, dark) { info -> Priority(info) }
            }
        }
    }

    @Test
    fun opened() {
        listOf(false, true).forEach { dark ->
            captureScreen("inbox_suppressed_open", ScreenSize.Cover, dark) {
                Column { SuppressedBadge(2, suppressedSample, onOpen = {}, initiallyOpen = true) }
            }
        }
    }
}
