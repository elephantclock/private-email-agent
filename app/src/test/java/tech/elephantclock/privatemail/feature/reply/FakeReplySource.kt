package tech.elephantclock.privatemail.feature.reply

import tech.elephantclock.privatemail.core.data.repo.DraftOutcome
import tech.elephantclock.privatemail.core.data.repo.SendOutcome
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.SendResult
import tech.elephantclock.privatemail.core.mail.smtp.SentCopy
import tech.elephantclock.privatemail.core.mail.smtp.StoredMessage
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.inbox.ReadingFixtures

/** Records drafts and sends; never talks to a server. */
class FakeReplySource : ReplySource {
    val drafts = mutableListOf<Pair<OutgoingMessage, StoredMessage?>>()
    val sends = mutableListOf<Pair<OutgoingMessage, StoredMessage?>>()
    var sendResult: () -> SendOutcome = {
        SendOutcome.Done(SendResult.Sent("<id@home.example>", SentCopy.Saved(stored(99, "<id>"))))
    }
    var context: ReplyContext? =
        ReplyContext(
            ReadingFixtures.home,
            ReadingFixtures.header(4, "Prospective client", "Chatbot for our hotels", 319, ReadingFixtures.home),
        )

    override suspend fun context(parentId: Long) = context

    override suspend fun saveDraft(
        accountId: AccountId,
        message: OutgoingMessage,
        replacing: StoredMessage?,
    ): DraftOutcome {
        drafts += message to replacing
        return DraftOutcome.Saved(stored(drafts.size.toLong(), message.messageId))
    }

    @UserInitiatedSend
    override suspend fun send(
        accountId: AccountId,
        message: OutgoingMessage,
        approval: SendApproval,
        draft: StoredMessage?,
    ): SendOutcome {
        sends += message to draft
        return sendResult()
    }

    companion object {
        fun stored(
            uid: Long,
            messageId: String,
        ) = StoredMessage("Drafts", 1, uid, messageId)
    }
}
