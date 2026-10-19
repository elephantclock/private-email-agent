package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.FetchProfile
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import org.eclipse.angus.mail.imap.IMAPMessage
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import java.util.Date
import java.util.concurrent.TimeUnit

/** Where a Sent backfill stopped: everything in the window with a UID at or above [belowUid] is done. */
data class SentHistoryCursor(
    val uidValidity: Long,
    val belowUid: Long,
)

/** One page of Sent history, newest first. */
data class SentHistoryPage(
    val uidValidity: Long,
    val headers: List<MessageHeader>,
    /** Messages in the window, in all pages (for progress). */
    val total: Int,
    /** Messages in the window still below this page, 0 when the backfill is done. */
    val remaining: Int,
    /** Resume here; null when the page was empty. */
    val next: SentHistoryCursor?,
)

/**
 * Reads the Sent folder's history for the relationship index (T43), **headers only**: EXAMINE
 * (read-only), `SEARCH SINCE`, then `UID`, `ENVELOPE`, `INTERNALDATE`, `RFC822.SIZE` and
 * `FLAGS` in pages of [pageSize], newest first. No BODY or BODYSTRUCTURE is ever fetched, so
 * the cost is a few hundred bytes per message and nothing on the server changes.
 *
 * Resumable: each page returns a [SentHistoryCursor]; pass it back to continue below it. A
 * cursor from another UIDVALIDITY is ignored and the history starts again from the top.
 */
class SentHistoryFetcher(
    private val clock: () -> Long = System::currentTimeMillis,
    private val days: Int = DEFAULT_DAYS,
    private val pageSize: Int = 200,
) {
    fun page(
        connection: ImapConnection,
        accountId: AccountId,
        folderPath: String,
        cursor: SentHistoryCursor?,
    ): SentHistoryPage {
        val folder = connection.openReadOnly(folderPath)
        try {
            val uidValidity = folder.uidValidity
            val below = cursor?.takeIf { it.uidValidity == uidValidity }?.belowUid ?: Long.MAX_VALUE
            val since = Date(clock() - TimeUnit.DAYS.toMillis(days.toLong()))
            val matches = folder.search(ReceivedDateTerm(ComparisonTerm.GE, since))
            folder.fetch(matches, UID_ONLY)
            val inWindow = matches.map { it to folder.getUID(it) }.sortedByDescending { it.second }
            val todo = inWindow.filter { it.second in 1 until below }
            val page = todo.take(pageSize)
            if (page.isNotEmpty()) folder.fetch(page.map { it.first }.toTypedArray(), ENVELOPE_ONLY)
            val headers =
                page.map { (m, uid) ->
                    header(m as IMAPMessage, MessageRef(accountId, folderPath, uidValidity, uid))
                }
            return SentHistoryPage(
                uidValidity = uidValidity,
                headers = headers,
                total = inWindow.size,
                remaining = todo.size - page.size,
                next = page.lastOrNull()?.let { SentHistoryCursor(uidValidity, it.second) },
            )
        } finally {
            folder.close(false)
        }
    }

    /** Envelope fields only; In-Reply-To comes from the envelope, References would need a header fetch. */
    private fun header(
        m: IMAPMessage,
        ref: MessageRef,
    ) = MessageHeader(
        ref = ref,
        messageId = m.messageID,
        from = (m.from?.firstOrNull() as? InternetAddress)?.toAddress(),
        subject = m.subject.orEmpty(),
        sentAtMillis = m.sentDate?.time,
        receivedAtMillis = m.receivedDate?.time ?: clock(),
        to = m.getRecipients(Message.RecipientType.TO).toAddresses(),
        cc = m.getRecipients(Message.RecipientType.CC).toAddresses(),
        replyTo = m.replyTo.toAddresses(),
        inReplyTo = m.inReplyTo?.trim()?.ifEmpty { null },
        flags = m.flags.toModel(),
        sizeBytes = m.size.toLong().coerceAtLeast(0),
    )

    private fun InternetAddress.toAddress(): Address? = address?.takeIf { '@' in it }?.let { Address.of(it, personal) }

    private fun Array<jakarta.mail.Address>?.toAddresses(): List<Address> =
        this.orEmpty().filterIsInstance<InternetAddress>().mapNotNull { it.toAddress() }

    companion object {
        /** About 12 months. */
        const val DEFAULT_DAYS = 365

        private val UID_ONLY = FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) }

        private val ENVELOPE_ONLY =
            FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
            }
    }
}
