package tech.elephantclock.privatemail.core.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import tech.elephantclock.privatemail.core.model.AuthType
import tech.elephantclock.privatemail.core.model.ConnectionSecurity

data class ServerColumns(
    val host: String,
    val port: Int,
    val security: ConnectionSecurity,
)

/** An account; the password is in `SecretStore`, never here. */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val emailName: String?,
    val displayName: String,
    val username: String,
    @Embedded(prefix = "imap_") val imap: ServerColumns,
    @Embedded(prefix = "smtp_") val smtp: ServerColumns,
    val authType: AuthType,
    val providerId: String?,
    val createdAtMillis: Long,
)
