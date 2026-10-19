package tech.elephantclock.privatemail.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.elephantclock.privatemail.core.data.db.FolderSummary
import tech.elephantclock.privatemail.core.data.db.TriageCounts

/** Which list: Priority across accounts, or one folder of one account. */
enum class InboxMode { PRIORITY, FOLDERS }

data class InboxUiState(
    val accounts: List<AccountChip> = emptyList(),
    /** Null = all accounts (Priority only; Folders always shows one account). */
    val selectedAccount: String? = null,
    val folders: List<FolderItem> = emptyList(),
    val selectedFolder: String? = null,
    val today: TodaySummary? = null,
    val searching: Boolean = false,
    val query: String = "",
    /** Null while no search has run for the current query. */
    val results: List<MessageRow>? = null,
    val refreshing: Boolean = false,
    val refreshProblem: RefreshOutcome? = null,
) {
    val priorityTotal: Int get() = accounts.sumOf { it.priorityCount }
}

private data class Selection(
    val account: String?,
    val folder: String?,
)

/**
 * The Priority and Folders lists (T44): Paging 3 over [InboxSource], an account filter, the
 * "today" card and search. Everything shown comes from the database; only [refresh] touches
 * the network (through the sync repository, read-only).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class InboxViewModel(
    private val source: InboxSource,
    val mode: InboxMode,
    private val times: TimeLabels = TimeLabels(),
) : ViewModel() {
    private val local = MutableStateFlow(InboxUiState())
    private var searchJob: Job? = null

    private val accountChips: Flow<List<AccountChip>> =
        combine(source.accounts(), source.priorityCounts()) { accounts, counts ->
            accounts.map { a ->
                // The user's own name for the account (T52), else the address's domain.
                val label =
                    a.displayName.takeIf { it.isNotBlank() && it != a.email }
                        ?: a.email.substringAfter('@').ifBlank { a.email }
                AccountChip(a.id, label, a.colorIndex, counts[a.id] ?: 0)
            }
        }

    /** Folders mode: the chosen account, else the first one. */
    private val effectiveAccount: Flow<String?> =
        combine(accountChips, local.map { it.selectedAccount }.distinctUntilChanged()) { chips, chosen ->
            when (mode) {
                InboxMode.PRIORITY -> chosen?.takeIf { id -> chips.any { it.id == id } }
                InboxMode.FOLDERS -> chosen?.takeIf { id -> chips.any { it.id == id } } ?: chips.firstOrNull()?.id
            }
        }.distinctUntilChanged()

    private val folderList: Flow<List<FolderSummary>> =
        effectiveAccount.flatMapLatest { id ->
            if (mode == InboxMode.FOLDERS && id != null) source.folders(id) else flowOf(emptyList())
        }

    private val selection: Flow<Selection> =
        combine(effectiveAccount, folderList, local.map { it.selectedFolder }.distinctUntilChanged()) { a, folders, f ->
            val folder = f?.takeIf { p -> folders.any { it.path == p } } ?: folders.firstOrNull()?.path
            Selection(a, if (mode == InboxMode.FOLDERS) folder else null)
        }.distinctUntilChanged()

    private val today: Flow<TriageCounts?> =
        effectiveAccount.flatMapLatest { id ->
            if (mode == InboxMode.PRIORITY) source.triagedSince(times.startOfToday(), id) else flowOf(null)
        }

    val state: StateFlow<InboxUiState> =
        combine(local, accountChips, folderList, selection, today) { s, chips, folders, sel, counts ->
            s.copy(
                accounts = chips,
                selectedAccount = sel.account,
                folders = folders.map { FolderItem(it.path, it.displayName, it.total, it.unread) },
                selectedFolder = sel.folder,
                today =
                    counts?.takeIf { it.filed + it.discarded > 0 }?.let {
                        TodaySummary(it.filed, it.discarded, labelOnly = it.stillInInbox > 0)
                    },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), InboxUiState())

    /** The list itself; follows the account and folder choice. */
    val messages: Flow<PagingData<MessageRow>> =
        selection
            .flatMapLatest { sel ->
                when {
                    mode == InboxMode.PRIORITY -> source.priority(sel.account)
                    sel.account != null && sel.folder != null -> source.folder(sel.account, sel.folder)
                    else -> emptyFlow()
                }
            }.map { page -> page.map { it.toRow(times) } }
            .cachedIn(viewModelScope)

    fun selectAccount(id: String?) = local.update { it.copy(selectedAccount = id, selectedFolder = null) }

    fun selectFolder(path: String) = local.update { it.copy(selectedFolder = path) }

    fun openSearch() = local.update { it.copy(searching = true) }

    fun closeSearch() {
        searchJob?.cancel()
        local.update { it.copy(searching = false, query = "", results = null) }
    }

    /** Searches as the user types. Results are limited to the selected account, if any. */
    fun setQuery(text: String) {
        local.update { it.copy(query = text, results = if (text.isBlank()) null else it.results) }
        searchJob?.cancel()
        if (text.isBlank()) return
        searchJob =
            viewModelScope.launch {
                val account = state.value.selectedAccount
                val found = source.search(text).filter { account == null || it.message.accountId == account }
                local.update { if (it.query == text) it.copy(results = found.map { r -> r.toRow(times) }) else it }
            }
    }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true, refreshProblem = null) }
        viewModelScope.launch {
            val outcome =
                try {
                    source.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    RefreshOutcome.PARTLY_FAILED
                }
            local.update {
                it.copy(refreshing = false, refreshProblem = outcome.takeIf { o -> o != RefreshOutcome.DONE })
            }
        }
    }

    fun dismissProblem() = local.update { it.copy(refreshProblem = null) }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
