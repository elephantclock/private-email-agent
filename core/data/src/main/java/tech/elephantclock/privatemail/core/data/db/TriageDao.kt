package tech.elephantclock.privatemail.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Recipients and Message-ID of one message in a Sent folder, for the relationship index. */
data class SentRow(
    val toJson: String,
    val ccJson: String,
    val messageIdHeader: String?,
    val sentAtMillis: Long?,
    val receivedAtMillis: Long,
)

/** Changes whenever Sent rows are added or removed; the relationship index is rebuilt then. */
data class SentFingerprint(
    val count: Int,
    val maxId: Long?,
)

/** Queries of the triage orchestrator (T24). */
@Dao
interface TriageDao {
    /**
     * Messages in [folderPath] without a verdict, oldest first. Triage only reads the Inbox: mail
     * that arrives elsewhere (Sent, Drafts, a folder the user filed into) was put there on purpose.
     */
    @Query(
        "SELECT m.* FROM messages m LEFT JOIN verdicts v ON v.messageId = m.id " +
            "WHERE v.messageId IS NULL AND m.folderPath = :folderPath ORDER BY m.receivedAtMillis, m.id LIMIT :limit",
    )
    suspend fun untriagedIn(
        folderPath: String,
        limit: Int,
    ): List<MessageEntity>

    /** Messages still in [folderPath] whose triage move hasn't been confirmed (see [VerdictEntity.pendingMove]). */
    @Transaction
    @Query(
        "SELECT m.* FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "WHERE v.pendingMove = 1 AND m.accountId = :accountId AND m.folderPath = :folderPath " +
            "ORDER BY m.id LIMIT :limit",
    )
    suspend fun pendingMoves(
        accountId: String,
        folderPath: String,
        limit: Int,
    ): List<MessageWithVerdict>

    @Query("SELECT DISTINCT accountId FROM messages m JOIN verdicts v ON v.messageId = m.id WHERE v.pendingMove = 1")
    suspend fun accountsWithPendingMoves(): List<String>

    @Query("UPDATE verdicts SET pendingMove = :pending WHERE messageId = :messageId")
    suspend fun setPendingMove(
        messageId: Long,
        pending: Boolean,
    )

    /** After a move on the server: same local id, new server location and the time of the move. */
    @Query(
        "UPDATE messages SET folderPath = :folderPath, uidValidity = :uidValidity, uid = :uid, " +
            "movedAtMillis = :movedAtMillis WHERE id = :id",
    )
    suspend fun recordMove(
        id: Long,
        folderPath: String,
        uidValidity: Long,
        uid: Long,
        movedAtMillis: Long?,
    )

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("SELECT path FROM folders WHERE accountId = :accountId AND specialUse = 'SENT'")
    suspend fun sentFolders(accountId: String): List<String>

    @Query(
        "SELECT toJson, ccJson, messageIdHeader, sentAtMillis, receivedAtMillis FROM messages " +
            "WHERE accountId = :accountId AND folderPath IN (:paths)",
    )
    suspend fun sentRows(
        accountId: String,
        paths: List<String>,
    ): List<SentRow>

    @Query(
        "SELECT COUNT(*) AS count, MAX(id) AS maxId FROM messages WHERE accountId = :accountId AND folderPath IN (:paths)",
    )
    suspend fun sentFingerprint(
        accountId: String,
        paths: List<String>,
    ): SentFingerprint

    /** Messages triage discarded at or before [cutoffMillis] and that are still outside the Inbox. */
    @Query(
        "SELECT m.* FROM messages m JOIN verdicts v ON v.messageId = m.id WHERE m.accountId = :accountId " +
            "AND v.action = 'DISCARD' AND m.folderPath != 'INBOX' AND m.movedAtMillis IS NOT NULL " +
            "AND m.movedAtMillis <= :cutoffMillis ORDER BY m.id",
    )
    suspend fun discardedBefore(
        accountId: String,
        cutoffMillis: Long,
    ): List<MessageEntity>
}
