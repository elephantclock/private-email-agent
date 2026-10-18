package tech.elephantclock.privatemail.feature.discarded

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.DiscardedRow
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.triage.DiscardedActions
import tech.elephantclock.privatemail.core.data.triage.RestoreReport
import tech.elephantclock.privatemail.core.data.triage.TriageSettings
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.smtp.OutgoingMessage
import tech.elephantclock.privatemail.core.mail.smtp.SendApproval
import tech.elephantclock.privatemail.core.mail.smtp.SendError
import tech.elephantclock.privatemail.core.mail.smtp.SendResult
import tech.elephantclock.privatemail.core.mail.smtp.SmtpSender
import tech.elephantclock.privatemail.core.mail.smtp.UserInitiatedSend
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address

private val json = Json { ignoreUnknownKeys = true }
private val headerMap = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

/** The digest's rows from Room, with each message's unsubscribe method worked out once. */
class RoomDiscardedSource(
    private val db: AppDatabase,
    private val settings: TriageSettings = TriageSettings.DEFAULTS,
) : DiscardedSource {
    override fun items(): Flow<List<DiscardedItem>> =
        db.discarded().discarded().map { rows ->
            val trusted =
                rows.map { it.accountId }.distinct().associateWith { id ->
                    settings.forAccount(AccountId(id)).trustedAuthservIds
                }
            rows.mapNotNull { it.toItem(trusted[it.accountId].orEmpty()) }
        }

    private fun DiscardedRow.toItem(trustedAuthservIds: Set<String>): DiscardedItem? {
        val account = runCatching { Address.of(accountEmail, accountName) }.getOrNull() ?: return null
        val headers = runCatching { json.decodeFromString(headerMap, headersJson) }.getOrDefault(emptyMap())
        return DiscardedItem(
            id = id,
            accountId = AccountId(accountId),
            accountAddress = account,
            from = fromEmail?.let { runCatching { Address.of(it, fromName) }.getOrNull() },
            subject = subject,
            receivedAtMillis = receivedAtMillis,
            action = action,
            reason = reason,
            decidedAtMillis = decidedAtMillis,
            unsubscribe = runCatching { ListUnsubscribe.methodFor(headers, trustedAuthservIds) }.getOrNull(),
        )
    }
}

class DataDiscardedCommands(
    private val actions: DiscardedActions,
) : DiscardedCommands {
    override suspend fun restore(ids: List<Long>): RestoreReport = actions.restore(ids)

    override suspend fun neverDiscard(
        accountId: AccountId,
        sender: Address,
        ids: List<Long>,
    ): RestoreReport = actions.neverDiscard(accountId, sender, ids).second
}

/**
 * One-click over [OneClickUnsubscriber]; mailto through [SmtpSender] with the account's own
 * SMTP settings. No Sent copy (no IMAP connection is opened for an unsubscribe email). An
 * account waiting for the user (wrong password...) is not signed in to.
 */
class AppUnsubscriber(
    private val db: AppDatabase,
    private val credentials: (AccountId, String) -> MailCredentials?,
    private val smtp: SmtpSender,
    private val oneClick: OneClickUnsubscriber = OneClickUnsubscriber(),
) : Unsubscriber {
    override suspend fun oneClick(url: String): UnsubscribeOutcome = oneClick.post(url)

    @UserInitiatedSend
    override suspend fun mail(
        accountId: AccountId,
        message: OutgoingMessage,
        approval: SendApproval,
    ): UnsubscribeOutcome {
        val entity = db.accounts().get(accountId.value) ?: return UnsubscribeOutcome.FAILED
        if (entity.authProblem != null) return UnsubscribeOutcome.FAILED
        val account = entity.toModel()
        val creds = credentials(account.id, account.username) ?: return UnsubscribeOutcome.FAILED
        return when (val result = smtp.send(message, approval, account.smtp, creds, imap = null, account.providerId)) {
            is SendResult.Sent -> {
                UnsubscribeOutcome.DONE
            }

            is SendResult.NotSent -> {
                when (result.error) {
                    // A drop after DATA can still deliver (T46's wording): "may not have been sent".
                    is SendError.Connection -> UnsubscribeOutcome.UNKNOWN

                    is SendError.Rejected, is SendError.RecipientsRejected -> UnsubscribeOutcome.REFUSED

                    else -> UnsubscribeOutcome.FAILED
                }
            }
        }
    }
}
