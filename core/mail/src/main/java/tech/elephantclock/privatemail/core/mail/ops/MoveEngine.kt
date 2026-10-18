package tech.elephantclock.privatemail.core.mail.ops

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.SpecialUse
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.allows
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MoveRequest(
    val accountId: AccountId,
    val fromFolder: String,
    /** UIDVALIDITY the UIDs belong to; a different one on the server means nothing is moved. */
    val fromUidValidity: Long,
    val items: List<MoveItem>,
    val target: FolderTarget,
    val action: VerdictAction,
    val batchId: String = UUID.randomUUID().toString(),
) {
    init {
        require(action.allows(target.role)) { "$action cannot target ${target.role}" }
        require(items.map { it.uid }.toSet().size == items.size) { "duplicate UIDs" }
    }
}

data class MoveReport(
    val batchId: String,
    val destination: String?,
    val moved: List<MovedMessage>,
    val missing: List<Long>,
    /** The server has neither MOVE nor UIDPLUS: originals are marked `\Deleted`, not expunged. */
    val markedOnly: Boolean,
    val failure: TransferFailure?,
)

/** A message in Discarded and when it was discarded (INTERNALDATE survives a move, so the caller keeps this). */
data class DiscardedMessage(
    val item: MoveItem,
    val discardedAtMillis: Long,
)

data class PurgeReport(
    /** Null when there's no Discarded folder or nothing is due. */
    val trash: String?,
    val moved: List<MovedMessage>,
    /** Due, but the server has no special-use Trash folder; we don't guess one. */
    val noTrash: Boolean,
    val failure: TransferFailure?,
    /** Due UIDs no longer in Discarded (moved or deleted elsewhere): drop their records. */
    val missing: List<Long> = emptyList(),
)

/**
 * Files, discards and undoes on the server, touching only the UIDs it is given. Folders are
 * opened read-write only for the operation and closed without expunging; the only expunge is
 * `UID EXPUNGE` of UIDs this engine just marked (UIDPLUS). Use a connection nothing else is
 * using at the same time. Server refusals mid-run end up in the report's `failure`; a lost
 * connection or a missing folder throws `MessagingException` (batches done before it are in
 * the undo log, so a retry is safe).
 */
class MoveEngine internal constructor(
    private val mailbox: Mailbox,
    private val undoLog: UndoLog,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher,
) {
    constructor(
        connection: ImapConnection,
        undoLog: UndoLog,
        clock: () -> Long = System::currentTimeMillis,
        capabilities: Capabilities = connection.capabilities,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) : this(ImapMailbox(connection, capabilities), undoLog, clock, io)

    /** Fresh per operation, so folders created elsewhere (e.g. Trash) are seen. */
    private val provisioner get() = FolderProvisioner(mailbox)

    /** Moves [MoveRequest.items] to the target folder (created on demand) and logs each batch for undo. */
    suspend fun move(request: MoveRequest): MoveReport =
        withContext(io) {
            val destination = requireNotNull(provisioner.pathFor(request.target))
            if (destination == request.fromFolder) {
                return@withContext MoveReport(request.batchId, destination, emptyList(), emptyList(), false, null)
            }
            val now = clock()
            val gmailLabels = request.action == VerdictAction.FILE || request.action == VerdictAction.DISCARD
            val report =
                Transfer(mailbox).run(
                    source = request.fromFolder,
                    expectedUidValidity = request.fromUidValidity,
                    items = request.items,
                    destination = destination,
                    allowGmailLabels = gmailLabels,
                ) { batch, destinationUidValidity ->
                    undoLog.record(batch.map { it.toUndo(request, destination, destinationUidValidity, now) })
                }
            MoveReport(request.batchId, destination, report.moved, report.missing, report.markedOnly, report.failure)
        }

    private fun MovedMessage.toUndo(
        request: MoveRequest,
        destination: String,
        destinationUidValidity: Long,
        now: Long,
    ) = UndoEntry(
        accountId = request.accountId,
        batchId = request.batchId,
        localId = item.localId,
        action = request.action,
        fromFolder = request.fromFolder,
        fromUidValidity = request.fromUidValidity,
        fromUid = item.uid,
        toFolder = destination,
        toUidValidity = destinationUidValidity,
        toUid = newUid,
        messageIdHeader = item.messageIdHeader,
        createdAtMillis = now,
    )

    /** Moves a batch back where it came from. Entries older than 7 days are not undone. */
    suspend fun undo(
        accountId: AccountId,
        batchId: String,
    ): UndoReport =
        withContext(io) {
            val entries = undoLog.batch(accountId, batchId)
            val cutoff = clock() - UndoLog.RETENTION_MILLIS
            val (fresh, expired) = entries.partition { it.createdAtMillis >= cutoff }
            val results =
                fresh.groupBy { it.fromFolder to it.toFolder }.map { (_, group) ->
                    Undoer(mailbox).undo(group)
                }
            val report = UndoReport.merge(results, expired)
            if (report.failure == null && fresh.isNotEmpty()) undoLog.deleteBatch(accountId, batchId)
            report
        }

    /**
     * Moves messages discarded at least 30 days ago from Discarded to the server's Trash.
     * [messages] come from the caller's records (see [DiscardedMessage]); others are untouched.
     */
    suspend fun purgeDiscarded(
        discardedUidValidity: Long,
        messages: List<DiscardedMessage>,
    ): PurgeReport =
        withContext(io) {
            val cutoff = clock() - DISCARD_HOLD_MILLIS
            val due = messages.filter { it.discardedAtMillis <= cutoff }.map { it.item }
            val folders = provisioner
            val discarded = folders.pathFor(FolderTarget.Discarded, create = false)
            if (due.isEmpty() || discarded == null) return@withContext PurgeReport(null, emptyList(), false, null)
            val trash =
                folders.special(SpecialUse.TRASH) ?: return@withContext PurgeReport(null, emptyList(), true, null)
            val report =
                Transfer(
                    mailbox,
                ).run(discarded, discardedUidValidity, due, trash, allowGmailLabels = false) { _, _ -> }
            PurgeReport(trash, report.moved, false, report.failure, report.missing)
        }

    /** Drops undo entries older than 7 days; returns how many. */
    suspend fun purgeUndoLog(): Int = undoLog.purgeOlderThan(clock() - UndoLog.RETENTION_MILLIS)

    companion object {
        val DISCARD_HOLD_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
