package tech.elephantclock.privatemail.core.data.db

import androidx.room.Entity
import androidx.room.Fts4

/** Full-text index over [MessageEntity]; Room keeps it in step with triggers. rowid = message id. */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val subject: String,
    val fromName: String?,
    val fromEmail: String?,
    val snippet: String,
)
