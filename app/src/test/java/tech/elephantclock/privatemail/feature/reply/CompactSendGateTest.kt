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

/** The Flex-mode editor (T4A) is a new way into sending: the same confirmation gate applies. */
@RunWith(RobolectricTestRunner::class)
class CompactSendGateTest {
    @get:Rule
    val compose = createComposeRule()
    private val source = FakeReplySource()

    private fun show() {
        val vm = ReplyViewModel(4, source, NoAiAvailability())
        compose.setContent { PrivateMailTheme { ReplyRoute(vm, onClose = {}, compact = true) } }
        compose.onNodeWithTag("field_body").performTextReplacement("Tuesday works.")
    }

    @Test
    fun `Send asks first and sends nothing, and Keep editing sends nothing`() {
        show()
        compose.onNodeWithTag("send").performClick()
        compose.onNodeWithTag("inline_confirm").assertExists()
        assertEquals(0, source.sends.size)
        compose.onNodeWithTag("cancel_send").performClick()
        compose.onNodeWithTag("inline_confirm").assertDoesNotExist()
        compose.onNodeWithTag("field_body").assertExists()
        assertEquals(0, source.sends.size)
    }

    @Test
    fun `confirming sends exactly once`() {
        show()
        compose.onNodeWithTag("send").performClick()
        val confirm = compose.onNodeWithTag("confirm_send")
        confirm.performClick()
        runCatching { confirm.performClick() }
        compose.waitForIdle()
        assertEquals(1, source.sends.size)
        assertEquals(
            "Tuesday works.",
            source.sends
                .single()
                .first.text,
        )
    }
}
