package tech.elephantclock.privatemail.core.mail.smtp

import tech.elephantclock.privatemail.core.mail.auth.AuthError

/** Where a message was stored by APPEND. [uid] is null when the server has no UIDPLUS and the copy wasn't found. */
data class StoredMessage(
    val folderPath: String,
    val uidValidity: Long,
    val uid: Long?,
    val messageId: String,
)

sealed interface SendResult {
    /** The SMTP server accepted the message for every recipient. Never send it again. */
    data class Sent(
        val messageId: String,
        val sentCopy: SentCopy,
    ) : SendResult

    /** Nothing was sent. */
    data class NotSent(
        val error: SendError,
    ) : SendResult
}

/** What happened to the copy in Sent after a successful send. */
sealed interface SentCopy {
    data class Saved(
        val stored: StoredMessage,
    ) : SentCopy

    /** Gmail and Microsoft file sent mail themselves; a second copy would be a duplicate. */
    data object SavedByProvider : SentCopy

    /** No IMAP connection was given. */
    data object Skipped : SentCopy

    /** The mail went out, but the copy couldn't be stored. Show it; never resend because of it. */
    data class Failed(
        val detail: String,
    ) : SentCopy
}

sealed interface SendError {
    /** See [SendApproval]. Nothing was contacted. */
    data class NotApproved(
        val reason: String,
    ) : SendError

    data object NoRecipients : SendError

    /** An address or header the message can't be built with (e.g. "bob@"). Nothing was contacted. */
    data class InvalidMessage(
        val detail: String,
    ) : SendError

    /** Sign-in, TLS or network failure before the message was accepted. */
    data class Connection(
        val error: AuthError,
    ) : SendError

    /** The server refused these recipients; the message went to nobody (no partial sends). */
    data class RecipientsRejected(
        val addresses: List<String>,
        val serverMessage: String,
    ) : SendError

    /** The server refused the message itself (size, policy, sender address). */
    data class Rejected(
        val serverMessage: String,
    ) : SendError
}
