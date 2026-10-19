package tech.elephantclock.privatemail.feature.shell

import androidx.compose.runtime.Composable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.ai.NoAiAvailability
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.feature.detail.AttachmentItem
import tech.elephantclock.privatemail.feature.detail.BodyState
import tech.elephantclock.privatemail.feature.detail.DetailHeader
import tech.elephantclock.privatemail.feature.detail.DetailScreen
import tech.elephantclock.privatemail.feature.detail.DetailUiState
import tech.elephantclock.privatemail.feature.detail.TriageBrief
import tech.elephantclock.privatemail.feature.reply.CompactReplyEditor
import tech.elephantclock.privatemail.feature.reply.FakeReplySource
import tech.elephantclock.privatemail.feature.reply.ReplyActions
import tech.elephantclock.privatemail.feature.reply.ReplyRoute
import tech.elephantclock.privatemail.feature.reply.ReplyUiState
import tech.elephantclock.privatemail.feature.reply.ReplyViewModel
import tech.elephantclock.privatemail.ui.components.ImportanceLevel
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureScreen

/** Flex mode (mockup 10): the email above the hinge and the reply editor below it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class FlexScreenshotTest {
    private val email =
        DetailUiState(
            loading = false,
            header =
                DetailHeader(
                    subject = "Q3 partner payouts",
                    fromName = "Partner office",
                    fromEmail = "person1@sender.example",
                    to = "Sam Tester",
                    cc = null,
                    date = "18 Oct 2026, 15:02",
                    account = null,
                ),
            triage = TriageBrief(ImportanceLevel.Urgent, VerdictAction.KEEP, FolderRole.MONEY, null, "VIP", false),
            brief =
                "Partner office asks you to confirm the payout figures for Q3 by Monday. " +
                    "Two partners' totals differ from last month's report.",
            body = BodyState.Loaded("Hello Sam,\n\nPlease confirm the payout figures by Monday.", false, false),
            attachments = listOf(AttachmentItem("2", "payouts_q3.xlsx", "application/vnd.ms-excel", 48_000)),
        )

    private val reply =
        ReplyUiState(
            loading = false,
            from = "sam@home.example",
            to = "person1@sender.example",
            subject = "Re: Q3 partner payouts",
            body = "Hi, thanks. I'll check both partners' totals and confirm by Monday noon.",
        )

    @Composable
    private fun Replying(info: WindowInfo) {
        AppShell(
            windowInfo = info,
            listPane = sampleList,
            detailPane = { _, _ ->
                DetailScreen(
                    email,
                    onToggleOriginal = {},
                    onRetry = {},
                    onAttachment = {},
                    onReply = null,
                    belowHinge = { CompactReplyEditor(reply, ReplyActions()) },
                )
            },
            initialOpenKey = "4",
        )
    }

    @Test
    fun emailAboveReplyBelow() {
        listOf(false, true).forEach { dark ->
            captureScreen("flex_reply", ScreenSize.Flex, dark) { info -> Replying(info) }
        }
    }

    /** A hinge that hides 30 dp: nothing is drawn under it, the band covers exactly that strip. */
    @Test
    fun occludingHinge() {
        captureScreen("flex_reply_occluding_hinge", ScreenSize.Flex, dark = false) { info ->
            Replying(info.copy(posture = FoldPosture.Tabletop(hingeTopDp = 360f, hingeBottomDp = 390f)))
        }
    }

    /** Send's confirmation in Flex: it must not sit on the hinge. */
    @Test
    fun sendConfirmation() {
        val vm = ReplyViewModel(4, FakeReplySource(), NoAiAvailability())
        vm.setBody("Hi, thanks. I'll confirm by Monday noon.")
        vm.requestSend()
        captureScreen("flex_reply_confirm", ScreenSize.Flex, dark = false) { info ->
            AppShell(
                windowInfo = info,
                listPane = sampleList,
                detailPane = { _, _ ->
                    DetailScreen(
                        email,
                        onToggleOriginal = {},
                        onRetry = {},
                        onAttachment = {},
                        onReply = null,
                        belowHinge = { ReplyRoute(vm, onClose = {}, compact = true) },
                    )
                },
                initialOpenKey = "4",
            )
        }
    }
}
