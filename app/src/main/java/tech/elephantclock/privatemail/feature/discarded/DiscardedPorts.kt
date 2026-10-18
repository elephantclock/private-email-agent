package tech.elephantclock.privatemail.feature.discarded

import kotlinx.coroutines.flow.Flow
import tech.elephantclock.privatemail.core.data.triage.RestoreReport
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address

/** The discarded and junked messages, live. */
fun interface DiscardedSource {
    fun items(): Flow<List<DiscardedItem>>
}

/** Restore and never-discard (backed by `core:data`'s `DiscardedActions`). */
interface DiscardedCommands {
    suspend fun restore(ids: List<Long>): RestoreReport

    suspend fun neverDiscard(
        accountId: AccountId,
        sender: Address,
        ids: List<Long>,
    ): RestoreReport
}

/**
 * Sends unsubscribe requests. Called only from the confirmation dialog's handlers: the mailto
 * path needs a [SendApproval], which only that handler creates ([UserInitiatedSend]).
 */
interface Unsubscriber {
    suspend fun oneClick(url: String): UnsubscribeOutcome

    @UserInitiatedSend
    suspend fun mail(
        accountId: AccountId,
        message: OutgoingMessage,
        approval: SendApproval,
    ): UnsubscribeOutcome
}
