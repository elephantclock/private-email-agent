package tech.elephantclock.privatemail.feature.reply

import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toHeader
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.repo.DraftOutcome
import tech.elephantclock.privatemail.core.data.repo.OutgoingMailRepository
import tech.elephantclock.privatemail.core.data.repo.SendOutcome
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.StoredMessage
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.MessageHeader

/** The message being answered and the account answering it. */
data class ReplyContext(
    val account: Account,
    val parent: MessageHeader,
)

/** What the reply editor needs. [RoomReplySource] in the app; fakes in tests. */
interface ReplySource {
    suspend fun context(parentId: Long): ReplyContext?

    suspend fun saveDraft(
        accountId: AccountId,
        message: OutgoingMessage,
        replacing: StoredMessage?,
    ): DraftOutcome

    /** Marked, not opted in: only the confirmed Send handler may call it. */
    @UserInitiatedSend
    suspend fun send(
        accountId: AccountId,
        message: OutgoingMessage,
        approval: SendApproval,
        draft: StoredMessage?,
    ): SendOutcome
}

class RoomReplySource(
    private val db: AppDatabase,
    private val outgoing: OutgoingMailRepository,
) : ReplySource {
    override suspend fun context(parentId: Long): ReplyContext? {
        val message = db.messages().get(parentId) ?: return null
        val account = db.accounts().get(message.accountId) ?: return null
        return ReplyContext(account.toModel(), message.toHeader())
    }

    override suspend fun saveDraft(
        accountId: AccountId,
        message: OutgoingMessage,
        replacing: StoredMessage?,
    ) = outgoing.saveDraft(accountId, message, replacing)

    @UserInitiatedSend
    override suspend fun send(
        accountId: AccountId,
        message: OutgoingMessage,
        approval: SendApproval,
        draft: StoredMessage?,
    ) = outgoing.send(accountId, message, approval, draft)
}
