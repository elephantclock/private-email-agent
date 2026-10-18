package tech.elephantclock.privatemail.feature.onboarding

import androidx.compose.runtime.Immutable
import tech.elephantclock.privatemail.core.mail.autoconfig.ProviderNote
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig

/** One server as typed: strings, so half-typed values are allowed while editing. */
@Immutable
data class ServerFields(
    val host: String = "",
    val port: String = "",
    val security: ConnectionSecurity = ConnectionSecurity.SSL_TLS,
) {
    fun toConfig(): ServerConfig? {
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val h = host.trim().trimEnd('.').takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return null
        return ServerConfig(h, p, security)
    }

    companion object {
        fun of(config: ServerConfig) = ServerFields(config.host, config.port.toString(), config.security)
    }
}

enum class DiscoveryStatus {
    /** Nothing looked up yet for the current address. */
    Idle,
    Running,
    Found,

    /** Nothing found: the server settings are open for the user to fill in. */
    Manual,
}

/** Which server a problem is about; the same error on both is shown once. */
enum class ServerRole { Incoming, Outgoing, Both }

data class ServerProblem(
    val role: ServerRole,
    val explanation: ErrorExplanation,
)

enum class Busy { Testing, Saving }

/**
 * Everything the add-account screen shows. The password is deliberately not in here (the
 * view model keeps it apart), so this state can be printed, compared and saved freely.
 */
@Immutable
data class AddAccountUiState(
    val email: String = "",
    val discovery: DiscoveryStatus = DiscoveryStatus.Idle,
    /** Preset id (e.g. "gmail"), stored with the account. */
    val providerId: String? = null,
    val providerName: String? = null,
    val note: ProviderNote? = null,
    val passwordSupported: Boolean = true,
    val username: String = "",
    val imap: ServerFields = ServerFields(),
    val smtp: ServerFields = ServerFields(security = ConnectionSecurity.STARTTLS),
    val serverSettingsOpen: Boolean = false,
    /**
     * The hosts came from unauthenticated DNS outside the email's domain: nothing that sends the
     * password runs until the user has seen them and said OK (RFC 6186 § 6).
     */
    val confirmationPending: Boolean = false,
    val busy: Busy? = null,
    val problems: List<ServerProblem> = emptyList(),
    val testPassed: Boolean = false,
    /** A certificate waiting for the user's trust decision. */
    val certificate: CertificateInfo? = null,
    val saved: Boolean = false,
) {
    val emailValid: Boolean get() = looksLikeEmail(email)

    val serversValid: Boolean get() = imap.toConfig() != null && smtp.toConfig() != null && username.isNotBlank()
}

internal fun looksLikeEmail(text: String): Boolean {
    val t = text.trim()
    val at = t.lastIndexOf('@')
    return at > 0 && at < t.length - 1 && '.' in t.substring(at + 1) && t.none(Char::isWhitespace)
}
