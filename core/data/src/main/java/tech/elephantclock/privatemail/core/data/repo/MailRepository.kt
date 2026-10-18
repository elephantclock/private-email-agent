package tech.elephantclock.privatemail.core.data.repo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.FolderEntity
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.RemoteFolder
import tech.elephantclock.privatemail.core.mail.imap.cancellable
import tech.elephantclock.privatemail.core.mail.sync.ChangeFetcher
import tech.elephantclock.privatemail.core.mail.sync.HeaderFetcher
import tech.elephantclock.privatemail.core.mail.sync.IdleEvent
import tech.elephantclock.privatemail.core.mail.sync.IdleLoop
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the database in step with the server, read-only (EXAMINE, BODY.PEEK; nothing is
 * ever flagged, moved or deleted on the server here).
 *
 * Which folders: the Inbox, plus every folder with a triage role (the value folders,
 * Discarded, Junk), so the folder screens and Priority stay current. Others are listed
 * (so T41/T44 can offer them) but not synced.
 *
 * Entry points: [syncAll] (the periodic worker), [watchInboxes] (IMAP IDLE while the app is
 * in the foreground) and [syncAccount]. Syncs of the same folder never overlap: each
 * folder has a lock, which T17 should also hold while moving messages ([withFolderLock]).
 */
