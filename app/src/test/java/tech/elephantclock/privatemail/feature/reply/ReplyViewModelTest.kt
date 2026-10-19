package tech.elephantclock.privatemail.feature.reply

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import tech.elephantclock.privatemail.core.ai.NoAiAvailability
import tech.elephantclock.privatemail.core.data.repo.SendOutcome
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.SendError
import tech.elephantclock.privatemail.core.mail.smtp.SendResult
import tech.elephantclock.privatemail.core.mail.smtp.SentCopy
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend

@OptIn(ExperimentalCoroutinesApi::class, UserInitiatedSend::class) // tests may opt in
class ReplyViewModelTest {
    private val source = FakeReplySource()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ReplyViewModel(4, source, NoAiAvailability())

    /** What the confirmed Send handler does. */
    private fun ReplyViewModel.confirm() {
        val message = state.value.pending ?: return
        send(message, SendApproval(message))
    }

    @Test
    fun `starts addressed to the sender with one Re prefix, and AI tools unavailable`() =
        runTest {
            val s = vm().state.value
            assertEquals("sam@home.example", s.from)
            assertEquals("person4@sender.example", s.to)
            assertEquals("Re: Chatbot for our hotels", s.subject)
            assertFalse(s.rewriteAvailable)
            assertFalse(s.translateAvailable)
            assertEquals("Re: Fwd: x", ReplyViewModel.replySubject("RE: Fwd: x").replaceFirst("RE:", "Re:"))
            assertEquals("Re: a b", ReplyViewModel.replySubject("a\r\nb"))
        }

    @Test
    fun `tapping Send only asks for confirmation`() =
        runTest {
            val vm = vm()
            vm.setBody("Tuesday works.")
            vm.requestSend()
            assertEquals(
                "Tuesday works.",
                vm.state.value.pending!!
                    .text,
            )
            assertTrue(source.sends.isEmpty())
            vm.cancelSend()
            assertNull(vm.state.value.pending)
            assertTrue(source.sends.isEmpty())
        }

    @Test
    fun `a confirmed send goes once, even when confirmed twice`() =
        runTest {
            val vm = vm()
            vm.setBody("Tuesday works.")
            vm.requestSend()
            val message = vm.state.value.pending!!
            vm.confirm()
            vm.send(message, SendApproval(message)) // a second tap with a fresh approval
            assertEquals(1, source.sends.size)
            assertTrue(vm.state.value.sent)
            assertEquals(ReplyNotice.Sent, vm.state.value.notice)
            vm.setBody("changed after sending")
            assertEquals("Tuesday works.", vm.state.value.body)
        }

    @Test
    fun `send without an open confirmation does nothing`() =
        runTest {
            val vm = vm()
            vm.requestSend()
            val message = vm.state.value.pending!!
            vm.cancelSend()
            vm.send(message, SendApproval(message))
            assertTrue(source.sends.isEmpty())
        }

    @Test
    fun `bad or missing recipients are caught before the dialog`() =
        runTest {
            val vm = vm()
            vm.setTo("bob@, carol@example.org")
            vm.requestSend()
            assertNull(vm.state.value.pending)
            assertEquals(ReplyNotice.BadAddress, vm.state.value.notice)
            vm.setTo("bob\n@example.org")
            vm.requestSend()
            assertEquals(ReplyNotice.BadAddress, vm.state.value.notice)
            vm.setTo(" ")
            vm.requestSend()
            assertEquals(ReplyNotice.NoRecipient, vm.state.value.notice)
            vm.setTo("bob@example.org; carol@example.org")
            vm.requestSend()
            assertEquals(
                listOf("bob@example.org", "carol@example.org"),
                vm.state.value.pending!!
                    .to
                    .map { it.email },
            )
        }

    @Test
    fun `a lost connection is worded as may not have been sent, and sending again needs a new confirmation`() =
        runTest {
            source.sendResult = {
                SendOutcome.Done(SendResult.NotSent(SendError.Connection(AuthError.PortBlockedOrTimeout("smtp", 465))))
            }
            val vm = vm()
            vm.requestSend()
            vm.confirm()
            assertEquals(ReplyNotice.MaybeNotSent, vm.state.value.notice)
            assertFalse(vm.state.value.sent)
            assertNull(vm.state.value.pending)
            vm.confirm() // no dialog open: nothing happens
            assertEquals(1, source.sends.size)
        }

    @Test
    fun `drafts and the send share one Message-ID, and each save replaces the previous one`() =
        runTest {
            val vm = vm()
            vm.setBody("First")
            vm.saveDraft()
            vm.setBody("Second")
            vm.saveDraft()
            assertEquals(ReplyNotice.DraftSaved, vm.state.value.notice)
            assertNull(source.drafts[0].second)
            assertEquals(1L, source.drafts[1].second!!.uid)
            vm.requestSend()
            vm.confirm()
            val ids = (source.drafts.map { it.first } + source.sends.map { it.first }).map { it.messageId }.toSet()
            assertEquals(1, ids.size)
            assertEquals(
                2L,
                source.sends
                    .single()
                    .second!!
                    .uid,
            ) // the draft to remove after sending
        }

    @Test
    fun `every send outcome has its own words`() {
        fun notice(r: SendResult) = ReplyNotice.of(SendOutcome.Done(r))
        assertEquals(ReplyNotice.SentByProvider, notice(SendResult.Sent("<a@b.c>", SentCopy.SavedByProvider)))
        assertEquals(ReplyNotice.SentNoCopy, notice(SendResult.Sent("<a@b.c>", SentCopy.Skipped)))
        assertTrue(notice(SendResult.Sent("<a@b.c>", SentCopy.Failed("full"))).let { it.sent && it.detail == "full" })
        assertEquals(ReplyNotice.NotApproved, notice(SendResult.NotSent(SendError.NotApproved("changed"))))
        assertEquals(ReplyNotice.NoRecipient, notice(SendResult.NotSent(SendError.NoRecipients)))
        assertEquals(ReplyNotice.BadAddress, notice(SendResult.NotSent(SendError.InvalidMessage("bob@"))))
        assertEquals("x@y.z", notice(SendResult.NotSent(SendError.RecipientsRejected(listOf("x@y.z"), "550"))).detail)
        assertFalse(notice(SendResult.NotSent(SendError.Rejected("552 too big"))).sent)
        assertEquals(ReplyNotice.NeedsSignIn, ReplyNotice.of(SendOutcome.NeedsSignIn))
        assertEquals(ReplyNotice.Unexpected, ReplyNotice.of(null))
    }

    @Test
    fun `a parent that is gone shows as missing`() =
        runTest {
            source.context = null
            assertTrue(vm().state.value.missing)
        }
}
