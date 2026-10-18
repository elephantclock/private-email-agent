package tech.elephantclock.privatemail.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.SpecialUse

/** Priority messages of one account (for the account filter chips). */
data class AccountCount(
    val accountId: String,
    val count: Int,
)

/** A folder that has been synced at least once, with how many messages are stored for it. */
data class FolderSummary(
    val accountId: String,
    val path: String,
    val displayName: String,
    val role: FolderRole?,
    val specialUse: SpecialUse?,
    val total: Int,
    val unread: Int,
)

/**
 * What triage decided since a point in time ("today"): [filed] = FILE verdicts, [discarded] =
 * DISCARD and JUNK verdicts. [stillInInbox] counts those of them that are still in the Inbox on
 * the server (label-only mode, or a move that hasn't happened yet).
 */
data class TriageCounts(
    val filed: Int,
    val discarded: Int,
    val stillInInbox: Int,
)

/** Queries of the reading screens (T44–T45). Read-only; no schema of its own. */
@Dao
interface ReadingDao {
    /** [MessageDao.priority] for one account: KEEP verdict and still in the Inbox. */
    @Transaction
    @Query(
        "SELECT m.* FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "WHERE v.action = 'KEEP' AND m.folderPath = 'INBOX' AND m.accountId = :accountId " +
            "ORDER BY m.receivedAtMillis DESC, m.id DESC",
    )
    fun priorityIn(accountId: String): PagingSource<Int, MessageWithVerdict>

    @Query(
        "SELECT m.accountId AS accountId, COUNT(*) AS count FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "WHERE v.action = 'KEEP' AND m.folderPath = 'INBOX' GROUP BY m.accountId",
    )
    fun priorityCounts(): Flow<List<AccountCount>>

    /**
     * Folders the app syncs (they have a sync time), Inbox first. Others are listed by the server
     * but have no stored messages, so they aren't offered.
     */
    @Query(
        "SELECT f.accountId AS accountId, f.path AS path, f.displayName AS displayName, f.role AS role, " +
            "f.specialUse AS specialUse, COUNT(m.id) AS total, " +
            "COALESCE(SUM(CASE WHEN (m.flags & 1) = 0 THEN 1 ELSE 0 END), 0) AS unread " +
            "FROM folders f LEFT JOIN messages m ON m.accountId = f.accountId AND m.folderPath = f.path " +
            "WHERE f.accountId = :accountId AND f.lastSyncMillis IS NOT NULL " +
            "GROUP BY f.accountId, f.path ORDER BY (f.path = 'INBOX') DESC, f.displayName COLLATE NOCASE",
    )
    fun syncedFolders(accountId: String): Flow<List<FolderSummary>>

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN v.action = 'FILE' THEN 1 ELSE 0 END), 0) AS filed, " +
            "COALESCE(SUM(CASE WHEN v.action IN ('DISCARD', 'JUNK') THEN 1 ELSE 0 END), 0) AS discarded, " +
            "COALESCE(SUM(CASE WHEN m.folderPath = 'INBOX' THEN 1 ELSE 0 END), 0) AS stillInInbox " +
            "FROM verdicts v JOIN messages m ON m.id = v.messageId " +
            "WHERE v.action != 'KEEP' AND v.decidedAtMillis >= :sinceMillis " +
            "AND (:accountId IS NULL OR m.accountId = :accountId)",
    )
    fun triagedSince(
        sinceMillis: Long,
        accountId: String?,
    ): Flow<TriageCounts>

    /** One message and its verdict, following moves and verdict changes; null once it's gone. */
    @Transaction
    @Query("SELECT * FROM messages WHERE id = :id")
    fun message(id: Long): Flow<MessageWithVerdict?>
}
