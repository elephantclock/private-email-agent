package tech.elephantclock.privatemail.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A message with its verdict, if it has been triaged. */
data class MessageWithVerdict(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId") val verdict: VerdictEntity?,
)

@Dao
interface AccountDao {
    @Upsert suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY createdAtMillis")
    fun all(): Flow<List<AccountEntity>>

    /** Cascades to folders, messages, verdicts, learned rules and undo entries of the account. */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface FolderDao {
    @Upsert suspend fun upsert(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders WHERE accountId = :accountId ORDER BY path")
    suspend fun forAccount(accountId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE accountId = :accountId AND path = :path")
    suspend fun get(
        accountId: String,
        path: String,
    ): FolderEntity?

    @Query(
        "UPDATE folders SET uidValidity = :uidValidity, highestUid = :highestUid, lastSyncMillis = :lastSyncMillis " +
            "WHERE accountId = :accountId AND path = :path",
    )
    suspend fun updateSyncState(
        accountId: String,
        path: String,
        uidValidity: Long,
        highestUid: Long,
        lastSyncMillis: Long,
    )
}

@Dao
interface MessageDao {
    /** New messages only; an already-known server message returns -1 for its row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uidValidity = :uidValidity AND uid = :uid",
    )
    suspend fun byServerRef(
        accountId: String,
        folderPath: String,
        uidValidity: Long,
        uid: Long,
    ): MessageEntity?

    @Query(
        "UPDATE messages SET flags = :flags WHERE accountId = :accountId AND folderPath = :folderPath " +
            "AND uidValidity = :uidValidity AND uid = :uid",
    )
    suspend fun updateFlags(
        accountId: String,
        folderPath: String,
        uidValidity: Long,
        uid: Long,
        flags: Int,
    )

    /** After a move on the server (T17): same local id, new server location. */
    @Query("UPDATE messages SET folderPath = :folderPath, uidValidity = :uidValidity, uid = :uid WHERE id = :id")
    suspend fun moveTo(
        id: Long,
        folderPath: String,
        uidValidity: Long,
        uid: Long,
    )

    /** UIDVALIDITY changed: everything stored for the folder is stale. */
    @Query("DELETE FROM messages WHERE accountId = :accountId AND folderPath = :folderPath")
    suspend fun deleteFolder(
        accountId: String,
        folderPath: String,
    )

    /** Priority: kept messages across all accounts, newest first. */
    @Transaction
    @Query(
        "SELECT m.* FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "WHERE v.action = 'KEEP' ORDER BY m.receivedAtMillis DESC, m.id DESC",
    )
    fun priority(): PagingSource<Int, MessageWithVerdict>

    @Transaction
    @Query(
        "SELECT * FROM messages WHERE accountId = :accountId AND folderPath = :folderPath " +
            "ORDER BY receivedAtMillis DESC, id DESC",
    )
    fun inFolder(
        accountId: String,
        folderPath: String,
    ): PagingSource<Int, MessageWithVerdict>

    /** Messages that still need triage (T24), oldest first. */
    @Query(
        "SELECT m.* FROM messages m LEFT JOIN verdicts v ON v.messageId = m.id " +
            "WHERE v.messageId IS NULL ORDER BY m.receivedAtMillis LIMIT :limit",
    )
    suspend fun untriaged(limit: Int): List<MessageEntity>

    /** [ftsQuery] must come from [FtsQuery.of]; raw user text can be invalid MATCH syntax. */
    @Transaction
    @Query(
        "SELECT m.* FROM messages m JOIN messages_fts f ON f.rowid = m.id " +
            "WHERE messages_fts MATCH :ftsQuery ORDER BY m.receivedAtMillis DESC LIMIT :limit",
    )
    suspend fun search(
        ftsQuery: String,
        limit: Int = 50,
    ): List<MessageWithVerdict>
}

@Dao
interface VerdictDao {
    @Upsert suspend fun upsert(verdict: VerdictEntity)

    @Upsert suspend fun upsertAll(verdicts: List<VerdictEntity>)

    @Query("SELECT * FROM verdicts WHERE messageId = :messageId")
    suspend fun get(messageId: Long): VerdictEntity?
}

@Dao
interface LearnedRuleDao {
    /** Same matcher again replaces the old rule (the newest correction wins). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rule: LearnedRuleEntity): Long

    @Query(
        "SELECT * FROM learned_rules WHERE accountId IS NULL OR accountId = :accountId ORDER BY createdAtMillis DESC",
    )
    suspend fun forAccount(accountId: String): List<LearnedRuleEntity>

    @Query("DELETE FROM learned_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface UndoDao {
    @Insert suspend fun insertAll(entries: List<UndoEntity>)

    @Query("SELECT * FROM undo_log WHERE accountId = :accountId AND batchId = :batchId")
    suspend fun batch(
        accountId: String,
        batchId: String,
    ): List<UndoEntity>

    @Query("DELETE FROM undo_log WHERE accountId = :accountId AND batchId = :batchId")
    suspend fun deleteBatch(
        accountId: String,
        batchId: String,
    )

    /** Undo is offered for 7 days. */
    @Query("DELETE FROM undo_log WHERE createdAtMillis < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long): Int
}
