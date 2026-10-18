package tech.elephantclock.privatemail.core.mail.ops

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.VerdictAction
import java.util.concurrent.TimeUnit

/**
 * One message the engine moved, kept [UndoLog.RETENTION_MILLIS] so it can be moved back.
 * Mirrors `core:data`'s `UndoEntity`, plus [toUidValidity]: without it a `toUid` from before a
 * UIDVALIDITY change could point at a different message.
 */
data class UndoEntry(
    val accountId: AccountId,
    /** Moves made together (one tap, one triage run) share it and undo together. */
    val batchId: String,
    /** The app's local message id, when the caller has one. */
    val localId: Long?,
    val action: VerdictAction,
    val fromFolder: String,
    val fromUidValidity: Long,
    val fromUid: Long,
    val toFolder: String,
    val toUidValidity: Long,
    /** From COPYUID, else found by Message-ID right after the move; null when neither worked. */
    val toUid: Long?,
    val messageIdHeader: String?,
    val createdAtMillis: Long,
)

/**
 * Persistence for undo. `core:data` backs it with `UndoDao` (needs a `toUidValidity` column);
 * [InMemoryUndoLog] is for tests.
 */
interface UndoLog {
    suspend fun record(entries: List<UndoEntry>)

    suspend fun batch(
        accountId: AccountId,
        batchId: String,
    ): List<UndoEntry>

    suspend fun deleteBatch(
        accountId: AccountId,
        batchId: String,
    )

    /** Returns how many entries were removed. */
    suspend fun purgeOlderThan(cutoffMillis: Long): Int

    companion object {
        val RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(7)
    }
}

class InMemoryUndoLog : UndoLog {
    private val mutex = Mutex()
    private val entries = mutableListOf<UndoEntry>()

    override suspend fun record(entries: List<UndoEntry>) = mutex.withLock { this.entries += entries }

    override suspend fun batch(
        accountId: AccountId,
        batchId: String,
    ) = mutex.withLock { entries.filter { it.accountId == accountId && it.batchId == batchId } }

    override suspend fun deleteBatch(
        accountId: AccountId,
        batchId: String,
    ) = mutex.withLock { entries.removeAll { it.accountId == accountId && it.batchId == batchId }.let { } }

    override suspend fun purgeOlderThan(cutoffMillis: Long): Int =
        mutex.withLock {
            val before = entries.size
            entries.removeAll { it.createdAtMillis < cutoffMillis }
            before - entries.size
        }

    suspend fun all(): List<UndoEntry> = mutex.withLock { entries.toList() }
}
