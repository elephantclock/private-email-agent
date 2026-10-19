package tech.elephantclock.privatemail.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** When an account's folders were last synced (the newest folder sync), null before the first one. */
data class AccountSync(
    val accountId: String,
    val lastSyncMillis: Long?,
)

@Dao
abstract class AccountDao {
    /** Every column as given; [upsert] is the one to call. */
    @Upsert
    protected abstract suspend fun upsertRow(account: AccountEntity)

    /**
     * Adds or updates an account (onboarding, re-entering a password). What the user chose on
     * the Accounts screen survives an update: the colour, and a display name the user changed
     * (one that differs from the address). A new account gets the first colour no other has.
     */
    @Transaction
    open suspend fun upsert(account: AccountEntity) {
        val existing = get(account.id)
        val merged =
            if (existing == null) {
                account.copy(colorIndex = freeColor(colorsInUse()))
            } else {
                account.copy(
                    colorIndex = existing.colorIndex,
                    displayName =
                        if (existing.displayName !=
                            existing.email
                        ) {
                            existing.displayName
                        } else {
                            account.displayName
                        },
                )
            }
        upsertRow(merged)
    }

    @Query("SELECT * FROM accounts WHERE id = :id")
    abstract suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY createdAtMillis")
    abstract fun all(): Flow<List<AccountEntity>>

    /** See [AccountEntity.authProblem]; null clears it. */
    @Query("UPDATE accounts SET authProblem = :problem WHERE id = :id")
    abstract suspend fun setAuthProblem(
        id: String,
        problem: String?,
    )

    @Query("UPDATE accounts SET displayName = :name WHERE id = :id")
    abstract suspend fun rename(
        id: String,
        name: String,
    )

    @Query("UPDATE accounts SET colorIndex = :colorIndex WHERE id = :id")
    abstract suspend fun setColor(
        id: String,
        colorIndex: Int,
    )

    @Query("SELECT colorIndex FROM accounts")
    protected abstract suspend fun colorsInUse(): List<Int>

    @Query(
        "SELECT a.id AS accountId, MAX(f.lastSyncMillis) AS lastSyncMillis FROM accounts a " +
            "LEFT JOIN folders f ON f.accountId = a.id GROUP BY a.id",
    )
    abstract fun lastSyncs(): Flow<List<AccountSync>>

    /** Local ids of the account's messages (their attachment files are keyed by them). */
    @Query("SELECT id FROM messages WHERE accountId = :id")
    abstract suspend fun messageIds(id: String): List<Long>

    /** Cascades to folders, messages, verdicts, learned rules and undo entries of the account. */
    @Query("DELETE FROM accounts WHERE id = :id")
    abstract suspend fun delete(id: String)

    internal companion object {
        /** The lowest palette index nobody uses; once all are taken, the one used least. */
        fun freeColor(used: List<Int>): Int {
            val counts = used.groupingBy { it.mod(AccountEntity.COLOR_COUNT) }.eachCount()
            return (0 until AccountEntity.COLOR_COUNT).minBy { counts[it] ?: 0 }
        }
    }
}
