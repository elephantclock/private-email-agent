package tech.elephantclock.privatemail.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunReview
import tech.elephantclock.privatemail.core.data.onboarding.ReviewGroup
import tech.elephantclock.privatemail.core.data.onboarding.ReviewItem
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Status

data class FirstRunUiState(
    val work: FirstRunWorkState = FirstRunWorkState(status = Status.RUNNING, mode = Mode.SORT),
    val review: FirstRunReview? = null,
    val backfill: BackfillStatus? = null,
    val prefs: AccountPrefs = AccountPrefs(),
    val drillDown: ReviewGroup? = null,
    val items: List<ReviewItem>? = null,
) {
    /** The worker is still syncing, reading Sent, sorting or applying. */
    val busy: Boolean get() = work.running

    val failed: Boolean get() = work.status == Status.FAILED

    /** Apply only with a finished review and the Sent history complete: the safety gate, shown. */
    val canApply: Boolean get() = !busy && review != null && backfill == BackfillStatus.COMPLETE

    val canLabelOnly: Boolean get() = !busy || work.mode != Mode.APPLY
}

interface FirstRunActions {
    fun onOpenGroup(group: ReviewGroup)

    fun onCloseGroup()

    fun onKeep(item: ReviewItem)

    fun onApply()

    fun onLabelOnly()

    fun onRetry()

    object NoActions : FirstRunActions {
        override fun onOpenGroup(group: ReviewGroup) = Unit

        override fun onCloseGroup() = Unit

        override fun onKeep(item: ReviewItem) = Unit

        override fun onApply() = Unit

        override fun onLabelOnly() = Unit

        override fun onRetry() = Unit
    }
}

/**
 * Screen 03 (T43): follows the first-run worker; once the label-only sort is done, shows the
 * keep/discard review from the stored verdicts. Apply hands the moves to the worker; Label only
 * finishes onboarding without moving anything. Finishing (either way) sets the account's step to
 * done, and the app's routing moves on to the inbox.
 */
class FirstRunViewModel(
    private val account: AccountId,
    private val data: FirstRunData,
    private val work: FirstRunWork,
) : ViewModel(),
    FirstRunActions {
    private val _state = MutableStateFlow(FirstRunUiState())
    val state: StateFlow<FirstRunUiState> = _state.asStateFlow()
    private var itemsJob: Job? = null

    init {
        viewModelScope.launch { data.prefs(account).collect { p -> _state.update { it.copy(prefs = p) } } }
        viewModelScope.launch {
            work.state(account).collect { w ->
                _state.update { it.copy(work = w) }
                if (!w.running) refresh()
            }
        }
    }

    private suspend fun refresh() {
        val review = data.review(account)
        val backfill = data.backfillStatus(account)
        _state.update { it.copy(review = review, backfill = backfill) }
    }

    override fun onOpenGroup(group: ReviewGroup) {
        _state.update { it.copy(drillDown = group, items = null) }
        itemsJob?.cancel()
        itemsJob = viewModelScope.launch { loadItems(group) }
    }

    private suspend fun loadItems(group: ReviewGroup) {
        val items = data.items(account, group)
        _state.update { if (it.drillDown == group) it.copy(items = items) else it }
    }

    override fun onCloseGroup() {
        itemsJob?.cancel()
        _state.update { it.copy(drillDown = null, items = null) }
    }

    override fun onKeep(item: ReviewItem) {
        _state.update { s -> s.copy(items = s.items?.filterNot { it.id == item.id }) }
        viewModelScope.launch {
            data.keep(item.id)
            refresh()
        }
    }

    override fun onApply() {
        if (!_state.value.canApply) return
        _state.update { it.copy(work = FirstRunWorkState(Mode.APPLY, Status.RUNNING), drillDown = null, items = null) }
        work.apply(account)
    }

    override fun onLabelOnly() {
        if (!_state.value.canLabelOnly) return
        viewModelScope.launch { data.labelOnly(account) }
    }

    override fun onRetry() {
        _state.update { it.copy(work = FirstRunWorkState(Mode.SORT, Status.RUNNING)) }
        work.sort(account)
    }
}
