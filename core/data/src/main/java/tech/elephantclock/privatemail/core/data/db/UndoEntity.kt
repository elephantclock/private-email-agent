package tech.elephantclock.privatemail.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import tech.elephantclock.privatemail.core.model.VerdictAction

/** One move the app made on the server, kept 7 days so it can be undone (T17). */
@Entity(
    tableName = "undo_log",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("createdAtMillis"), Index("accountId", "batchId")],
)
data class UndoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    /** Moves made together (one tap, one triage run) share a batch id and undo together. */
    val batchId: String,
    /** Local message id; not a foreign key, so the entry survives a re-sync of the message. */
    val messageId: Long,
    val action: VerdictAction,
    val fromFolder: String,
    val fromUidValidity: Long,
    val fromUid: Long,
    val toFolder: String,
    /** From COPYUID when the server supports UIDPLUS; else found by Message-ID on undo. */
    val toUid: Long?,
    val messageIdHeader: String?,
    val createdAtMillis: Long,
)
