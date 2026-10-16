package tech.elephantclock.privatemail.core.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * Full-text index over [MessageEntity]; Room keeps it in step with triggers. rowid = message id.
 * unicode61 folds case and accents in every script ("simple" folds ASCII only, so "Заказ" was unfindable).
 */
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val subject: String,
    val fromName: String?,
    val fromEmail: String?,
    val snippet: String,
)
