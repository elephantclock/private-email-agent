package tech.elephantclock.privatemail.core.mail

import tech.elephantclock.privatemail.core.mail.auth.AuthError

sealed interface MailResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : MailResult<T>

    data class Failed(
        val error: AuthError,
    ) : MailResult<Nothing>
}
