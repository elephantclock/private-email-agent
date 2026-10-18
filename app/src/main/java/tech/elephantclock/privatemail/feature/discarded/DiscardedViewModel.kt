package tech.elephantclock.privatemail.feature.discarded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.VerdictAction
import java.time.Instant
import java.time.ZoneId

/** What the Discarded screen can ask for. */
interface DiscardedScreenActions {
    fun onGrouping(grouping: Grouping)

    fun onRestore(group: DiscardGroup)

    fun onNeverDiscard(group: DiscardGroup)

    /** Opens the confirmation dialog; sends nothing. */
    fun onUnsubscribe(group: DiscardGroup)

    fun onDismissConfirm()

    /** The dialog's confirm button for a one-click plan. */
    fun onConfirmOneClick()

    /** The dialog's confirm button for a mailto plan, with the approval its tap handler created. */
    @UserInitiatedSend
    fun onConfirmMail(approval: SendApproval)

    fun onNoticeShown()

    object NoActions : DiscardedScreenActions {
        override fun onGrouping(grouping: Grouping) = Unit

        override fun onRestore(group: DiscardGroup) = Unit

        override fun onNeverDiscard(group: DiscardGroup) = Unit

        override fun onUnsubscribe(group: DiscardGroup) = Unit

        override fun onDismissConfirm() = Unit

        override fun onConfirmOneClick() = Unit

        @UserInitiatedSend
        override fun onConfirmMail(approval: SendApproval) = Unit

        override fun onNoticeShown() = Unit
    }
}

/**
 * The Discarded digest (T47). Restore and Never discard act at once (both only bring mail
 * back). Unsubscribe never acts at once: [onUnsubscribe] only prepares a [UnsubscribePlan] for
 * the dialog, and the request goes out from [onConfirmOneClick] / [onConfirmMail], once per
 * dialog (the plan is cleared before anything is sent, so a double tap does nothing).
 */
class DiscardedViewModel(
    private val source: DiscardedSource,
    private val commands: DiscardedCommands,
    private val unsubscriber: Unsubscriber,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel(),
    DiscardedScreenActions {
    private val _state = MutableStateFlow(DiscardedUiState())
    val state: StateFlow<DiscardedUiState> = _state.asStateFlow()
    private var items: List<DiscardedItem> = emptyList()

    init {
        viewModelScope.launch {
            source.items().collect {
                items = it
                regroup()
            }
        }
    }

    private fun regroup() {
        val startOfDay =
            Instant
                .ofEpochMilli(clock())
                .atZone(zone())
                .toLocalDate()
                .atStartOfDay(zone())
                .toInstant()
                .toEpochMilli()
        _state.update { s ->
            s.copy(
                loading = false,
                groups = DiscardGrouping.group(items, s.grouping),
                discardedToday = items.count { it.action == VerdictAction.DISCARD && it.decidedAtMillis >= startOfDay },
                spamCount = items.count { it.action == VerdictAction.JUNK },
            )
        }
    }

    override fun onGrouping(grouping: Grouping) {
        _state.update { it.copy(grouping = grouping) }
        regroup()
    }

    override fun onRestore(group: DiscardGroup) =
        act(group) {
            val report = commands.restore(group.ids)
            if (report.failed >
                0
            ) {
                DiscardNotice.RestoreFailed(report.failed)
            } else {
                DiscardNotice.Restored(report.restored)
            }
        }

    override fun onNeverDiscard(group: DiscardGroup) {
        val sender = group.sender ?: return
        if (!group.canNeverDiscard) return
        act(group) {
            val report = commands.neverDiscard(group.accountId, sender, group.ids)
            if (report.failed > 0) {
                DiscardNotice.RestoreFailed(report.failed)
            } else {
                DiscardNotice.NeverDiscard(sender.email, report.restored)
            }
        }
    }

    override fun onUnsubscribe(group: DiscardGroup) {
        if (!group.canUnsubscribe || group.key in _state.value.unsubscribed) return
        val label = group.sender?.email ?: group.title
        val plan =
            when (val method = group.unsubscribe) {
                is UnsubscribeMethod.OneClick -> {
                    UnsubscribePlan.OneClick(group.key, label, method.url, method.host)
                }

                is UnsubscribeMethod.Mailto -> {
                    val message =
                        runCatching {
                            OutgoingMessage(
                                from = group.accountAddress,
                                subject = method.subject,
                                text = method.body,
                                to = listOf(method.to),
                            )
                        }.getOrNull() ?: return
                    UnsubscribePlan.Mail(group.key, label, group.accountId, message)
                }

                null -> {
                    return
                }
            }
        _state.update { it.copy(confirm = plan) }
    }

    override fun onDismissConfirm() = _state.update { it.copy(confirm = null) }

    override fun onConfirmOneClick() {
        val plan = takePlan<UnsubscribePlan.OneClick>() ?: return
        unsubscribe(plan) { unsubscriber.oneClick(plan.url) }
    }

    @UserInitiatedSend
    override fun onConfirmMail(approval: SendApproval) {
        val plan = takePlan<UnsubscribePlan.Mail>() ?: return
        unsubscribe(plan) { unsubscriber.mail(plan.accountId, plan.message, approval) }
    }

    override fun onNoticeShown() = _state.update { it.copy(notice = null) }

    /** The open plan, removed from the state so it can be confirmed only once. */
    private inline fun <reified T : UnsubscribePlan> takePlan(): T? {
        val plan = _state.value.confirm as? T ?: return null
        _state.update { it.copy(confirm = null) }
        return plan
    }

    private fun unsubscribe(
        plan: UnsubscribePlan,
        send: suspend () -> UnsubscribeOutcome,
    ) {
        _state.update { it.copy(busy = it.busy + plan.groupKey) }
        viewModelScope.launch {
            val outcome = runCatching { withContext(io) { send() } }.getOrDefault(UnsubscribeOutcome.FAILED)
            val who = plan.senderLabel
            _state.update {
                it.copy(
                    busy = it.busy - plan.groupKey,
                    unsubscribed =
                        if (outcome ==
                            UnsubscribeOutcome.DONE
                        ) {
                            it.unsubscribed + plan.groupKey
                        } else {
                            it.unsubscribed
                        },
                    notice =
                        when (outcome) {
                            UnsubscribeOutcome.DONE -> DiscardNotice.Unsubscribed(who)
                            UnsubscribeOutcome.REFUSED -> DiscardNotice.UnsubscribeRefused(who)
                            UnsubscribeOutcome.FAILED -> DiscardNotice.UnsubscribeFailed(who)
                            UnsubscribeOutcome.UNKNOWN -> DiscardNotice.UnsubscribeUnknown(who)
                        },
                )
            }
        }
    }

    private fun act(
        group: DiscardGroup,
        block: suspend () -> DiscardNotice,
    ) {
        if (group.key in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + group.key) }
        viewModelScope.launch {
            val notice =
                runCatching { withContext(io) { block() } }.getOrElse { DiscardNotice.RestoreFailed(group.count) }
            _state.update { it.copy(busy = it.busy - group.key, notice = notice) }
        }
    }
}
