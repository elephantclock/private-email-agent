package tech.elephantclock.privatemail.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

/** The current triage decision for a message (one per message; the user's correction replaces it). */
@Entity(
    tableName = "verdicts",
    foreignKeys = [ForeignKey(MessageEntity::class, ["id"], ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("action", "importance")],
)
data class VerdictEntity(
    @PrimaryKey val messageId: Long,
    val importance: Importance,
    val targetRole: FolderRole,
    val targetCustomName: String?,
    val action: VerdictAction,
    val source: VerdictSource,
    val reason: String,
    val confidence: Float,
    val summary: String?,
    val decidedAtMillis: Long,
    /**
     * Triage decided to move the message (auto-file was on) and the move hasn't been confirmed
     * yet; retried until it is. Never set again after an undo, so an undone move stays undone.
     * Added in schema v2.
     */
    @ColumnInfo(defaultValue = "0") val pendingMove: Boolean = false,
)
