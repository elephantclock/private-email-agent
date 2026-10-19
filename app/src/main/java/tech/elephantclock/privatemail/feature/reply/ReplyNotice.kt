package tech.elephantclock.privatemail.feature.reply

import androidx.annotation.StringRes
import tech.elephantclock.privatemail.R
import tech.elephantclock.privatemail.core.data.repo.SendOutcome
import tech.elephantclock.privatemail.core.mail.smtp.SendError
import tech.elephantclock.privatemail.core.mail.smtp.SendResult
import tech.elephantclock.privatemail.core.mail.smtp.SentCopy

/**
 * What the reply editor tells the user. Exhaustive over [SendResult], [SendError] and
 * [SentCopy], so a new outcome doesn't compile until it has words. [sent] = the server took it.
 */
sealed class ReplyNotice(
    @param:StringRes val text: Int,
    val sent: Boolean = false,
    /** Server text or addresses, shown under the message. */
    val detail: String? = null,
) {
    data object Sent : ReplyNotice(R.string.reply_sent, sent = true)

    data object SentByProvider : ReplyNotice(R.string.reply_sent, sent = true)

    data object SentNoCopy : ReplyNotice(R.string.reply_sent_no_copy, sent = true)

    class SentCopyFailed(
        detail: String,
    ) : ReplyNotice(R.string.reply_sent_copy_failed, sent = true, detail = detail)

    /** A drop after DATA can still deliver: never claim it wasn't sent. */
    data object MaybeNotSent : ReplyNotice(R.string.reply_maybe_not_sent)

    data object NotApproved : ReplyNotice(R.string.reply_not_approved)

    data object NoRecipient : ReplyNotice(R.string.reply_no_recipient)

    data object BadAddress : ReplyNotice(R.string.reply_bad_address)

    class RecipientsRejected(
        addresses: List<String>,
    ) : ReplyNotice(R.string.reply_recipients_rejected, detail = addresses.joinToString(", "))

    class Rejected(
        serverMessage: String,
    ) : ReplyNotice(R.string.reply_rejected, detail = serverMessage)

    data object NeedsSignIn : ReplyNotice(R.string.problem_sign_in)

    data object Unexpected : ReplyNotice(R.string.reply_unexpected)

    data object DraftSaved : ReplyNotice(R.string.reply_draft_saved)

    data object DraftFailed : ReplyNotice(R.string.reply_draft_failed)

    data object NotReady : ReplyNotice(R.string.reply_not_ready)

    companion object {
        fun of(outcome: SendOutcome?): ReplyNotice =
            when (outcome) {
                null -> {
                    Unexpected
                }

                SendOutcome.NeedsSignIn -> {
                    NeedsSignIn
                }

                is SendOutcome.Done -> {
                    when (val r = outcome.result) {
                        is SendResult.Sent -> {
                            when (val copy = r.sentCopy) {
                                is SentCopy.Saved -> Sent
                                SentCopy.SavedByProvider -> SentByProvider
                                SentCopy.Skipped -> SentNoCopy
                                is SentCopy.Failed -> SentCopyFailed(copy.detail)
                            }
                        }

                        is SendResult.NotSent -> {
                            when (val e = r.error) {
                                is SendError.Connection -> MaybeNotSent
                                is SendError.NotApproved -> NotApproved
                                SendError.NoRecipients -> NoRecipient
                                is SendError.InvalidMessage -> BadAddress
                                is SendError.RecipientsRejected -> RecipientsRejected(e.addresses)
                                is SendError.Rejected -> Rejected(e.serverMessage)
                            }
                        }
                    }
                }
            }
    }
}
