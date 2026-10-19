package tech.elephantclock.privatemail.core.data.triage

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.MessageEntity
import tech.elephantclock.privatemail.core.data.db.RoomUndoLog
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.repo.ImapConnector
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction
import tech.elephantclock.privatemail.core.mail.ops.ArchiveRequest
import tech.elephantclock.privatemail.core.mail.ops.ArchiveResult
import tech.elephantclock.privatemail.core.mail.ops.MoveEngine
import tech.elephantclock.privatemail.core.mail.ops.MoveItem
import tech.elephantclock.privatemail.core.mail.ops.MoveReport
import tech.elephantclock.privatemail.core.model.AccountId

sealed interface ArchiveOutcome {
    /** Out of the Inbox; [batchId] undoes it (`TriageMover.undo`, 7 days). */
    data class Archived(
        val batchId: String,
    ) : ArchiveOutcome

    /** The server has no Archive folder (nothing was moved or created). */
    data object NoArchiveFolder : ArchiveOutcome

    /** The account or message is gone, belongs to another account, or has left the Inbox already. */
    data object NotInInbox : ArchiveOutcome

    /** The account waits for the user (`authProblem`); no sign-in was tried. */
    data object NeedsSignIn : ArchiveOutcome

    data class Failed(
        val reason: String,
    ) : ArchiveOutcome
}

/**
 * Archives one Inbox message (the notification's Archive action, T49) with [MoveEngine.archive]
 * on its own connection, keeping the row in step like [TriageMover]: under the Inbox's folder
 * lock (and the destination's while the row moves), re-reading the row under the lock, an undo
 * entry in [RoomUndoLog]. The row keeps its id and verdict; out of INBOX it leaves Priority.
 */
class InboxArchiver(
    private val db: AppDatabase,
    private val repo: MailRepository,
    private val connector: ImapConnector,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val undoLog = RoomUndoLog(db)

    suspend fun archive(
        accountId: AccountId,
        localId: Long,
    ): ArchiveOutcome {
        val account = db.accounts().get(accountId.value) ?: return ArchiveOutcome.NotInInbox
        // Retrying a wrong password can get the account locked: wait for the user, like sync does.
        if (account.authProblem != null) return ArchiveOutcome.NeedsSignIn
        inInbox(accountId, localId) ?: return ArchiveOutcome.NotInInbox
        val connection =
            when (val result = withContext(io) { connector.connect(account.toModel()) }) {
                is MailResult.Failed -> {
                    if (result.error.needsUserAction) {
                        db.accounts().setAuthProblem(accountId.value, result.error.javaClass.simpleName)
                    }
                    return ArchiveOutcome.Failed("sign-in failed: ${result.error}")
                }

                is MailResult.Ok -> {
                    result.value
                }
            }
        try {
            val engine = MoveEngine(connection, undoLog, clock, io = io)
            return repo.withFolderLock(accountId, MailRepository.INBOX) {
                val row = inInbox(accountId, localId) ?: return@withFolderLock ArchiveOutcome.NotInInbox
                val request =
                    ArchiveRequest(accountId, row.uidValidity, listOf(MoveItem(row.uid, row.messageIdHeader, row.id)))
                when (val result = engine.archive(request)) {
                    ArchiveResult.NoArchiveFolder -> ArchiveOutcome.NoArchiveFolder
                    is ArchiveResult.Archived -> record(accountId, row, result.report)
                }
            }
        } finally {
            withContext(NonCancellable + io) { runCatching { connection.close() } }
        }
    }

    private suspend fun inInbox(
        accountId: AccountId,
        localId: Long,
    ): MessageEntity? =
        db
            .messages()
            .get(localId)
            ?.takeIf { it.accountId == accountId.value && it.folderPath == MailRepository.INBOX }

    private suspend fun record(
        accountId: AccountId,
        row: MessageEntity,
        report: MoveReport,
    ): ArchiveOutcome {
        val moved = report.moved.singleOrNull()
        val destination = report.destination
        if (moved == null || destination == null) {
            return report.failure?.let { ArchiveOutcome.Failed("move stopped: $it") } ?: ArchiveOutcome.NotInInbox
        }
        repo.withFolderLock(accountId, destination) {
            db.withTransaction {
                val newUid = moved.newUid
                val uidValidity = report.destinationUidValidity
                if (newUid == null || uidValidity == null) {
                    // Its new UID is unknown: the row can't point at it any more.
                    db.triage().deleteMessage(row.id)
                } else {
                    db
                        .messages()
                        .byServerRef(accountId.value, destination, uidValidity, newUid)
                        ?.takeIf {
                            it.id !=
                                row.id
                        }?.let {
                            db.triage().deleteMessage(it.id)
                        }
                    db.messages().moveTo(row.id, destination, uidValidity, newUid)
                    db.triage().setPendingMove(row.id, false)
                }
            }
        }
        return ArchiveOutcome.Archived(report.batchId)
    }
}
