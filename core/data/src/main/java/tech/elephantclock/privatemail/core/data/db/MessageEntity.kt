package tech.elephantclock.privatemail.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One message. [id] is the local, stable message id: it survives moves between folders
 * (T17 updates [folderPath] / [uid]). The server identity (folder, UIDVALIDITY, UID) is
 * unique but stored separately, and the RFC `Message-ID` header ([messageIdHeader]) is only
 * an index: it can be missing or shared by copies.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["accountId", "folderPath", "uidValidity", "uid"], unique = true),
        Index("accountId", "folderPath", "receivedAtMillis"),
        Index("receivedAtMillis"),
        Index("accountId", "messageIdHeader"),
        Index("accountId", "fromEmail"),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val folderPath: String,
    val uidValidity: Long,
    val uid: Long,
    val messageIdHeader: String?,
    val fromEmail: String?,
    val fromName: String?,
    val subject: String,
    val sentAtMillis: Long?,
    val receivedAtMillis: Long,
    /** JSON lists of `Address`. */
    val toJson: String,
    val ccJson: String,
    val replyToJson: String,
    val inReplyTo: String?,
    val referencesJson: String,
    /** Bit set of `MessageFlag` ordinals. */
    val flags: Int,
    val sizeBytes: Long,
    /** JSON map of lower-cased header name → values, in order. */
    val headersJson: String,
    val snippet: String,
    val snippetTruncated: Boolean,
    val snippetMimeType: String,
    /** JSON list of `Attachment`. */
    val attachmentsJson: String,
    val hasAttachments: Boolean,
)
