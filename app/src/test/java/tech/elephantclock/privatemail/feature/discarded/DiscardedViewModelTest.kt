package tech.elephantclock.privatemail.feature.discarded

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.elephantclock.privatemail.core.data.triage.RestoreReport
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.VerdictAction
import java.time.ZoneId

@OptIn(UserInitiatedSend::class)
class DiscardedViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val items = MutableStateFlow(SAMPLE)
    private val commands = FakeCommands()
    private val unsubscriber = FakeUnsubscriber()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(io: kotlinx.coroutines.CoroutineDispatcher = main) =
        DiscardedViewModel({ items }, commands, unsubscriber, io, clock = { NOW }, zone = { ZoneId.of("UTC") })

    private fun DiscardedViewModel.group(sender: String) = state.value.groups.single { it.sender?.email == sender }

    @Test
    fun `groups by sender and reason, counts today's discards and spam separately`() {
        val vm = viewModel()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf("news@trail.example", "promo@shop.example"), s.groups.map { it.sender?.email })
        assertEquals(listOf(2, 1), s.groups.map { it.count })
        assertEquals(2, s.discardedToday) // the third discard was yesterday
        assertEquals(1, s.spamCount)

        vm.onGrouping(Grouping.REASON)
        assertEquals(
            listOf("Marketing", "Newsletter"),
            vm.state.value.groups
                .map { it.title }
                .sorted(),
        )
        assertTrue(
            vm.state.value.groups
                .none { it.canNeverDiscard || it.canUnsubscribe },
        )

        vm.onGrouping(Grouping.SPAM)
        val spam =
            vm.state.value.groups
                .single()
        assertTrue(spam.junk)
        assertFalse("never whitelist a phisher", spam.canNeverDiscard)
        assertFalse("never confirm the address to a spammer", spam.canUnsubscribe)
    }

    @Test
    fun `restore and never discard go to the commands with the whole group`() {
        val vm = viewModel()
        vm.onRestore(vm.group("news@trail.example"))
        assertEquals(listOf(listOf(1L, 2L)), commands.restored)
        assertEquals(DiscardNotice.Restored(2), vm.state.value.notice)

        vm.onNeverDiscard(vm.group("promo@shop.example"))
        assertEquals(listOf(Triple(ACCOUNT, Address.of("promo@shop.example"), listOf(3L))), commands.never)
        assertEquals(DiscardNotice.NeverDiscard("promo@shop.example", 1), vm.state.value.notice)
    }

    @Test
    fun `a failed restore says so`() {
        commands.report = RestoreReport(restored = 0, failed = 2)
        val vm = viewModel()
        vm.onRestore(vm.group("news@trail.example"))
        assertEquals(DiscardNotice.RestoreFailed(2), vm.state.value.notice)
    }

    @Test
    fun `one-click is posted only after the confirmation, and only once`() {
        val vm = viewModel()
        vm.onUnsubscribe(vm.group("news@trail.example"))
        val plan = vm.state.value.confirm as UnsubscribePlan.OneClick
        assertEquals("lists.example", plan.host)
        assertEquals(0, unsubscriber.calls)

        vm.onDismissConfirm()
        assertNull(vm.state.value.confirm)
        vm.onConfirmOneClick() // nothing to confirm any more
        assertEquals(0, unsubscriber.calls)

        vm.onUnsubscribe(vm.group("news@trail.example"))
        vm.onConfirmOneClick()
        vm.onConfirmOneClick() // double tap
        assertEquals(listOf("https://lists.example/u"), unsubscriber.posted)
        assertEquals(DiscardNotice.Unsubscribed("news@trail.example"), vm.state.value.notice)
        assertTrue(
            "unsubscribed",
            "news@trail.example" in
                vm.state.value.unsubscribed
                    .joinToString(),
        )
        vm.onUnsubscribe(vm.group("news@trail.example"))
        assertNull("no second request offered", vm.state.value.confirm)
    }

    @Test
    fun `mailto is sent only from the confirm handler, with an approval for the shown message`() {
        val vm = viewModel()
        vm.onUnsubscribe(vm.group("promo@shop.example"))
        val plan = vm.state.value.confirm as UnsubscribePlan.Mail
        assertEquals(listOf(Address.of("leave@shop.example")), plan.message.to)
        assertEquals(Address.of("me@ourco.example"), plan.message.from)
        assertEquals("unsubscribe", plan.message.subject)
        assertEquals(0, unsubscriber.calls)

        // A confirm for a different dialog type does nothing.
        vm.onConfirmOneClick()
        assertEquals(0, unsubscriber.calls)

        val approval = SendApproval(plan.message)
        vm.onConfirmMail(approval)
        vm.onConfirmMail(SendApproval(plan.message))
        assertEquals(listOf(plan.message), unsubscriber.mailed)
        assertTrue(unsubscriber.approvals.single() === approval)
    }

    @Test
    fun `nothing is ever sent without a dialog`() {
        val vm = viewModel()
        vm.onConfirmOneClick()
        vm.onConfirmMail(SendApproval(SAMPLE_MESSAGE))
        vm.onGrouping(Grouping.SPAM)
        vm.onUnsubscribe(
            vm.state.value.groups
                .single(),
        )
        assertNull(vm.state.value.confirm)
        vm.onConfirmOneClick()
        assertEquals(0, unsubscriber.calls)
    }

    @Test
    fun `an unsure send is reported as maybe sent, and the group is busy meanwhile`() {
        val io = StandardTestDispatcher(main.scheduler)
        unsubscriber.outcome = UnsubscribeOutcome.UNKNOWN
        val vm = viewModel(io)
        vm.onUnsubscribe(vm.group("news@trail.example"))
        vm.onConfirmOneClick()
        assertEquals(setOf(vm.group("news@trail.example").key), vm.state.value.busy)
        main.scheduler.advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.state.value.busy)
        assertEquals(DiscardNotice.UnsubscribeUnknown("news@trail.example"), vm.state.value.notice)
        assertTrue(
            vm.state.value.unsubscribed
                .isEmpty(),
        )
    }

    private class FakeCommands : DiscardedCommands {
        val restored = mutableListOf<List<Long>>()
        val never = mutableListOf<Triple<AccountId, Address, List<Long>>>()
        var report: RestoreReport? = null

        override suspend fun restore(ids: List<Long>): RestoreReport {
            restored += ids
            return report ?: RestoreReport(restored = ids.size)
        }

        override suspend fun neverDiscard(
            accountId: AccountId,
            sender: Address,
            ids: List<Long>,
        ): RestoreReport {
            never += Triple(accountId, sender, ids)
            return report ?: RestoreReport(restored = ids.size)
        }
    }

    private class FakeUnsubscriber : Unsubscriber {
        val posted = mutableListOf<String>()
        val mailed = mutableListOf<OutgoingMessage>()
        val approvals = mutableListOf<SendApproval>()
        var outcome = UnsubscribeOutcome.DONE
        val calls get() = posted.size + mailed.size

        override suspend fun oneClick(url: String): UnsubscribeOutcome {
            posted += url
            return outcome
        }

        @UserInitiatedSend
        override suspend fun mail(
            accountId: AccountId,
            message: OutgoingMessage,
            approval: SendApproval,
        ): UnsubscribeOutcome {
            mailed += message
            approvals += approval
            return outcome
        }
    }

    companion object {
        private const val NOW = 1_800_000_000_000L
        private const val DAY = 86_400_000L
        val ACCOUNT = AccountId("a1")
        private val ME = Address.of("me@ourco.example")
        private val SAMPLE_MESSAGE = OutgoingMessage(ME, "x", "y", to = listOf(Address.of("z@x.example")))

        private fun item(
            id: Long,
            from: String,
            reason: String,
            decidedAt: Long = NOW - 1_000 * id,
            action: VerdictAction = VerdictAction.DISCARD,
            unsubscribe: UnsubscribeMethod? = null,
        ) = DiscardedItem(
            id,
            ACCOUNT,
            ME,
            Address.of(from),
            "Subject $id",
            decidedAt,
            action,
            reason,
            decidedAt,
            unsubscribe,
        )

        val SAMPLE =
            listOf(
                item(
                    1,
                    "news@trail.example",
                    "Newsletter",
                    unsubscribe = UnsubscribeMethod.OneClick("https://lists.example/u"),
                ),
                item(2, "news@trail.example", "Newsletter"),
                item(
                    3,
                    "promo@shop.example",
                    "Marketing",
                    decidedAt = NOW - DAY,
                    unsubscribe =
                        UnsubscribeMethod.Mailto(
                            Address.of("leave@shop.example"),
                            "unsubscribe",
                            "unsubscribe",
                        ),
                ),
                item(
                    4,
                    "alert@paypa1.example",
                    "Phishing",
                    action = VerdictAction.JUNK,
                    unsubscribe = UnsubscribeMethod.OneClick("https://paypa1.example/u"),
                ),
            )
    }
}
