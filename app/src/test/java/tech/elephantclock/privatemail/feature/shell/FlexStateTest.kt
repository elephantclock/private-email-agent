package tech.elephantclock.privatemail.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.elephantclock.privatemail.core.ai.NoAiAvailability
import tech.elephantclock.privatemail.feature.detail.DetailViewModel
import tech.elephantclock.privatemail.feature.detail.FakeDetailSource
import tech.elephantclock.privatemail.feature.reply.FakeReplySource
import tech.elephantclock.privatemail.feature.reply.ReplyViewModel
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme

/**
 * Flex mode keeps what the user was doing: an open reply with typed text stays open, with the
 * same text, from flat to half-open to folded and back, and across activity recreation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w832dp-h750dp")
class FlexStateTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val flat = WindowInfo(832, 750)
    private val flex = WindowInfo(832, 750, FoldPosture.Tabletop(360f, 390f))
    private val cover = WindowInfo(412, 960)

    @Test
    fun replyAndDraftSurviveFlatHalfOpenFoldedAndRecreation() {
        var info by mutableStateOf(flat)
        val detailSource = FakeDetailSource(dir = tmp.root)
        val replySource = FakeReplySource()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PrivateMailTheme {
                AppShell(
                    windowInfo = info,
                    listPane = sampleList,
                    detailPane = { _, _ ->
                        FlexReadingDetail(
                            messageId = 4,
                            detailViewModel = { DetailViewModel(4, detailSource, NoAiAvailability()) },
                            replyViewModel = { ReplyViewModel(4, replySource, NoAiAvailability()) },
                        )
                    },
                    initialOpenKey = "4",
                )
            }
        }
        compose.onNodeWithTag("reply_button").performClick()
        compose.onNodeWithTag("field_body").performTextReplacement("Tuesday works.")
        compose.onNodeWithTag("reply").assertExists()

        // Half-open: the email above the hinge, the same draft in the compact editor below.
        info = flex
        compose.waitForIdle()
        compose.onNodeWithTag("detail").assertExists()
        compose.onNodeWithTag("reply_compact").assertExists()
        compose.onNodeWithTag("hinge_band").assertExists()
        compose.onNodeWithTag("field_body").assertTextContains("Tuesday works.")

        // Folded to the cover screen: the full editor, still the same draft.
        info = cover
        compose.waitForIdle()
        compose.onNodeWithTag("reply").assertExists()
        compose.onNodeWithTag("field_body").assertTextContains("Tuesday works.")

        // Fold, unfold and rotation recreate the activity.
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reply").assertExists()
        compose.onNodeWithTag("field_body").assertTextContains("Tuesday works.")

        info = flex
        compose.waitForIdle()
        compose.onNodeWithTag("reply_compact").assertExists()
        compose.onNodeWithTag("field_body").assertTextContains("Tuesday works.")

        // Closing the compact editor goes back to the email with its Reply action.
        compose.onNodeWithTag("compact_close").performClick()
        compose.onNodeWithTag("reply_button").assertExists()
    }
}
