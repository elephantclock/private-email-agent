package tech.elephantclock.privatemail.core.data.db

import tech.elephantclock.privatemail.core.mail.ops.UndoEntry
import tech.elephantclock.privatemail.core.mail.ops.UndoLog
import tech.elephantclock.privatemail.core.model.AccountId

/** `core:mail`'s [UndoLog] in the database, so undo survives the process (7 days). */
class RoomUndoLog(
    private val db: AppDatabase,
) : UndoLog {
    override suspend fun record(entries: List<UndoEntry>) = db.undo().insertAll(entries.map { it.toEntity() })

    override suspend fun batch(
        accountId: AccountId,
        batchId: String,
    ): List<UndoEntry> = db.undo().batch(accountId.value, batchId).map { it.toEntry() }

    override suspend fun deleteBatch(
        accountId: AccountId,
        batchId: String,
    ) = db.undo().deleteBatch(accountId.value, batchId)

    override suspend fun purgeOlderThan(cutoffMillis: Long): Int = db.undo().purgeOlderThan(cutoffMillis)
}
