package tech.elephantclock.privatemail.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.model.FolderRole

/** Queries of the rules screen (T48) and the folder-role bookkeeping of triage moves. No schema change. */
@Dao
interface RulesDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolderIfMissing(folder: FolderEntity)

    @Query("UPDATE folders SET role = :role WHERE accountId = :accountId AND path = :path")
    suspend fun setRole(
        accountId: String,
        path: String,
        role: FolderRole,
    )

    /**
     * Gives the folder triage just moved mail into its [role], so the sync keeps it current and
     * reconciles the moved rows (T24's carried-over item). A folder the listing hasn't stored yet
     * (MoveEngine just created it) gets a row first; its sync state starts empty.
     */
    @Transaction
    suspend fun assignRole(
        accountId: String,
        path: String,
        displayName: String,
        role: FolderRole,
    ) {
        insertFolderIfMissing(
            FolderEntity(
                accountId,
                path,
                displayName,
                uidValidity = 0,
                specialUse = null,
                role = role,
                description = null,
            ),
        )
        setRole(accountId, path, role)
    }

    @Query("SELECT COUNT(*) > 0 FROM folders WHERE accountId = :accountId AND specialUse = 'TRASH'")
    fun hasTrash(accountId: String): Flow<Boolean>

    /** The rules that apply to [accountId]: its own and the ones for every account, newest first. */
    @Query(
        "SELECT * FROM learned_rules WHERE accountId IS NULL OR accountId = :accountId ORDER BY createdAtMillis DESC, id DESC",
    )
    fun rules(accountId: String): Flow<List<LearnedRuleEntity>>

    @Query("SELECT * FROM learned_rules WHERE id = :id")
    suspend fun rule(id: Long): LearnedRuleEntity?

    @Query("SELECT COUNT(*) FROM learned_rules WHERE accountId = :accountId")
    suspend fun ownRuleCount(accountId: String): Int
}
