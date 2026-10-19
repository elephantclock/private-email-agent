package tech.elephantclock.privatemail.feature.onboarding

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import tech.elephantclock.privatemail.core.data.onboarding.FirstRun
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunReview
import tech.elephantclock.privatemail.core.data.onboarding.ReviewGroup
import tech.elephantclock.privatemail.core.data.onboarding.ReviewItem
import tech.elephantclock.privatemail.core.data.onboarding.SentHistoryBackfill
import tech.elephantclock.privatemail.core.data.onboarding.SentPeople
import tech.elephantclock.privatemail.core.data.onboarding.SentPerson
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.model.AccountId

/** What the background first-run work is doing (from WorkManager), for screens 02 and 03. */
data class FirstRunWorkState(
    val mode: Mode? = null,
    val status: Status = Status.IDLE,
    val stage: Stage? = null,
    val done: Int = 0,
    val total: Int = 0,
    /** Set when the work stopped for something the user must fix (password, account removed). */
    val blocked: Boolean = false,
) {
    enum class Mode { PREPARE, SORT, APPLY }

    enum class Status { IDLE, RUNNING, SUCCEEDED, FAILED }

    enum class Stage { SYNC, SENT, SORT, APPLY }

    val running: Boolean get() = status == Status.RUNNING
}

/** Starts and observes the first-run worker; `WorkManagerFirstRunWork` in the app, a fake in tests. */
interface FirstRunWork {
    /** Sync and the Sent backfill; started when screen 02 opens. Idempotent. */
    fun prepare(id: AccountId)

    /** After screen 02: prepare (resumes), then the label-only triage of the last 30 days. */
    fun sort(id: AccountId)

    /** Screen 03's Apply: the moves, then auto-file on. */
    fun apply(id: AccountId)

    fun state(id: AccountId): Flow<FirstRunWorkState>
}

/** Screen 02's data: people from Sent and the stored settings. */
interface PersonaliseData {
    fun people(id: AccountId): Flow<List<SentPerson>>

    fun prefs(id: AccountId): Flow<AccountPrefs>

    suspend fun update(
        id: AccountId,
        transform: (AccountPrefs) -> AccountPrefs,
    )
}

/** Screen 03's data: the review, its drill-down and the "Label only" choice. */
interface FirstRunData {
    suspend fun review(id: AccountId): FirstRunReview

    suspend fun items(
        id: AccountId,
        group: ReviewGroup,
    ): List<ReviewItem>

    suspend fun keep(messageId: Long)

    suspend fun backfillStatus(id: AccountId): BackfillStatus

    suspend fun labelOnly(id: AccountId)

    fun prefs(id: AccountId): Flow<AccountPrefs>
}

class StorePersonaliseData(
    private val people: SentPeople,
    private val store: TriageSettingsStore,
    private val ownAddresses: suspend (AccountId) -> Set<String>,
) : PersonaliseData {
    override fun people(id: AccountId): Flow<List<SentPerson>> = flow { emitAll(people.people(id, ownAddresses(id))) }

    override fun prefs(id: AccountId): Flow<AccountPrefs> = store.account(id)

    override suspend fun update(
        id: AccountId,
        transform: (AccountPrefs) -> AccountPrefs,
    ) {
        store.update(id, transform)
    }
}

class EngineFirstRunData(
    private val firstRun: FirstRun,
    private val backfill: SentHistoryBackfill,
    private val store: TriageSettingsStore,
) : FirstRunData {
    override suspend fun review(id: AccountId) = firstRun.review(id)

    override suspend fun items(
        id: AccountId,
        group: ReviewGroup,
    ) = firstRun.items(id, group)

    override suspend fun keep(messageId: Long) = firstRun.keep(messageId)

    override suspend fun backfillStatus(id: AccountId) = backfill.status(id)

    override suspend fun labelOnly(id: AccountId) = firstRun.labelOnlyDone(id)

    override fun prefs(id: AccountId): Flow<AccountPrefs> = store.account(id)
}
