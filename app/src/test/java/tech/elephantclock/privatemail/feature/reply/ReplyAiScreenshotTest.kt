package tech.elephantclock.privatemail.feature.reply

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.ai.LanguageTag
import tech.elephantclock.privatemail.feature.detail.BodyState
import tech.elephantclock.privatemail.feature.detail.DetailHeader
import tech.elephantclock.privatemail.feature.detail.DetailScreen
import tech.elephantclock.privatemail.feature.detail.DetailUiState
import tech.elephantclock.privatemail.feature.shell.AppShell
import tech.elephantclock.privatemail.feature.shell.sampleList
import tech.elephantclock.privatemail.ui.screenshot.ScreenSize
import tech.elephantclock.privatemail.ui.screenshot.captureScreen

/** The reply editor with on-device AI available (T33), from fixed states. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xhdpi")
class ReplyAiScreenshotTest {
    private val available =
        ReplyAiUi(
            draftAvailable = true,
            rewriteAvailable = true,
            proofreadAvailable = true,
            translateAvailable = true,
        )

    /** A Russian draft, made more formal on device (Undo offered), with its English preview. */
    private val russian =
        ReplyUiState(
            loading = false,
            from = "sam@home.example",
            to = "ivan@sender.example",
            subject = "Re: Встреча во вторник",
            body = "Здравствуйте, Иван!\n\nБлагодарю за письмо. Вторник мне подходит, в 15:00.\n\nС уважением,\nСэм",
            ai =
                available.copy(
                    undo = "Привет, Иван! Вторник ок, в 15:00. Сэм",
                    lastChange = AiAction.FORMAL,
                    translation =
                        TranslationUi.Ready(
                            "Hello Ivan,\n\nThank you for your email. Tuesday suits me, at 15:00.\n\nKind regards,\nSam",
                            LanguageTag.RUSSIAN,
                        ),
                ),
        )

    private fun full(
        name: String,
        state: ReplyUiState,
        darkToo: Boolean = true,
    ) = listOf(false, true).filter { darkToo || !it }.forEach { dark ->
        captureScreen(name, ScreenSize.Cover, dark) { info ->
            AppShell(
                windowInfo = info,
                listPane = sampleList,
                detailPane = { _, _ -> ReplyScreen(state, ReplyActions()) },
                initialOpenKey = "4",
            )
        }
    }

    @Test
    fun undoAndTranslation() = full("reply_ai_ready", russian)

    @Test
    fun failedWithRetry() =
        full(
            "reply_ai_failed",
            russian.copy(ai = available.copy(failed = AiAction.PROOFREAD, translation = TranslationUi.Idle)),
            darkToo = false,
        )

    @Test
    fun flex() {
        val email =
            DetailUiState(
                loading = false,
                header =
                    DetailHeader(
                        subject = "Встреча во вторник",
                        fromName = "Иван Петров",
                        fromEmail = "ivan@sender.example",
                        to = "Sam Tester",
                        cc = null,
                        date = "20 Oct 2026, 10:12",
                        account = null,
                    ),
                body = BodyState.Loaded("Здравствуйте, Сэм!\n\nВам удобно встретиться во вторник?", false, false),
            )
        listOf(false, true).forEach { dark ->
            captureScreen("reply_ai_flex", ScreenSize.Flex, dark) { info ->
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
                            belowHinge = { CompactReplyEditor(russian, ReplyActions()) },
                        )
                    },
                    initialOpenKey = "4",
                )
            }
        }
    }
}
