package tech.elephantclock.privatemail.core.data.triage

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.RoomUndoLog
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.repo.ImapConnector
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.ops.DiscardedMessage
import tech.elephantclock.privatemail.core.mail.ops.MoveEngine
import tech.elephantclock.privatemail.core.mail.ops.MoveItem
import tech.elephantclock.privatemail.core.mail.ops.MoveReport
import tech.elephantclock.privatemail.core.mail.ops.MoveRequest
import tech.elephantclock.privatemail.core.mail.ops.UndoReport
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderTarget
import tech.elephantclock.privatemail.core.model.Importance
import tech.elephantclock.privatemail.core.model.Verdict
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.core.model.VerdictSource

/** One message triage wants moved: where it is now and the target of its verdict. */
data class PlannedMove(
    val message: MessageEntity,
    val target: FolderTarget,
    val action: VerdictAction,
)

/**
 * What a run of moves did. Messages that [failed] keep their pending flag and are retried.
 * In "straight to Trash" mode, [trashed] of the discarded messages went on to the server's
 * Trash; [heldNoTrash] stayed in Discarded because the server has no Trash folder.
 */
data class MoveOutcome(
    val moved: Int = 0,
    val missing: Int = 0,
    val failed: Int = 0,
    val batchIds: List<String> = emptyList(),
    val errors: List<Throwable> = emptyList(),
    val trashed: Int = 0,
    val heldNoTrash: Int = 0,
) {
    operator fun plus(o: MoveOutcome) =
        MoveOutcome(
            moved + o.moved,
            missing + o.missing,
            failed + o.failed,
            batchIds + o.batchIds,
            errors + o.errors,
            trashed + o.trashed,
            heldNoTrash + o.heldNoTrash,
        )
}

/**
 * Carries out triage's moves with `core:mail`'s [MoveEngine] on its own connection, and keeps
 * the database in step: under [MailRepository.withFolderLock] for the source folder (and the
 * destination while the rows are updated), so a sync never runs on a folder mid-move. Undo
 * entries go to [RoomUndoLog]. A lost connection (`MessagingException`) leaves the remaining
 * messages pending; batches already moved are logged, so the retry is safe.
 *
 * Every folder it moves mail into gets its `FolderRole` in the database, so the sync keeps it
 * current and reconciles the moved rows. With [discardMode] "straight to Trash" for the
 * account, discarded mail goes on from Discarded to the server's special-use Trash at once
 * ([StraightToTrash]); Trash is never created, so without one the mail is held in Discarded.
 */
