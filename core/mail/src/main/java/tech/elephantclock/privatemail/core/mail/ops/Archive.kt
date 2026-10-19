package tech.elephantclock.privatemail.core.mail.ops

import tech.elephantclock.privatemail.core.model.AccountId
import java.util.UUID

/** Messages to take out of the Inbox to the account's own Archive (T49's notification action). */
data class ArchiveRequest(
    val accountId: AccountId,
    /** INBOX's UIDVALIDITY the UIDs belong to; a different one on the server means nothing is moved. */
    val inboxUidValidity: Long,
    val items: List<MoveItem>,
    val batchId: String = UUID.randomUUID().toString(),
) {
    init {
        require(items.map { it.uid }.toSet().size == items.size) { "duplicate UIDs" }
    }
}

sealed interface ArchiveResult {
    /**
     * The move ran (see [MoveReport.failure] and [MoveReport.missing]). On Gmail
     * [MoveReport.destination] is All Mail: the messages only lost their `\Inbox` label.
     */
    data class Archived(
        val report: MoveReport,
    ) : ArchiveResult

    /**
     * The server has no Archive folder (Gmail: All Mail is not shown over IMAP). Nothing was
     * moved and nothing created: guessing a folder name could hide mail where the user never looks.
     */
    data object NoArchiveFolder : ArchiveResult
}
