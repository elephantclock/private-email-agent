package tech.elephantclock.privatemail.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class AccountId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }

    override fun toString() = value
}

@Serializable
enum class ConnectionSecurity { SSL_TLS, STARTTLS, NONE }

@Serializable
enum class AuthType { PASSWORD, OAUTH2 }

@Serializable
data class ServerConfig(
    val host: String,
    val port: Int,
    val security: ConnectionSecurity,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }
}

/**
 * A mailbox the app signs in to. Holds no secrets: the password lives in the
 * Keystore-backed secret store, keyed by [id].
 */
@Serializable
data class Account(
    val id: AccountId,
    val email: Address,
    val displayName: String,
    val username: String,
    val imap: ServerConfig,
    val smtp: ServerConfig,
    val authType: AuthType = AuthType.PASSWORD,
    /** Preset that supplied the settings (e.g. "gmail"), or null for discovered/manual. */
    val providerId: String? = null,
)