class TriageMover(
    private val db: AppDatabase,
    private val repo: MailRepository,
    private val connector: ImapConnector,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val discardMode: suspend (AccountId) -> DiscardMode = { DiscardMode.HOLD_30_DAYS },
) {
    private val undoLog = RoomUndoLog(db)

    suspend fun apply(
        account: Account,
        moves: List<PlannedMove>,
    ): MoveOutcome {
        if (moves.isEmpty()) return MoveOutcome()
        return withConnection(
            account,
            onFailure = { MoveOutcome(failed = moves.size, errors = listOf(it)) },
        ) { engine ->
            moves
                .groupBy { Triple(it.message.folderPath, it.message.uidValidity, it.target to it.action) }
                .map { (key, group) ->
                    val (from, uidValidity, targetAction) = key
                    try {
                        moveGroup(engine, account.id, from, uidValidity, targetAction.first, targetAction.second, group)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MoveOutcome(failed = group.size, errors = listOf(e))
                    }
                }.fold(MoveOutcome()) { a, b -> a + b }
        }
    }

    /**
     * Moves a triage batch back (7 days); rows follow their messages back to the source folder,
     * and each gets the user's KEEP verdict, so it is in Priority again and never re-moved.
     * Null when the account can't be signed in to.
     */
    suspend fun undo(
        account: Account,
        batchId: String,
    ): UndoReport? =
        withConnection(account, onFailure = { null }) { engine ->
            val report = engine.undo(account.id, batchId)
            val now = clock()
            db.withTransaction {
                for (r in report.restored) {
                    val id = r.entry.localId ?: continue
                    val uid = r.uid ?: continue
                    if (db.messages().get(id) == null) continue
                    relocate(account.id, id, r.entry.fromFolder, r.entry.fromUidValidity, uid, movedAt = null)
                    db.verdicts().upsert(UNDONE.toEntity(id, now))
                }
            }
            report
        }

    /**
     * Moves messages discarded 30+ days ago from Discarded to the server's Trash, using the
     * discard times stored with the rows, and drops the rows of what was moved or is gone.
     */
    suspend fun purgeDiscarded(account: Account): Int {
        val due = db.triage().discardedBefore(account.id.value, clock() - MoveEngine.DISCARD_HOLD_MILLIS)
        if (due.isEmpty()) return 0
        return withConnection(account, onFailure = { 0 }) { engine ->
            due.groupBy { it.folderPath to it.uidValidity }.entries.sumOf { (key, rows) ->
                val (folder, uidValidity) = key
                repo.withFolderLock(account.id, folder) {
                    val messages =
                        rows.map {
                            DiscardedMessage(
                                MoveItem(it.uid, it.messageIdHeader, it.id),
                                it.movedAtMillis!!,
                            )
                        }
                    val report = engine.purgeDiscarded(uidValidity, messages)
                    val gone =
                        report.moved.mapNotNull { it.item.localId } +
                            rows.filter { it.uid in report.missing }.map { it.id }
                    db.withTransaction { gone.forEach { db.triage().deleteMessage(it) } }
                    gone.size
                }
            }
        }
    }

    private suspend fun moveGroup(
        engine: MoveEngine,
        accountId: AccountId,
        from: String,
        uidValidity: Long,
        target: FolderTarget,
        action: VerdictAction,
        group: List<PlannedMove>,
    ): MoveOutcome =
        repo.withFolderLock(accountId, from) {
            val items = group.map { MoveItem(it.message.uid, it.message.messageIdHeader, it.message.id) }
            val report = engine.move(MoveRequest(accountId, from, uidValidity, items, target, action))
            val destination = report.destination
            if (destination == null || destination == from) {
                record(accountId, group, report)
            } else {
                repo.withFolderLock(accountId, destination) {
                    val outcome = record(accountId, group, report)
                    FolderRoles.assign(db, accountId, destination, target)
                    if (action == VerdictAction.DISCARD && discardMode(accountId) == DiscardMode.STRAIGHT_TO_TRASH) {
                        outcome + StraightToTrash.run(db, engine, report)
                    } else {
                        outcome
                    }
                }
            }
        }

    /** Updates the moved rows, clears the pending flag of moved and missing ones; the rest stay pending. */
    private suspend fun record(
        accountId: AccountId,
        group: List<PlannedMove>,
        report: MoveReport,
    ): MoveOutcome {
        val now = clock()
        val byUid = group.associateBy { it.message.uid }
        val destination = report.destination
        val missing = report.missing.mapNotNull { byUid[it]?.message?.id }
        db.withTransaction {
            val dao = db.triage()
            for (m in report.moved) {
                val id = m.item.localId ?: continue
                val newUid = m.newUid
                val uidValidity = report.destinationUidValidity
                if (destination == null || newUid == null || uidValidity == null) {
                    // Its new UID is unknown: the destination's sync stores it again (without this verdict).
                    dao.deleteMessage(id)
                } else {
                    relocate(accountId, id, destination, uidValidity, newUid, movedAt = now)
                    dao.setPendingMove(id, false)
                }
            }
            missing.forEach { dao.setPendingMove(it, false) }
        }
        val done = report.moved.size + missing.size
        return MoveOutcome(
            moved = report.moved.size,
            missing = missing.size,
            failed = group.size - done,
            batchIds = if (report.moved.isEmpty()) emptyList() else listOf(report.batchId),
            errors = listOfNotNull(report.failure?.let { IllegalStateException("move stopped: $it") }),
        )
    }

    /** Points row [id] at its new server location; a copy the destination's sync stored first is dropped. */
    private suspend fun relocate(
        accountId: AccountId,
        id: Long,
        folder: String,
        uidValidity: Long,
        uid: Long,
        movedAt: Long?,
    ) {
        db.messages().byServerRef(accountId.value, folder, uidValidity, uid)?.takeIf { it.id != id }?.let {
            db.triage().deleteMessage(it.id)
        }
        db.triage().recordMove(id, folder, uidValidity, uid, movedAt)
    }

    private companion object {
        val UNDONE = Verdict.keep(Importance.NORMAL, VerdictSource.USER, "You undid the move")
    }

    private suspend fun <T> withConnection(
        account: Account,
        onFailure: (Throwable) -> T,
        block: suspend (MoveEngine) -> T,
    ): T {
        // Like sync and IDLE: an account waiting for the user (wrong password...) is not signed in
        // to again, since retrying can get it locked. Its moves stay pending.
        db.accounts().get(account.id.value)?.authProblem?.let {
            return onFailure(IllegalStateException("waiting for the user: $it"))
        }
        val connection: ImapConnection =
            when (val result = withContext(io) { connector.connect(account) }) {
                is MailResult.Failed -> {
                    if (result.error.needsUserAction) {
                        db.accounts().setAuthProblem(account.id.value, result.error.javaClass.simpleName)
                    }
                    return onFailure(IllegalStateException("sign-in failed: ${result.error}"))
                }

                is MailResult.Ok -> {
                    result.value
                }
            }
        try {
            return block(MoveEngine(connection, undoLog, clock, io = io))
        } finally {
            withContext(NonCancellable + io) { runCatching { connection.close() } }
        }
    }
}
