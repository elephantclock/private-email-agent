package tech.elephantclock.privatemail.core.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.smtp.DraftStore
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.SendError
import tech.elephantclock.privatemail.core.mail.smtp.SendResult
import tech.elephantclock.privatemail.core.mail.smtp.SmtpSender
import tech.elephantclock.privatemail.core.mail.smtp.StoredMessage
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.AccountId

sealed interface DraftOutcome {
    data class Saved(
        val stored: StoredMessage,
    ) : DraftOutcome

    /** The account waits for its password; nothing was contacted. */
    data object NeedsSignIn : DraftOutcome

    /** Sign-in or network problem, or the server refused the APPEND ([error] null: unexpected). */
    data class Failed(
        val error: AuthError?,
    ) : DraftOutcome
}

sealed interface SendOutcome {
    /** What [SmtpSender] reported. A `NotSent(Connection)` may still have been delivered. */
    data class Done(
        val result: SendResult,
        /** After a send: whether the saved draft was removed from Drafts. */
        val draftRemoved: Boolean = false,
    ) : SendOutcome

    /** The account waits for its password; nothing was contacted and the approval is unused. */
    data object NeedsSignIn : SendOutcome
}

/**
 * Drafts and sending for the reply editor (T46). Sending is `@UserInitiatedSend` all the way
 * down: this class never opts in, so only the UI's confirmed Send handler can reach [send].
 */
class OutgoingMailRepository(
    private val db: AppDatabase,
    private val connector: ImapConnector,
    private val passwords: (AccountId) -> Password?,
    private val sender: SmtpSender,
    private val drafts: DraftStore = DraftStore(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Saves [message] to Drafts, replacing [replacing] (the previous save) if given. */
    suspend fun saveDraft(
        accountId: AccountId,
        message: OutgoingMessage,
        replacing: StoredMessage?,
    ): DraftOutcome =
        withContext(io) {
            val account = usableAccount(accountId) ?: return@withContext DraftOutcome.NeedsSignIn
            val connection =
                when (val r = connect(account)) {
                    is MailResult.Failed -> return@withContext DraftOutcome.Failed(r.error)
                    is MailResult.Ok -> r.value
                }
            try {
                DraftOutcome.Saved(drafts.save(connection, message, replacing).stored)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DraftOutcome.Failed(AuthError.Unknown(e.message ?: e.javaClass.simpleName))
            } finally {
                closeQuietly(connection)
            }
        }

    /**
     * Sends [message] with the user's [approval], files the Sent copy (unless the provider does)
     * and then removes [draft]. Without an IMAP connection the mail still goes; only the copy is
     * skipped. Never retried here.
     */
    @UserInitiatedSend
    suspend fun send(
        accountId: AccountId,
        message: OutgoingMessage,
        approval: SendApproval,
        draft: StoredMessage?,
    ): SendOutcome =
        withContext(io) {
            val account = usableAccount(accountId) ?: return@withContext SendOutcome.NeedsSignIn
            val password = passwords(accountId) ?: return@withContext SendOutcome.NeedsSignIn
            val imap = (connect(account) as? MailResult.Ok)?.value
            try {
                val model = account.toModel()
                val result =
                    sender.send(
                        message = message,
                        approval = approval,
                        server = model.smtp,
                        credentials = MailCredentials(model.username, password),
                        imap = imap,
                        providerId = model.providerId,
                    )
                if (result is SendResult.NotSent) {
                    val error = (result.error as? SendError.Connection)?.error
                    if (error?.needsUserAction ==
                        true
                    ) {
                        db.accounts().setAuthProblem(account.id, error.javaClass.simpleName)
                    }
                }
                val removed =
                    result is SendResult.Sent && draft != null && imap != null &&
                        runCatching { drafts.delete(imap, draft) }.isSuccess
                SendOutcome.Done(result, draftRemoved = removed)
            } finally {
                imap?.let { closeQuietly(it) }
            }
        }

    /** Null when the account is unknown or waits for its password (never contact it then). */
    private suspend fun usableAccount(id: AccountId): AccountEntity? =
        db.accounts().get(id.value)?.takeIf { it.authProblem == null }

    private suspend fun connect(account: AccountEntity): MailResult<ImapConnection> {
        val result = connector.connect(account.toModel())
        if (result is MailResult.Failed && result.error.needsUserAction) {
            db.accounts().setAuthProblem(account.id, result.error.javaClass.simpleName)
        }
        return result
    }

    private suspend fun closeQuietly(connection: ImapConnection) {
        withContext(NonCancellable) { runCatching { connection.close() } }
    }
}
