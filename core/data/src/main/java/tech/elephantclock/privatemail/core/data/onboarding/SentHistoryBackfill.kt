package tech.elephantclock.privatemail.core.data.onboarding

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.messageEntity
import tech.elephantclock.privatemail.core.data.db.toModel
import tech.elephantclock.privatemail.core.data.repo.ImapConnector
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.FolderBackfill
import tech.elephantclock.privatemail.core.data.settings.SentBackfillGate
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.mail.imap.cancellable
import tech.elephantclock.privatemail.core.mail.sync.SentHistoryCursor
import tech.elephantclock.privatemail.core.mail.sync.SentHistoryFetcher
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.MessageBody

/** How a backfill run ended. */
sealed interface BackfillResult {
    data object Complete : BackfillResult

    data object NoSentFolder : BackfillResult

    /** Sign-in failed, the connection dropped, or Sent isn't synced yet; run again later, it resumes. */
    data class Interrupted(
        val reason: String,
    ) : BackfillResult
}

/**
 * The carried-over safety item of T43: before auto-file may move anything, store about 12 months
 * of the Sent folder's **headers** (no bodies, read-only, [SentHistoryFetcher]) as ordinary rows
 * of the Sent folder, so `RelationshipRepository` protects people the user wrote to long before
 * the 30-day sync window.
 *
 * - **Batched and resumable:** one page (newest first) per round trip; each page is committed,
 *   then its cursor is saved in [store]. A crash between the two replays one page, which is
 *   harmless: rows are inserted with IGNORE on their server identity.
 * - **Clean cancellation:** blocking IMAP calls run under [cancellable], which drops the
 *   connection at once; nothing half-written is left, and the next run resumes at the cursor.
 * - Rows are written under the folder's lock and only when the server's UIDVALIDITY matches the
 *   one the sync stored, so the sync's own bookkeeping (highest UID, mod-sequence) is untouched.
 */
class SentHistoryBackfill(
    private val db: AppDatabase,
    private val repo: MailRepository,
    private val connector: ImapConnector,
    private val store: TriageSettingsStore,
    private val fetcher: SentHistoryFetcher = SentHistoryFetcher(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val gate = SentBackfillGate(db, store)

    suspend fun status(id: AccountId): BackfillStatus = gate.status(id)

    /** Runs until every Sent folder is done; [progress] gets (stored, total) after each page. */
    suspend fun run(
        id: AccountId,
        progress: suspend (stored: Int, total: Int) -> Unit = { _, _ -> },
    ): BackfillResult {
        val entity = db.accounts().get(id.value) ?: return BackfillResult.Interrupted("unknown account")
        entity.authProblem?.let { return BackfillResult.Interrupted("waiting for the user") }
        val paths = db.triage().sentFolders(id.value).sorted()
        if (paths.isEmpty()) return BackfillResult.NoSentFolder
        if (gate.status(id) == BackfillStatus.COMPLETE) return BackfillResult.Complete
        val connection =
            when (val result = withContext(io + NonCancellable) { connector.connect(entity.toModel()) }) {
                is MailResult.Failed -> return BackfillResult.Interrupted("sign-in failed")
                is MailResult.Ok -> result.value
            }
        try {
            currentCoroutineContext().ensureActive()
            for (path in paths) {
                val problem = backfillFolder(connection, id, path, paths, progress)
                if (problem != null) return BackfillResult.Interrupted(problem)
            }
        } finally {
            withContext(NonCancellable + io) { runCatching { connection.close() } }
        }
        return if (gate.status(id) == BackfillStatus.COMPLETE) {
            BackfillResult.Complete
        } else {
            BackfillResult.Interrupted("Sent changed during the backfill")
        }
    }

    /** Null when the folder is done; else why it stopped. */
    private suspend fun backfillFolder(
        connection: ImapConnection,
        id: AccountId,
        path: String,
        paths: List<String>,
        progress: suspend (Int, Int) -> Unit,
    ): String? {
        while (true) {
            val folder = db.folders().get(id.value, path)
            if (folder == null || folder.uidValidity <= 0) return "Sent folder $path not synced yet"
            val entry = store.get(id).backfill[path]?.takeIf { it.uidValidity == folder.uidValidity }
            if (entry?.done == true) return null
            val cursor = entry?.belowUid?.let { SentHistoryCursor(entry.uidValidity, it) }
            val page =
                try {
                    connection.cancellable(io) { fetcher.page(it, id, path, cursor) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return "fetch failed: ${e.javaClass.simpleName}"
                }
            val written =
                repo.withFolderLock(id, path) {
                    // The sync may have seen a UIDVALIDITY reset meanwhile; then this page is stale.
                    if (db.folders().get(id.value, path)?.uidValidity != page.uidValidity) return@withFolderLock null
                    db.withTransaction {
                        db.messages().insertAll(page.headers.map { messageEntity(it, MessageBody(it.ref, "", false)) })
                    }
                }
            if (written == null) return "Sent folder $path was reset; sync it first"
            val updated =
                FolderBackfill(
                    uidValidity = page.uidValidity,
                    belowUid = page.next?.belowUid ?: entry?.belowUid,
                    done = page.remaining == 0,
                    total = page.total,
                    stored = page.total - page.remaining,
                )
            val prefs = store.update(id) { it.copy(backfill = it.backfill + (path to updated)) }
            val all = paths.mapNotNull { prefs.backfill[it] }
            progress(all.sumOf { it.stored }, all.sumOf { it.total })
            if (updated.done) return null
        }
    }
}
