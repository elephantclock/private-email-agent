package tech.elephantclock.privatemail.core.data.repo

import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.imap.isConnectionProblem
import tech.elephantclock.privatemail.core.model.AccountId

/** Messages a sync stored for the first time; T24 triages them. Ids are local message ids. */
data class NewMessages(
    val accountId: AccountId,
    val folderPath: String,
    val messageIds: List<Long>,
)

/** What one folder sync did. [error] is set when it (or its flag/expunge check) failed. */
data class FolderSyncResult(
    val path: String,
    val added: Int = 0,
    val flagsChanged: Int = 0,
    val removed: Int = 0,
    val reset: Boolean = false,
    val error: Throwable? = null,
)

sealed interface AccountSyncResult {
    val accountId: AccountId

    data class Synced(
        override val accountId: AccountId,
        val folders: List<FolderSyncResult>,
    ) : AccountSyncResult

    data class SignInFailed(
        override val accountId: AccountId,
        val error: AuthError,
    ) : AccountSyncResult

    /** Something unexpected (a database error, a bug); the other accounts were still synced. */
    data class Failed(
        override val accountId: AccountId,
        val error: Throwable,
    ) : AccountSyncResult

    /** Not tried: unknown account, or one with an `authProblem` waiting for the user. */
    data class Skipped(
        override val accountId: AccountId,
        val reason: String,
    ) : AccountSyncResult
}

data class SyncReport(
    val accounts: List<AccountSyncResult>,
) {
    /**
     * True when something failed that a later attempt may fix: the network, a dropped
     * connection, a server that's busy. Problems that need the user never ask for a retry.
     */
    val shouldRetry: Boolean
        get() =
            accounts.any { result ->
                when (result) {
                    is AccountSyncResult.SignInFailed -> result.error.isTransient()
                    is AccountSyncResult.Synced -> result.folders.any { it.error?.isConnectionProblem() == true }
                    is AccountSyncResult.Failed -> result.error.isConnectionProblem()
                    is AccountSyncResult.Skipped -> false
                }
            }

    private fun AuthError.isTransient() =
        this is AuthError.HostNotFound || this is AuthError.PortBlockedOrTimeout ||
            this is AuthError.TlsFailure || this is AuthError.Unknown
}