class MailRepository(
    private val db: AppDatabase,
    private val connector: ImapConnector,
    headerFetcher: HeaderFetcher = HeaderFetcher(),
    changeFetcher: ChangeFetcher = ChangeFetcher(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val idleEvents: (Account, connect: () -> MailResult<ImapConnection>) -> Flow<IdleEvent> =
        { _, connect -> IdleLoop(connect, INBOX).events() },
) {
    private val syncer = FolderSyncer(db, headerFetcher, changeFetcher, io)
    private val locks = ConcurrentHashMap<Pair<String, String>, Mutex>()
    private val newMessagesFlow =
        MutableSharedFlow<NewMessages>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Messages stored for the first time, emitted after they are committed. A nudge for
     * triage (T24), not a queue: nothing is replayed, and with nobody collecting (a sync in
     * the background worker) emissions are lost. `MessageDao.untriaged()` is the durable
     * source; T24 should drain it on start and whenever this emits. Includes every synced
     * folder; T24 decides what to triage (arrivals outside the Inbox were filed by someone).
     */
    val newMessages: SharedFlow<NewMessages> = newMessagesFlow.asSharedFlow()

    /** Every account that doesn't need the user's attention, one after another. */
    suspend fun syncAll(): SyncReport =
        SyncReport(
            db.accounts().all().first().map { entity ->
                val id = AccountId(entity.id)
                try {
                    syncAccount(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never let one account's surprise stop the others.
                    AccountSyncResult.Failed(id, e)
                }
            },
        )

    /**
     * Signs in once and syncs [onlyFolders], or else the folder list and every folder to sync.
     * Per-folder failures are reported in the result; one bad folder never stops the others.
     * Main-safe: the work runs on [io].
     */
    suspend fun syncAccount(
        id: AccountId,
        onlyFolders: List<String>? = null,
    ): AccountSyncResult = withContext(io) { syncAccountOnIo(id, onlyFolders) }

    private suspend fun syncAccountOnIo(
        id: AccountId,
        onlyFolders: List<String>?,
    ): AccountSyncResult {
        val entity = db.accounts().get(id.value) ?: return AccountSyncResult.Skipped(id, "unknown account")
        entity.authProblem?.let { return AccountSyncResult.Skipped(id, "waiting for the user: $it") }
        val connection =
            when (val result = connect(entity.toModel())) {
                is MailResult.Failed -> {
                    recordSignInFailure(id, result.error)
                    return AccountSyncResult.SignInFailed(id, result.error)
                }

                is MailResult.Ok -> {
                    result.value
                }
            }
        try {
            val paths = onlyFolders ?: refreshFolders(connection, id)
            val results = paths.map { path -> syncFolder(connection, id, path) }
            return AccountSyncResult.Synced(id, results)
        } finally {
            // After a cancellation the connection was already dropped, so this returns at once.
            withContext(NonCancellable + io) { runCatching { connection.close() } }
        }
    }

    /**
     * IMAP IDLE on the Inbox of every account that doesn't need the user's attention, syncing
     * the Inbox on each change; follows accounts being added or removed. Runs until cancelled:
     * collect it only while the app is in the foreground (the plan's battery budget allows
     * IDLE nowhere else).
     */
    suspend fun watchInboxes() {
        db
            .accounts()
            .all()
            .map { accounts -> accounts.filter { it.authProblem == null }.map { it.toModel() } }
            .distinctUntilChanged()
            .collectLatest { accounts -> coroutineScope { accounts.forEach { launch { watchInboxGuarded(it) } } } }
    }

    /** One account's surprise (a database error, a bug) must not end the other accounts' watches. */
    private suspend fun watchInboxGuarded(account: Account) {
        while (true) {
            try {
                watchInbox(account)
                return // the IDLE loop stopped for good (see IdleEvent.Stopped)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(WATCH_RESTART_MILLIS)
            }
        }
    }

    internal suspend fun watchInbox(account: Account) {
        idleEvents(account) { connector.connect(account) }
            // Changes that arrive during a sync collapse into one more sync afterwards.
            .conflate()
            .collect { event ->
                when (event) {
                    IdleEvent.Connected, IdleEvent.Changed -> syncInboxQuietly(account.id)
                    is IdleEvent.Stopped -> recordSignInFailure(account.id, event.error)
                    is IdleEvent.Disconnected -> Unit
                }
            }
    }

    private suspend fun syncInboxQuietly(id: AccountId) {
        try {
            syncAccount(id, listOf(INBOX))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next IDLE event (or the worker) tries again; the watch must keep running.
        }
    }

    /** Runs [block] while no sync of this folder is running; for T17's moves. */
    suspend fun <T> withFolderLock(
        accountId: AccountId,
        path: String,
        block: suspend () -> T,
    ): T = locks.getOrPut(accountId.value to path) { Mutex() }.withLock { block() }

    private suspend fun syncFolder(
        connection: ImapConnection,
        id: AccountId,
        path: String,
    ): FolderSyncResult {
        val outcome = withFolderLock(id, path) { syncer.sync(connection, id, path) }
        outcome.newMessages?.let { newMessagesFlow.tryEmit(it) }
        return outcome.result
    }

    /** Stores newly listed folders and returns the paths to sync. Without a listing, the Inbox. */
    private suspend fun refreshFolders(
        connection: ImapConnection,
        id: AccountId,
    ): List<String> {
        val remote =
            try {
                connection.cancellable(io) { it.listFolders() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        db.folders().insertNew(remote.filter { it.selectable }.map { it.toEntity(id) })
        val withRole =
            db
                .folders()
                .forAccount(id.value)
                .filter { it.role != null }
                .map { it.path }
        return (listOf(INBOX) + withRole).distinct()
    }

    /** Signs in off the main thread. A connection that arrives after cancellation is dropped, never leaked. */
    private suspend fun connect(account: Account): MailResult<ImapConnection> {
        val result = withContext(io + NonCancellable) { connector.connect(account) }
        try {
            currentCoroutineContext().ensureActive()
        } catch (e: CancellationException) {
            (result as? MailResult.Ok)?.value?.abort()
            throw e
        }
        return result
    }

    private suspend fun recordSignInFailure(
        id: AccountId,
        error: AuthError,
    ) {
        if (error.needsUserAction) db.accounts().setAuthProblem(id.value, error.javaClass.simpleName)
    }

    private fun RemoteFolder.toEntity(id: AccountId) =
        FolderEntity(
            accountId = id.value,
            path = path,
            displayName = separator?.let { path.substringAfterLast(it) } ?: path,
            uidValidity = 0,
            specialUse = specialUse,
            role = null,
            description = null,
        )

    companion object {
        /** IMAP's one fixed folder name; Angus always reports it in upper case. */
        const val INBOX = "INBOX"

        private const val WATCH_RESTART_MILLIS = 60_000L
    }
}
