package tech.elephantclock.privatemail.feature.reply

import androidx.compose.runtime.remember
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.feature.inbox.AccountChip
import tech.elephantclock.privatemail.feature.inbox.InboxScreen
import tech.elephantclock.privatemail.feature.inbox.InboxUiState
import tech.elephantclock.privatemail.feature.inbox.ListActions
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures
import tech.elephantclock.privatemail.feature.inbox.staticPage
import tech.elephantclock.privatemail.feature.inbox.toRow
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.feature.shell.ShellListPane
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureAllSizes
import tech.elephantclock.privatemail.ui.screenshot.captureScreen

/** The reply editor in the detail pane, AI tools disabled (rules-only phone). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class ReplyScreenshotTest {
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

    private val editing =
        ReplyUiState(
            loading = false,
            from = "sam@home.example",
            to = "person4@sender.example",
            subject = "Re: Chatbot for our hotels",
            body = "Thanks for reaching out. We'd be glad to help.\n\nCould we talk on Tuesday or Wednesday?\n\nSam",
        )

    private fun shot(
        name: String,
        state: ReplyUiState,
        sizes: List<ScreenSize>,
        darkToo: Boolean = true,
    ) {
        sizes.forEach { size ->
            listOf(false, true).filter { darkToo || !it }.forEach { dark ->
                captureScreen(name, size, dark) { info ->
                    AppShell(
                        windowInfo = info,
                        listPane = list,
                        detailPane = { _, _ -> ReplyScreen(state, ReplyActions()) },
                        initialOpenKey = "4",
                    )
                }
            }
        }
    }

    @Test
    fun editor() {
        captureAllSizes("reply_editor") { info ->
            AppShell(
                windowInfo = info,
                listPane = list,
                detailPane = { _, _ -> ReplyScreen(editing, ReplyActions()) },
                initialOpenKey = "4",
            )
        }
    }

    @Test
    fun confirmation() {
        val message =
            OutgoingMessage(
                from = Address.of("sam@home.example"),
                subject = editing.subject,
                text = editing.body,
                to = listOf(Address.of("person4@sender.example")),
                messageId = "<fixed@home.example>",
            )
        shot("reply_confirm", editing.copy(pending = message), listOf(ScreenSize.Cover))
    }

    @Test
    fun maybeNotSent() {
        shot(
            "reply_maybe_not_sent",
            editing.copy(notice = ReplyNotice.MaybeNotSent),
            listOf(ScreenSize.Cover),
            darkToo = false,
        )
    }
}
