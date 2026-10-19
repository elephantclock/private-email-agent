package tech.elephantclock.privatemail.feature.accounts

/** One account on the Accounts screen; built from stored data only. */
data class AccountItem(
    val id: String,
    val email: String,
    /** What the user calls it; the address until renamed. */
    val name: String,
    val colorIndex: Int,
    /** Set when signing in needs the user (`AccountEntity.authProblem`): sync and alerts are paused. */
    val needsPassword: Boolean,
    /** Newest folder sync; null before the first. */
    val lastSyncMillis: Long?,
)

/** An open dialog. */
sealed interface AccountsDialog {
    data class Rename(
        val id: String,
        val email: String,
        val text: String,
    ) : AccountsDialog

    data class ConfirmRemove(
        val id: String,
        val email: String,
    ) : AccountsDialog
}

enum class AccountsNotice { REMOVED, REMOVE_FAILED }

data class AccountsUiState(
    val loading: Boolean = true,
    val accounts: List<AccountItem> = emptyList(),
    val dialog: AccountsDialog? = null,
    /** An account being removed: its card shows progress and takes no taps. */
    val removing: String? = null,
    val notice: AccountsNotice? = null,
) {
    companion object {
        const val MAX_NAME = 40
    }
}
