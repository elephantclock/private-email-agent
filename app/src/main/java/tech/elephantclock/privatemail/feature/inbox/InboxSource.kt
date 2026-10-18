package tech.elephantclock.privatemail.feature.inbox

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.elephantclock.privatemail.core.data.db.AccountEntity
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.db.FolderSummary
import tech.elephantclock.privatemail.core.data.db.FtsQuery
import tech.elephantclock.privatemail.core.data.db.MessageWithVerdict
import tech.elephantclock.privatemail.core.data.db.TriageCounts
import tech.elephantclock.privatemail.core.data.repo.AccountSyncResult
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.SyncReport
import tech.elephantclock.privatemail.core.mail.auth.needsUserAction

/** What a pull-to-refresh achieved. */
enum class RefreshOutcome {
    DONE,

    /** At least one account or folder failed for a reason that may pass (network, server). */
    PARTLY_FAILED,

    /** An account needs its password again; the banner explains it. */
    NEEDS_SIGN_IN,
}

/** The data the lists read. [RoomInboxSource] in the app; fakes in tests and screenshots. */
interface InboxSource {
    fun accounts(): Flow<List<AccountEntity>>

    /** Priority messages per account id. */
    fun priorityCounts(): Flow<Map<String, Int>>

    /** Priority across all accounts ([accountId] null) or one. */
    fun priority(accountId: String?): Flow<PagingData<MessageWithVerdict>>

    fun folder(
        accountId: String,
        path: String,
    ): Flow<PagingData<MessageWithVerdict>>

    fun folders(accountId: String): Flow<List<FolderSummary>>

    fun triagedSince(
        sinceMillis: Long,
        accountId: String?,
    ): Flow<TriageCounts>

    /** Full-text search over subject, sender and preview, newest first. */
    suspend fun search(text: String): List<MessageWithVerdict>

    /** Sync now (pull to refresh), then triage what arrived. */
    suspend fun refresh(): RefreshOutcome
}

class RoomInboxSource(
    private val db: AppDatabase,
    private val repository: MailRepository,
    private val afterSync: suspend () -> Unit = {},
) : InboxSource {
    private val config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)

    override fun accounts() = db.accounts().all()

    override fun priorityCounts() =
        db.reading().priorityCounts().map { list ->
            list.associate {
                it.accountId to
                    it.count
            }
        }

    override fun priority(accountId: String?): Flow<PagingData<MessageWithVerdict>> =
        Pager(config) {
            if (accountId == null) db.messages().priority() else db.reading().priorityIn(accountId)
        }.flow

    override fun folder(
        accountId: String,
        path: String,
    ): Flow<PagingData<MessageWithVerdict>> = Pager(config) { db.messages().inFolder(accountId, path) }.flow

    override fun folders(accountId: String) = db.reading().syncedFolders(accountId)

    override fun triagedSince(
        sinceMillis: Long,
        accountId: String?,
    ) = db.reading().triagedSince(sinceMillis, accountId)

    override suspend fun search(text: String): List<MessageWithVerdict> {
        val query = FtsQuery.of(text) ?: return emptyList()
        return db.messages().search(query, limit = SEARCH_LIMIT)
    }

    override suspend fun refresh(): RefreshOutcome {
        val report = repository.syncAll()
        runCatching { afterSync() }
        return report.outcome()
    }

    companion object {
        const val PAGE_SIZE = 40
        const val SEARCH_LIMIT = 100
    }
}

internal fun SyncReport.outcome(): RefreshOutcome =
    when {
        accounts.any {
            it is AccountSyncResult.SignInFailed && it.error.needsUserAction
        } -> RefreshOutcome.NEEDS_SIGN_IN

        accounts.any {
            it is AccountSyncResult.Skipped &&
                it.reason.startsWith(
                    "waiting",
                )
        } -> RefreshOutcome.NEEDS_SIGN_IN

        shouldRetry || accounts.any { it is AccountSyncResult.Failed } -> RefreshOutcome.PARTLY_FAILED

        accounts.any { r ->
            r is AccountSyncResult.Synced && r.folders.any { it.error != null }
        } -> RefreshOutcome.PARTLY_FAILED

        accounts.any { it is AccountSyncResult.SignInFailed } -> RefreshOutcome.PARTLY_FAILED

        else -> RefreshOutcome.DONE
    }
