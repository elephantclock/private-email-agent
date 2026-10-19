package tech.elephantclock.privatemail.feature.reply

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tech.elephantclock.privatemail.core.ai.NoAiAvailability
import tech.elephantclock.privatemail.ui.theme.PrivateMailTheme
import java.io.File

/**
 * Sending is impossible without the confirmation, through the real screen and ViewModel (only
 * the server side is faked).
 */
@RunWith(RobolectricTestRunner::class)
class SendGateTest {
    @get:Rule
    val compose = createComposeRule()
    private val source = FakeReplySource()

    private fun show() {
        val vm = ReplyViewModel(4, source, NoAiAvailability())
        compose.setContent { PrivateMailTheme { ReplyRoute(vm, onClose = {}) } }
        compose.onNodeWithTag("field_body").performTextReplacement("Tuesday works.")
    }

    @Test
    fun `Send opens a dialog and sends nothing, and Keep editing sends nothing`() {
        show()
        compose.onNodeWithTag("send").performClick()
        compose.onNodeWithTag("confirm_send").assertExists()
        assertEquals(0, source.sends.size)
        compose.onNodeWithTag("cancel_send").performClick()
        compose.onNodeWithTag("confirm_send").assertDoesNotExist()
        assertEquals(0, source.sends.size)
    }

    @Test
    fun `confirming sends exactly once, even with a double tap`() {
        show()
        compose.onNodeWithTag("send").performClick()
        val confirm = compose.onNodeWithTag("confirm_send")
        confirm.performClick()
        runCatching { confirm.performClick() } // the dialog is gone after the first tap
        compose.waitForIdle()
        assertEquals(1, source.sends.size)
        assertEquals(
            "Tuesday works.",
            source.sends
                .single()
                .first.text,
        )
        compose.onNodeWithTag("notice").assertExists()
    }

    /**
     * Every place that may create a [SendApproval] is a confirmed-tap handler, listed here on
     * purpose: Reply's confirm button (T46) and the unsubscribe-by-email dialog's confirm (T47).
     * A new entry needs a review of its confirmation flow.
     */
    @Test
    fun `the app opts in to UserInitiatedSend only in confirmed-tap handlers`() {
        val allowed = listOf("DiscardedCards.kt", "ReplyScreen.kt")
        val main = File("src/main/java")
        val sources = main.walkTopDown().filter { it.extension == "kt" }.toList()
        val optIns = sources.flatMap { f -> f.readLines().filter { "OptIn(UserInitiatedSend" in it }.map { f.name } }
        assertEquals(allowed, optIns.sorted())
        val approvals =
            sources.flatMap { f ->
                f.readLines().filter { "SendApproval(" in it && "import" !in it }.map { f.name }
            }
        assertEquals(allowed, approvals.sorted())
    }
}
