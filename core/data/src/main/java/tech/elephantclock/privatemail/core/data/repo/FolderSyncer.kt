package tech.elephantclock.privatemail.core.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.FolderEntity
import tech.elephantclock.privatemail.core.data.db.deleteUids
import tech.elephantclock.privatemail.core.data.db.messageEntity
import tech.elephantclock.privatemail.core.data.db.toBits
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.cancellable
import tech.elephantclock.privatemail.core.mail.imap.isConnectionProblem
import tech.elephantclock.privatemail.core.mail.sync.ChangeFetcher
import tech.elephantclock.privatemail.core.mail.sync.HeaderFetcher
import tech.elephantclock.privatemail.core.mail.sync.KnownMessages
import tech.elephantclock.privatemail.core.mail.sync.SyncState
import tech.elephantclock.privatemail.core.model.AccountId

/**
 * Syncs one folder on an open connection and writes the result. The caller holds the folder's
 * lock. Order matters:
 * 1. Reconcile what we already store ([ChangeFetcher]): flags changed and messages expunged by
 *    other clients. This comes first so its HIGHESTMODSEQ is read before *any* flags are
 *    fetched in this sync, including those of the new mail below; a change made after that
 *    point has a higher mod-sequence and is caught next time. A failure here is recorded but
 *    doesn't stop new mail (unless the connection itself is gone).
 * 2. Fetch new mail ([HeaderFetcher]) and store it in one transaction with the new sync state.
 *    On a UIDVALIDITY change rows of the old generations are deleted first; rows already under
 *    the new one (moved in by triage) are kept.
 */
internal class FolderSyncer(
    private val db: AppDatabase,
    private val headerFetcher: HeaderFetcher,
    private val changeFetcher: ChangeFetcher,
    private val io: CoroutineDispatcher,
) {
    data class Outcome(
        val result: FolderSyncResult,
        val newMessages: NewMessages?,
    )

    suspend fun sync(
        connection: ImapConnection,
        accountId: AccountId,
        path: String,
    ): Outcome {
        val account = accountId.value
        // updateSyncState is a plain UPDATE: without the row the state would never be saved.
        db.folders().insertNew(listOf(placeholder(account, path)))
        val folder = checkNotNull(db.folders().get(account, path))
        val previous =
            folder
                .takeIf { it.uidValidity > 0 }
                ?.let { SyncState(accountId, path, it.uidValidity, it.highestUid, it.lastSyncMillis ?: 0) }

        var flagsChanged = 0
        var removed = 0
        var problem: Throwable? = null
        if (previous != null) {
            try {
                val (flags, gone) = reconcile(connection, accountId, path, previous, folder.highestModSeq)
                flagsChanged = flags
                removed = gone
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isConnectionProblem()) return Outcome(FolderSyncResult(path, error = e), null)
                problem = e
            }
        }

        val batch =
            try {
                connection.cancellable(io) { headerFetcher.sync(it, accountId, path, previous) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome(FolderSyncResult(path, 0, flagsChanged, removed, error = e), null)
            }
        val ids =
            db.withTransaction {
                val messages = db.messages()
                if (batch.reset) {
                    messages.deleteStaleGenerations(account, path, batch.state.uidValidity)
                    db.folders().updateModSeq(account, path, null)
                }
                val rows = messages.insertAll(batch.messages.map { messageEntity(it.header, it.body) })
                val s = batch.state
                db.folders().updateSyncState(account, path, s.uidValidity, s.highestUid, s.lastSyncMillis)
                rows.filter { it > 0 }
            }
        return Outcome(
            FolderSyncResult(path, ids.size, flagsChanged, removed, batch.reset, problem),
            ids.takeIf { it.isNotEmpty() }?.let { NewMessages(accountId, path, it) },
        )
    }

    /** Applies other clients' flag changes and expunges. Returns (flags changed, rows removed). */
    private suspend fun reconcile(
        connection: ImapConnection,
        accountId: AccountId,
        path: String,
        previous: SyncState,
        storedModSeq: Long?,
    ): Pair<Int, Int> {
        val account = accountId.value
        val stored =
            db
                .messages()
                .uidFlags(account, path, previous.uidValidity)
                .filter { it.uid <= previous.highestUid }
                .associate { it.uid to it.flags }
        if (stored.isEmpty()) return 0 to 0
        val known = KnownMessages(previous.uidValidity, stored.keys.min(), previous.highestUid, storedModSeq)
        // Null: UIDVALIDITY changed under us; the header sync below resets the folder.
        val changes = connection.cancellable(io) { changeFetcher.fetch(it, path, known) } ?: return 0 to 0
        // Only rows whose flags really differ: every UPDATE also rewrites the row's FTS entry.
        val updates = changes.flagUpdates.filter { u -> stored[u.uid]?.let { it != u.flags.toBits() } == true }
        val gone = stored.keys - changes.presentUids
        db.withTransaction {
            val messages = db.messages()
            updates.forEach { messages.updateFlags(account, path, previous.uidValidity, it.uid, it.flags.toBits()) }
            messages.deleteUids(account, path, previous.uidValidity, gone)
            db.folders().updateModSeq(account, path, changes.highestModSeq)
        }
        return updates.size to gone.size
    }

    private fun placeholder(
        accountId: String,
        path: String,
    ) = FolderEntity(accountId, path, path, uidValidity = 0, specialUse = null, role = null, description = null)
}
