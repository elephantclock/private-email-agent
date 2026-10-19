package tech.elephantclock.privatemail.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction

/** How many Inbox messages got one kind of verdict (screen 03's groups). */
data class VerdictCount(
    val action: VerdictAction,
    val targetRole: FolderRole,
    val targetCustomName: String?,
    val count: Int,
)

/** One line of a drill-down list on screen 03. */
data class ReviewRow(
    val id: Long,
    val fromEmail: String?,
    val fromName: String?,
    val subject: String,
    val receivedAtMillis: Long,
    val reason: String,
)

/** Queries of the first run (T43): the review of the last 30 days and its Apply. */
@Dao
interface FirstRunDao {
    /**
     * Forgets the machine's verdicts on [accountId]'s Inbox so they are made again, after the Sent
     * backfill (verdicts made before it didn't know everyone the user wrote to). The user's own
     * choices and moves still pending are kept.
     */
    @Query(
        "DELETE FROM verdicts WHERE source != 'USER' AND pendingMove = 0 AND messageId IN " +
            "(SELECT id FROM messages WHERE accountId = :accountId AND folderPath = :folderPath)",
    )
    suspend fun clearMachineVerdicts(
        accountId: String,
        folderPath: String,
    ): Int

    @Query(
        "SELECT v.action, v.targetRole, v.targetCustomName, " +
            "COUNT(*) AS count FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "WHERE m.accountId = :accountId AND m.folderPath = :folderPath " +
            "GROUP BY v.action, v.targetRole, v.targetCustomName",
    )
    suspend fun verdictCounts(
        accountId: String,
        folderPath: String,
    ): List<VerdictCount>

    @Query(
        "SELECT COUNT(*) FROM messages m LEFT JOIN verdicts v ON v.messageId = m.id " +
            "WHERE v.messageId IS NULL AND m.accountId = :accountId AND m.folderPath = :folderPath",
    )
    suspend fun untriagedCount(
        accountId: String,
        folderPath: String,
    ): Int

    @Query(
        "SELECT m.id, m.fromEmail, m.fromName, m.subject, m.receivedAtMillis, v.reason " +
            "FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "WHERE m.accountId = :accountId AND m.folderPath = :folderPath AND v.action = :action " +
            "AND v.targetRole = :role ORDER BY m.receivedAtMillis DESC, m.id DESC LIMIT :limit",
    )
    suspend fun reviewRows(
        accountId: String,
        folderPath: String,
        action: VerdictAction,
        role: FolderRole,
        limit: Int,
    ): List<ReviewRow>

    /** Inbox messages whose verdict would move them (anything but KEEP), for Apply. */
    @Transaction
    @Query(
        "SELECT m.* FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "WHERE m.accountId = :accountId AND m.folderPath = :folderPath AND v.action != 'KEEP' ORDER BY m.id",
    )
    suspend fun movable(
        accountId: String,
        folderPath: String,
    ): List<MessageWithVerdict>

    /** Changes whenever the account's Sent rows change (sync or backfill); drives the people list. */
    @Query(
        "SELECT COUNT(*) AS count, MAX(id) AS maxId FROM messages WHERE accountId = :accountId AND folderPath IN " +
            "(SELECT path FROM folders WHERE accountId = :accountId AND specialUse = 'SENT')",
    )
    fun sentFingerprint(accountId: String): Flow<SentFingerprint>
}
