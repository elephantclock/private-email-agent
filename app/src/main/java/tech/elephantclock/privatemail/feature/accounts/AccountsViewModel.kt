package tech.elephantclock.privatemail.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Accounts screen's controls call; [AccountsViewModel] in the app, no-ops in screenshots. */
interface AccountsScreenActions {
    fun onRename(id: String)

    fun onRenameText(text: String)

    fun onRenameConfirm()

    fun onColor(
        id: String,
        colorIndex: Int,
    )

    fun onRemove(id: String)

    fun onRemoveConfirm()

    fun onDismissDialog()

    fun onNoticeShown()

    object NoActions : AccountsScreenActions {
        override fun onRename(id: String) = Unit

        override fun onRenameText(text: String) = Unit

        override fun onRenameConfirm() = Unit

        override fun onColor(
            id: String,
            colorIndex: Int,
        ) = Unit

        override fun onRemove(id: String) = Unit

        override fun onRemoveConfirm() = Unit

        override fun onDismissDialog() = Unit

        override fun onNoticeShown() = Unit
    }
}

/**
 * The Accounts screen (T52): the stored accounts with their status, rename, colour and remove.
 * Removal happens only from the confirmation dialog's button, and only on this device.
 */
class AccountsViewModel(
    private val source: AccountsSource,
) : ViewModel(),
    AccountsScreenActions {
    private val local = MutableStateFlow(AccountsUiState())

    val state: StateFlow<AccountsUiState> =
        combine(local, source.accounts()) { s, accounts ->
            s.copy(loading = false, accounts = accounts)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    private fun account(id: String) = state.value.accounts.firstOrNull { it.id == id }

    override fun onRename(id: String) {
        val a = account(id) ?: return
        local.update { it.copy(dialog = AccountsDialog.Rename(id, a.email, if (a.name == a.email) "" else a.name)) }
    }

    override fun onRenameText(text: String) {
        local.update { s ->
            val d = s.dialog as? AccountsDialog.Rename ?: return@update s
            s.copy(dialog = d.copy(text = text.take(AccountsUiState.MAX_NAME)))
        }
    }

    override fun onRenameConfirm() {
        val d = local.value.dialog as? AccountsDialog.Rename ?: return
        local.update { it.copy(dialog = null) }
        viewModelScope.launch { source.rename(d.id, d.text) }
    }

    override fun onColor(
        id: String,
        colorIndex: Int,
    ) {
        viewModelScope.launch { source.setColor(id, colorIndex) }
    }

    override fun onRemove(id: String) {
        val a = account(id) ?: return
        if (local.value.removing != null) return
        local.update { it.copy(dialog = AccountsDialog.ConfirmRemove(id, a.email)) }
    }

    override fun onRemoveConfirm() {
        val d = local.value.dialog as? AccountsDialog.ConfirmRemove ?: return
        local.update { it.copy(dialog = null, removing = d.id) }
        viewModelScope.launch {
            // Leaving the screen must not stop a removal halfway.
            val removed =
                withContext(NonCancellable) {
                    try {
                        // False only when it was already gone, which is what the user asked for.
                        source.remove(d.id)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
            local.update {
                it.copy(removing = null, notice = if (removed) AccountsNotice.REMOVED else AccountsNotice.REMOVE_FAILED)
            }
        }
    }

    override fun onDismissDialog() = local.update { it.copy(dialog = null) }

    override fun onNoticeShown() = local.update { it.copy(notice = null) }
}
