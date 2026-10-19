package tech.elephantclock.privatemail.core.data.db

import androidx.room.ColumnInfo
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
    /**
     * Set when signing in failed in a way only the user can fix (wrong password, untrusted
     * certificate...): background sync and IDLE skip the account, since retrying every 15
     * minutes can get it locked. Holds the `AuthError` name. Cleared once the user fixes it (T41).
     */
    val authProblem: String? = null,
    /**
     * The account's colour (T52), an index into the app's palette of [COLOR_COUNT]. Chosen once
     * when the account is added (the first one no other account has) and changed only by the
     * user, so it never shifts when another account is removed. [AccountDao.upsert] keeps it.
     */
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,
) {
    companion object {
        /** Colours in the app's account palette (`AccountPalette`). */
        const val COLOR_COUNT = 5
    }
}
