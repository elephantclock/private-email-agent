package tech.elephantclock.privatemail.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.model.VerdictAction

/** One discarded (or junked) message with what the Discarded digest shows about it (T47). */
data class DiscardedRow(
    val id: Long,
    val accountId: String,
    val accountEmail: String,
    val accountName: String?,
    val folderPath: String,
    val fromEmail: String?,
    val fromName: String?,
    val subject: String,
    val receivedAtMillis: Long,
    /** JSON map of lower-cased header name → values (List-Unsubscribe, Authentication-Results...). */
    val headersJson: String,
    val action: VerdictAction,
    val reason: String,
    val decidedAtMillis: Long,
)

/** How many verdicts of each action were decided in a time range (the daily digest, T49). */
data class ActionCount(
    val action: VerdictAction,
    @ColumnInfo(name = "n") val count: Int,
)

/** A message that would have alerted but failed authentication, so it got no notification. */
data class SuppressedAlertRow(
    val id: Long,
    val accountId: String,
    val fromEmail: String?,
    val fromName: String?,
    val subject: String,
    val receivedAtMillis: Long,
)

/**
 * Queries of the Discarded digest (T47) and the notifications (T49). Read-only apart from the
 * verdicts the digest's actions write through [VerdictDao].
 */
@Dao
interface DiscardDao {
    /**
     * Messages triage discarded or junked, newest decision first, wherever they are: still in the
     * Inbox while auto-file is off (label only), or in Discarded / Junk once moved. Purged rows
     * (moved to Trash after 30 days) are gone from the table.
     */
    @Query(
        "SELECT m.id, m.accountId, a.email AS accountEmail, a.emailName AS accountName, m.folderPath, " +
            "m.fromEmail, m.fromName, m.subject, m.receivedAtMillis, m.headersJson, v.action, v.reason, " +
            "v.decidedAtMillis FROM messages m JOIN verdicts v ON v.messageId = m.id " +
            "JOIN accounts a ON a.id = m.accountId WHERE v.action IN ('DISCARD', 'JUNK') " +
            "ORDER BY v.decidedAtMillis DESC, m.id DESC LIMIT :limit",
    )
    fun discarded(limit: Int = MAX_ROWS): Flow<List<DiscardedRow>>

    @Query(
        "SELECT v.action, COUNT(*) AS n FROM verdicts v " +
            "WHERE v.decidedAtMillis >= :fromMillis AND v.decidedAtMillis < :toMillis GROUP BY v.action",
    )
    suspend fun countsBetween(
        fromMillis: Long,
        toMillis: Long,
    ): List<ActionCount>

    /** Unread Inbox messages with an URGENT or HIGH verdict decided in the range: "needs you". */
    @Query(
        "SELECT COUNT(*) FROM messages m JOIN verdicts v ON v.messageId = m.id WHERE m.folderPath = 'INBOX' " +
            "AND v.action = 'KEEP' AND v.importance IN ('URGENT', 'HIGH') AND (m.flags & 1) = 0 " +
            "AND v.decidedAtMillis >= :fromMillis AND v.decidedAtMillis < :toMillis",
    )
    suspend fun importantUnreadBetween(
        fromMillis: Long,
        toMillis: Long,
    ): Int

    /**
     * Unread Inbox messages whose alert was suppressed because authentication failed: the rules
     * engine marks a VIP's reason with [UNVERIFIED_VIP_SUFFIX], the orchestrator any other alert's
     * with [SUPPRESSED_ALERT_SUFFIX] (both pinned by a test). The inbox shows these as a badge
     * instead of staying silent (T49). `flags & 1` is `MessageFlag.SEEN`.
     */
    @Query(
        "SELECT m.id, m.accountId, m.fromEmail, m.fromName, m.subject, m.receivedAtMillis FROM messages m " +
            "JOIN verdicts v ON v.messageId = m.id WHERE m.folderPath = 'INBOX' AND (m.flags & 1) = 0 " +
            "AND v.source != 'USER' AND (substr(v.reason, -length(:suffix)) = :suffix " +
            "OR substr(v.reason, -length(:vipSuffix)) = :vipSuffix) " +
            "ORDER BY m.receivedAtMillis DESC LIMIT :limit",
    )
    fun suppressedAlerts(
        suffix: String = SUPPRESSED_ALERT_SUFFIX,
        vipSuffix: String = UNVERIFIED_VIP_SUFFIX,
        limit: Int = MAX_ROWS,
    ): Flow<List<SuppressedAlertRow>>

    /** The account's special-use Archive folder, if the server has one. */
    @Query("SELECT path FROM folders WHERE accountId = :accountId AND specialUse = 'ARCHIVE' ORDER BY path LIMIT 1")
    suspend fun archiveFolder(accountId: String): String?

    companion object {
        const val MAX_ROWS = 2_000

        /** Appended by `TriageOrchestrator` to the reason of an alert it held back; pinned by a test. */
        const val SUPPRESSED_ALERT_SUFFIX = " (no alert: failed authentication)"

        /** Appended by `RulesEngine` to a VIP verdict whose message failed authentication. */
        const val UNVERIFIED_VIP_SUFFIX = ", but the message failed authentication"
    }
}
