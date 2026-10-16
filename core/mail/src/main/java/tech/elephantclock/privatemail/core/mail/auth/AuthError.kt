package tech.elephantclock.privatemail.core.mail.auth

import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo

/**
 * Why a sign-in failed, in terms the UI can explain. Messages from the server are
 * kept for the details view; they never contain the password (see [ErrorMapper]).
 */
sealed interface AuthError {
    data object WrongCredentials : AuthError

    /** The provider wants an app-specific password (Gmail, iCloud, Yahoo). */
    data class AppPasswordRequired(
        val helpUrl: String?,
    ) : AuthError

    /** Locked, needs a browser sign-in first, or too many attempts. */
    data class AccountLockedOrRateLimited(
        val serverMessage: String,
    ) : AuthError

    data class HostNotFound(
        val host: String,
    ) : AuthError

    data class PortBlockedOrTimeout(
        val host: String,
        val port: Int,
    ) : AuthError

    /** Handshake failed, or implicit TLS was used against a plain-text port. */
    data class TlsFailure(
        val detail: String,
    ) : AuthError

    /** STARTTLS was asked for and the server doesn't offer it. Nothing was sent. */
    data object StartTlsUnavailable : AuthError

    /** The certificate isn't trusted; the user may pin [certificate]. */
    data class CertificateUntrusted(
        val certificate: CertificateInfo,
    ) : AuthError

    /** No password mechanism we support, or the provider has turned password sign-in off. */
    data class AuthMethodNotSupported(
        val serverMessage: String,
    ) : AuthError

    data class Unknown(
        val detail: String,
    ) : AuthError
}
