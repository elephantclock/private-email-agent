package tech.elephantclock.privatemail.feature.onboarding

import androidx.annotation.StringRes
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.mail.auth.AuthError

/** A string resource and its format arguments, resolved in the UI. */
data class UiText(
    @param:StringRes val res: Int,
    val args: List<Any> = emptyList(),
)

/**
 * What the screen says about a failed sign-in. [headline] is always our own plain-language
 * text; whatever the server said goes in [detail], shown smaller under "Details".
 */
data class ErrorExplanation(
    val headline: UiText,
    val detail: String? = null,
    val helpUrl: String? = null,
)

/**
 * The one place that turns an [AuthError] into words. Exhaustive on purpose: a new error type
 * doesn't compile until it has a message.
 */
fun AuthError.explain(): ErrorExplanation =
    when (this) {
        AuthError.WrongCredentials -> {
            ErrorExplanation(UiText(R.string.error_wrong_credentials))
        }

        is AuthError.AppPasswordRequired -> {
            ErrorExplanation(UiText(R.string.error_app_password_required), helpUrl = helpUrl)
        }

        is AuthError.AccountLockedOrRateLimited -> {
            ErrorExplanation(UiText(R.string.error_account_locked), detail = serverMessage.ifBlank { null })
        }

        is AuthError.HostNotFound -> {
            ErrorExplanation(UiText(R.string.error_host_not_found, listOf(host)))
        }

        is AuthError.PortBlockedOrTimeout -> {
            ErrorExplanation(UiText(R.string.error_port_blocked, listOf(host, port)))
        }

        is AuthError.TlsFailure -> {
            ErrorExplanation(UiText(R.string.error_tls_failure), detail = detail.ifBlank { null })
        }

        AuthError.StartTlsUnavailable -> {
            ErrorExplanation(UiText(R.string.error_starttls_unavailable))
        }

        is AuthError.CertificateUntrusted -> {
            ErrorExplanation(
                UiText(R.string.error_certificate_untrusted, listOf("${certificate.host}:${certificate.port}")),
            )
        }

        is AuthError.AuthMethodNotSupported -> {
            ErrorExplanation(UiText(R.string.error_auth_method_not_supported), detail = serverMessage.ifBlank { null })
        }

        is AuthError.Unknown -> {
            ErrorExplanation(UiText(R.string.error_unknown), detail = detail.ifBlank { null })
        }

        AuthError.PasswordUnavailable -> {
            ErrorExplanation(UiText(R.string.error_password_unavailable))
        }
    }
