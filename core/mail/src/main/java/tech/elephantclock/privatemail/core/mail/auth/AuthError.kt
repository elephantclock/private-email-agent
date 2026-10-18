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

    /** No saved password, or it can't be decrypted any more (`StoredSecret.NeedsReentry`). Nothing was sent. */
    data object PasswordUnavailable : AuthError
}

/** Retrying can't fix this until the user acts: a new password, trusting a certificate, other settings. */
val AuthError.needsUserAction: Boolean
    get() =
        when (this) {
            AuthError.WrongCredentials,
            AuthError.StartTlsUnavailable,
            AuthError.PasswordUnavailable,
            is AuthError.AppPasswordRequired,
            is AuthError.CertificateUntrusted,
            is AuthError.AuthMethodNotSupported,
            -> true

            is AuthError.AccountLockedOrRateLimited,
            is AuthError.HostNotFound,
            is AuthError.PortBlockedOrTimeout,
            is AuthError.TlsFailure,
            is AuthError.Unknown,
            -> false
        }

/**
 * Don't retry within a session (IDLE reconnects): [needsUserAction], or the server says the
 * account is locked or rate-limited, where every extra sign-in attempt makes things worse.
 */
val AuthError.stopRetrying: Boolean
    get() = needsUserAction || this is AuthError.AccountLockedOrRateLimited
