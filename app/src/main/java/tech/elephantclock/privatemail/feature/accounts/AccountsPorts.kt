package tech.elephantclock.privatemail.feature.accounts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tech.elephantclock.privatemail.core.data.accounts.AccountRemover
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.model.AccountId

/** What the Accounts screen reads and changes. Nothing here talks to a mail server. */
interface AccountsSource {
    fun accounts(): Flow<List<AccountItem>>

    /** A blank [name] goes back to the address. */
    suspend fun rename(
        id: String,
        name: String,
    )

    suspend fun setColor(
        id: String,
        colorIndex: Int,
    )

    /** Removes the account and everything stored for it on this device; the server is not touched. */
    suspend fun remove(id: String): Boolean
}

class RoomAccountsSource(
    private val db: AppDatabase,
    private val remover: AccountRemover,
) : AccountsSource {
    override fun accounts(): Flow<List<AccountItem>> =
        combine(db.accounts().all(), db.accounts().lastSyncs()) { accounts, syncs ->
            val last = syncs.associate { it.accountId to it.lastSyncMillis }
            accounts.map {
                AccountItem(it.id, it.email, it.displayName, it.colorIndex, it.authProblem != null, last[it.id])
            }
        }

    override suspend fun rename(
        id: String,
        name: String,
    ) {
        val account = db.accounts().get(id) ?: return
        db.accounts().rename(id, name.trim().take(AccountsUiState.MAX_NAME).ifBlank { account.email })
    }

    override suspend fun setColor(
        id: String,
        colorIndex: Int,
    ) = db.accounts().setColor(id, colorIndex)

    override suspend fun remove(id: String): Boolean = remover.remove(AccountId(id))
}
