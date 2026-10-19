package tech.elephantclock.privatemail.core.mail.ops

import tech.elephantclock.privatemail.core.mail.imap.SelectedFolder
import tech.elephantclock.privatemail.core.mail.imap.UidCommands

/** One message to move: its UID in the source folder, plus what helps find it afterwards. */
data class MoveItem(
    val uid: Long,
    val messageIdHeader: String? = null,
    /** The app's local message id, passed through to reports and the undo log. */
    val localId: Long? = null,
) {
    init {
        require(uid > 0) { "uid must be positive" }
    }
}

/** A message that left the source folder. [newUid] is its UID in the destination, when known. */
data class MovedMessage(
    val item: MoveItem,
    val newUid: Long?,
)

/** Why a transfer stopped. Batches before the failure are done (and logged); later ones weren't tried. */
sealed interface TransferFailure {
    /** The source folder's UIDVALIDITY changed: the UIDs we hold may name other messages, so nothing was done. */
    data object StaleUidValidity : TransferFailure

    data class Server(
        val detail: String,
    ) : TransferFailure
}

internal data class TransferReport(
    val destinationUidValidity: Long,
    val moved: List<MovedMessage>,
    /** Requested UIDs that weren't in the source folder (already moved or deleted elsewhere). */
    val missing: List<Long>,
    /** No MOVE and no UIDPLUS: the originals are marked `\Deleted` but still in the source. */
    val markedOnly: Boolean,
    val failure: TransferFailure?,
)

/**
 * Moves exact UIDs between folders in batches of [UidCommands.BATCH_SIZE]:
 * Gmail labels (INBOX only, when allowed), else `UID MOVE`, else `UID COPY` + `\Deleted` +
 * `UID EXPUNGE` of the same UIDs (UIDPLUS), else COPY + `\Deleted` and no expunge at all.
 */
internal class Transfer(
    private val mailbox: Mailbox,
) {
    private val caps get() = mailbox.capabilities
    private var expungeSkipped = false

    suspend fun run(
        source: String,
        expectedUidValidity: Long,
        items: List<MoveItem>,
        destination: String,
        allowGmailLabels: Boolean,
        /**
         * Gmail archive: only drop `\Inbox` ([destination] is All Mail, where the message already
         * is). Ignored unless the server is Gmail and [source] is the Inbox.
         */
        gmailArchive: Boolean = false,
        onBatch: suspend (batch: List<MovedMessage>, destinationUidValidity: Long) -> Unit,
    ): TransferReport {
        require(source != destination) { "source and destination are the same folder" }
        expungeSkipped = false
        val destStatus = mailbox.status(destination)
        val byUid = items.associateBy { it.uid }
        val fromInbox = caps.gmail && source.equals(FolderProvisioner.INBOX, ignoreCase = true)
        val dropInboxOnly = gmailArchive && fromInbox
        val gmailLabels = (allowGmailLabels || dropInboxOnly) && fromInbox
        val moved = mutableListOf<MovedMessage>()
        var missing = emptyList<Long>()
        var failure: TransferFailure? = null
        mailbox.open(source, readWrite = true).use { src ->
            if (src.uidValidity != expectedUidValidity) {
                return TransferReport(
                    destStatus.uidValidity,
                    emptyList(),
                    emptyList(),
                    false,
                    TransferFailure.StaleUidValidity,
                )
            }
            val present = src.existing(byUid.keys)
            missing = byUid.keys.filter { it !in present }.sorted()
            for (batch in UidCommands.batches(present)) {
                val done =
                    try {
                        val copyUid = moveBatch(src, batch, destination, gmailLabels, dropInboxOnly)
                        val known =
                            copyUid?.takeIf { it.uidValidity == destStatus.uidValidity }?.newUids.orEmpty()
                        val unresolved = batch.filter { known[it] == null }.map { byUid.getValue(it) }
                        // The batch has moved; a failed lookup only leaves its new UIDs unknown.
                        val found =
                            runCatching {
                                resolve(
                                    destination,
                                    destStatus,
                                    unresolved,
                                    // Already in All Mail: its UID there predates this run.
                                    sinceUid = if (dropInboxOnly) 1 else destStatus.uidNext,
                                )
                            }.getOrDefault(emptyMap())
                        batch.map { uid -> MovedMessage(byUid.getValue(uid), known[uid] ?: found[uid]) }
                    } catch (e: Exception) {
                        failure = TransferFailure.Server(e.message ?: e.javaClass.simpleName)
                        null
                    } ?: break
                onBatch(done, destStatus.uidValidity)
                moved += done
            }
        }
        val markedOnly = moved.isNotEmpty() && (expungeSkipped || (!gmailLabels && !caps.move && !caps.uidPlus))
        return TransferReport(destStatus.uidValidity, moved, missing, markedOnly, failure)
    }

    private fun moveBatch(
        src: SelectedFolder,
        batch: List<Long>,
        destination: String,
        gmailLabels: Boolean,
        dropInboxOnly: Boolean,
    ) = when {
        dropInboxOnly -> {
            src.storeGmailLabels(batch, listOf(GMAIL_INBOX), add = false)
            null
        }

        gmailLabels -> {
            // Label first, then drop \Inbox: if the second step fails the mail is in both, never in neither.
            src.storeGmailLabels(batch, listOf(destination), add = true)
            src.storeGmailLabels(batch, listOf(GMAIL_INBOX), add = false)
            null
        }

        caps.move -> {
            src.uidMove(batch, destination)
        }

        else -> {
            val copyUid = src.uidCopy(batch, destination)
            src.storeFlags(batch, DELETED, add = true)
            // Copied and marked: the move has happened. A failed expunge leaves the originals marked only.
            if (caps.uidPlus) runCatching { src.uidExpunge(batch) }.onFailure { expungeSkipped = true }
            copyUid
        }
    }

    /**
     * Without COPYUID: each copy is the one message with its Message-ID among the destination's
     * UIDs from [sinceUid] (normally UIDNEXT before the move). Ambiguous (shared or missing Message-ID) stays null.
     */
    private fun resolve(
        destination: String,
        before: FolderStatus,
        items: List<MoveItem>,
        sinceUid: Long,
    ): Map<Long, Long> {
        val counts = items.groupingBy { it.messageIdHeader }.eachCount()
        val searchable = items.filter { it.messageIdHeader?.let(UidCommands::messageId) == it.messageIdHeader }
        val unique = searchable.filter { it.messageIdHeader != null && counts[it.messageIdHeader] == 1 }
        if (unique.isEmpty() || sinceUid <= 0) return emptyMap()
        return mailbox.open(destination, readWrite = false).use { dst ->
            if (dst.uidValidity != before.uidValidity) return@use emptyMap()
            unique
                .mapNotNull { item ->
                    dst.searchMessageId(item.messageIdHeader!!, sinceUid).singleOrNull()?.let { item.uid to it }
                }.toMap()
        }
    }

    companion object {
        const val DELETED = "\\Deleted"
        const val GMAIL_INBOX = "\\Inbox"
    }
}
