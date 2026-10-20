package tech.elephantclock.privatemail.feature.detail

import androidx.compose.runtime.remember
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.feature.inbox.AccountChip
import tech.elephantclock.privatemail.feature.inbox.InboxScreen
import tech.elephantclock.privatemail.feature.inbox.InboxUiState
import tech.elephantclock.privatemail.feature.inbox.ListActions
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures
import tech.elephantclock.privatemail.feature.inbox.staticPage
import tech.elephantclock.privatemail.feature.inbox.toRow
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.feature.shell.ShellDetailPane
import tech.elephantclock.privatemail.feature.shell.ShellListPane
import tech.elephantclock.privatemail.ui.components.ImportanceLevel
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureScreen

/** The thread brief (T32) from a fake summarizer: ready, loading and fallback; cover and inner. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class BriefScreenshotTest {
    private val rows = ReadingFixtures.priority.map { it.toRow(ReadingFixtures.times) }

    private val list: ShellListPane = { _, selected, _ ->
        val items = remember { flowOf(staticPage(rows)) }.collectAsLazyPagingItems()
        InboxScreen(
            InboxUiState(accounts = listOf(AccountChip("acc-work", "work.example", 0, 5))),
            items,
            selectedId = selected?.toLongOrNull(),
            actions = ListActions(),
        )
    }

    private val base =
        DetailUiState(
            loading = false,
            header =
                DetailHeader(
                    subject = "Chatbot for our hotels",
                    fromName = "Prospective client",
                    fromEmail = "person4@sender.example",
                    to = "Sam Tester",
                    cc = null,
                    date = "18 Oct 2026, 10:41",
                    account = null,
                ),
            triage =
                TriageBrief(
                    ImportanceLevel.High,
                    VerdictAction.KEEP,
                    FolderRole.PRIORITY,
                    null,
                    "Replied before",
                    false,
                ),
            brief =
                "We would like a proposal and a call next week for our 12 properties. " +
                    "Our budget review is on Friday.",
            aiAvailable = true,
            body = BodyState.Loaded("Hello Sam,\n\nWe would like a proposal.", false, false),
            attachments = listOf(AttachmentItem("2", "brief.pdf", "application/pdf", 48_000)),
        )

    private val ready = base.copy(thread = FakeBriefSource.BRIEF.toState())

    private fun detail(s: DetailUiState): ShellDetailPane =
        { _, _ -> DetailScreen(s, onToggleOriginal = {}, onRetry = {}, onAttachment = {}, onReply = {}) }

    private fun capture(
        name: String,
        s: DetailUiState,
        size: ScreenSize,
        dark: Boolean,
    ) = captureScreen(name, size, dark) { info ->
        AppShell(windowInfo = info, listPane = list, detailPane = detail(s), initialOpenKey = "4")
    }

    @Test
    fun ready() {
        listOf(ScreenSize.Cover, ScreenSize.InnerPortrait).forEach { size ->
            listOf(false, true).forEach { dark -> capture("detail_thread_brief", ready, size, dark) }
        }
    }

    @Test
    fun loadingAndFallback() {
        val loading = base.copy(thread = ThreadBriefState.Loading)
        val fallback = base.copy(thread = ThreadBriefState.Fallback)
        capture("detail_thread_brief_loading", loading, ScreenSize.Cover, dark = false)
        capture("detail_thread_brief_fallback", fallback, ScreenSize.Cover, dark = true)
    }
}
