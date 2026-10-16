package tech.elephantclock.privatemail.core.mail.sync

import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.MessageBody
import tech.elephantclock.privatemail.core.model.MessageFlag
import tech.elephantclock.privatemail.core.model.MessageHeader
import tech.elephantclock.privatemail.core.model.MessageRef
import java.util.Date
import java.util.concurrent.TimeUnit

data class FetchedMessage(
    val header: MessageHeader,
    val body: MessageBody,
)

data class SyncBatch(
    val state: SyncState,
    val messages: List<FetchedMessage>,
    /** True when UIDVALIDITY changed: everything stored for this folder is stale and must be replaced. */
    val reset: Boolean,
)

/**
 * Fetches new messages in one folder, read-only. First run (or after a UIDVALIDITY change):
 * everything received in the last [firstRunDays] days. Later runs: only UIDs above the last
 * one seen. Fetched: envelope, flags, size, the headers triage reads, BODYSTRUCTURE and the
 * first 4 KB of the text part.
 */
class HeaderFetcher(
    private val clock: () -> Long = System::currentTimeMillis,
    private val firstRunDays: Int = 30,
    private val batchSize: Int = 100,
) {
    fun sync(
        connection: ImapConnection,
        accountId: AccountId,
        folderPath: String,
        previous: SyncState?,
    ): SyncBatch {
        val folder = connection.openReadOnly(folderPath)
        try {
            val uidValidity = folder.uidValidity
            // Read before searching: anything arriving later gets a UID >= this and is picked up next time.
            val uidNext = folder.uidNext
            val reset = previous != null && previous.uidValidity != uidValidity
            // Null on first run or after a UIDVALIDITY change: start over with the time window.
            val resume = previous?.takeUnless { reset }
            val messages: List<Message> =
                if (resume == null) {
                    val since = Date(clock() - TimeUnit.DAYS.toMillis(firstRunDays.toLong()))
                    folder.search(ReceivedDateTerm(ComparisonTerm.GE, since)).toList()
                } else {
                    // "n:*" always returns the last message even when its UID is below n; filtered below.
                    folder.getMessagesByUID(resume.highestUid + 1, UIDFolder.MAXUID).filterNotNull()
                }
            val fetched =
                messages
                    .chunked(batchSize)
                    .flatMap { chunk ->
                        fetchChunk(folder, chunk.toTypedArray(), accountId, folderPath, uidValidity)
                    }.filter { resume == null || it.header.ref.uid > resume.highestUid }
            // On a fresh sync every UID below UIDNEXT was considered, including older mail outside
            // the window that was appended later (higher UID, old date); don't fetch it next time.
            // A server that omits UIDNEXT gives -1: then only what was fetched counts.
            val considered = resume?.highestUid ?: (if (uidNext > 0) uidNext - 1 else 0L)
            val highest = (fetched.map { it.header.ref.uid } + considered).max()
            return SyncBatch(SyncState(accountId, folderPath, uidValidity, highest, clock()), fetched, reset)
        } finally {
            folder.close(false)
        }
    }

    private fun fetchChunk(
        folder: IMAPFolder,
        chunk: Array<Message>,
        accountId: AccountId,
        folderPath: String,
        uidValidity: Long,
    ): List<FetchedMessage> {
        folder.fetch(chunk, PROFILE)
        val snippets = BodySnippetFetcher.fetch(folder, chunk.map { it.messageNumber })
        return chunk.map { it as IMAPMessage }.map { m ->
            val ref = MessageRef(accountId, folderPath, uidValidity, folder.getUID(m))
            val snippet = snippets[m.messageNumber]
            val header =
                MessageHeader(
                    ref = ref,
                    messageId = m.messageID,
                    from = (m.from?.firstOrNull() as? InternetAddress)?.toAddress(),
                    subject = m.subject.orEmpty(),
                    sentAtMillis = m.sentDate?.time,
                    receivedAtMillis = m.receivedDate?.time ?: clock(),
                    to = m.getRecipients(Message.RecipientType.TO).toAddresses(),
                    cc = m.getRecipients(Message.RecipientType.CC).toAddresses(),
                    replyTo = m.replyTo.toAddresses(),
                    inReplyTo = (m.getHeader("In-Reply-To")?.firstOrNull() ?: m.inReplyTo)?.trim()?.ifEmpty { null },
                    references =
                        m
                            .getHeader(
                                "References",
                            )?.flatMap { it.split(Regex("\\s+")) }
                            ?.filter { it.isNotBlank() }
                            .orEmpty(),
                    flags = m.flags.toModel(),
                    sizeBytes = m.size.toLong().coerceAtLeast(0),
                    headers =
                        TRIAGE_HEADERS
                            .mapNotNull { name -> m.getHeader(name)?.let { name.lowercase() to it.toList() } }
                            .toMap(),
                )
            val body =
                MessageBody(
                    ref = ref,
                    textPreview = snippet?.text.orEmpty(),
                    isTruncated = snippet?.truncated ?: false,
                    sourceMimeType = snippet?.sourceMimeType ?: "text/plain",
                    attachments = snippet?.structure?.let(MimeTree::attachments).orEmpty(),
                )
            FetchedMessage(header, body)
        }
    }

    private fun InternetAddress.toAddress(): Address? = address?.takeIf { '@' in it }?.let { Address.of(it, personal) }

    private fun Array<jakarta.mail.Address>?.toAddresses(): List<Address> =
        this.orEmpty().filterIsInstance<InternetAddress>().mapNotNull { it.toAddress() }

    private fun Flags.toModel(): Set<MessageFlag> {
        val flags = this
        return FLAG_MAP.filterKeys { flags.contains(it) }.values.toSet()
    }

    companion object {
        /** Headers the rules engine reads (T20), beyond the envelope. */
        val TRIAGE_HEADERS =
            listOf(
                "List-Unsubscribe",
                "List-Unsubscribe-Post",
                "List-Id",
                "Precedence",
                "Auto-Submitted",
                "Authentication-Results",
                "References",
                "In-Reply-To",
                "Message-ID",
                "Return-Path",
                "X-Mailgun-Sid",
                "X-SG-EID",
                "X-MC-User",
                "X-Campaign",
                "Feedback-ID",
            )

        private val FLAG_MAP =
            mapOf(
                Flags.Flag.SEEN to MessageFlag.SEEN,
                Flags.Flag.ANSWERED to MessageFlag.ANSWERED,
                Flags.Flag.FLAGGED to MessageFlag.FLAGGED,
                Flags.Flag.DRAFT to MessageFlag.DRAFT,
                Flags.Flag.DELETED to MessageFlag.DELETED,
            )

        private val PROFILE =
            FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(FetchProfile.Item.SIZE)
                add(UIDFolder.FetchProfileItem.UID)
                TRIAGE_HEADERS.forEach { add(it) }
            }
    }
}
