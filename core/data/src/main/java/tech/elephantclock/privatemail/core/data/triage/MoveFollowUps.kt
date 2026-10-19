package tech.elephantclock.privatemail.core.data.triage

import androidx.room.withTransaction
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.mail.ops.DiscardedMessage
import tech.elephantclock.privatemail.core.mail.ops.MoveEngine
import tech.elephantclock.privatemail.core.mail.ops.MoveItem
import tech.elephantclock.privatemail.core.mail.ops.MoveReport
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.FolderTarget

/** Records which triage role a server folder has, once triage has moved mail into it. */
internal object FolderRoles {
    suspend fun assign(
        db: AppDatabase,
        accountId: AccountId,
        path: String,
        target: FolderTarget,
    ) {
        if (target.role == FolderRole.PRIORITY) return
        val name = target.customName ?: path.substringAfterLast('/').substringAfterLast('.')
        db.rules().assignRole(accountId.value, path, name.ifBlank { path }, target.role)
    }
}

/**
 * "Straight to Trash" (screen 02/07): the messages a discard batch just moved into Discarded go
 * on to the server's special-use Trash with `MoveEngine.purgeDiscarded`, which never creates
 * Trash. Without one ([tech.elephantclock.privatemail.core.mail.ops.PurgeReport.noTrash]) they
 * stay in Discarded, held 30 days as usual, and are counted in [MoveOutcome.heldNoTrash]. Rows
 * of messages now in Trash (or gone) are dropped: Trash isn't synced. Runs under the Discarded
 * folder's lock. Undo of that batch then finds nothing in Discarded to bring back.
 */
internal object StraightToTrash {
    suspend fun run(
        db: AppDatabase,
        engine: MoveEngine,
        report: MoveReport,
    ): MoveOutcome {
        val uidValidity = report.destinationUidValidity ?: return MoveOutcome()
        val items =
            report.moved.mapNotNull { m ->
                m.newUid?.let { DiscardedMessage(MoveItem(it, m.item.messageIdHeader, m.item.localId), DUE) }
            }
        if (items.isEmpty()) return MoveOutcome()
        val purge = engine.purgeDiscarded(uidValidity, items)
        if (purge.noTrash) return MoveOutcome(heldNoTrash = items.size)
        val byUid = items.associateBy { it.item.uid }
        val gone =
            purge.moved.mapNotNull { it.item.localId } + purge.missing.mapNotNull { byUid[it]?.item?.localId }
        db.withTransaction { gone.forEach { db.triage().deleteMessage(it) } }
        return MoveOutcome(
            trashed = purge.moved.size,
            errors = listOfNotNull(purge.failure?.let { IllegalStateException("move to Trash stopped: $it") }),
        )
    }

    /** "Discarded at the beginning of time": always due, whatever the clock says. */
    private const val DUE = Long.MIN_VALUE
}
