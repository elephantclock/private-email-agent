package tech.elephantclock.privatemail.feature.onboarding

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tech.elephantclock.privatemail.core.data.db.VerdictCount
import tech.elephantclock.privatemail.core.data.onboarding.FirstRunReview
import tech.elephantclock.privatemail.core.data.onboarding.ReviewGroup
import tech.elephantclock.privatemail.core.data.onboarding.ReviewItem
import tech.elephantclock.privatemail.core.data.onboarding.SentPerson
import tech.elephantclock.privatemail.core.data.settings.AccountPrefs
import tech.elephantclock.privatemail.core.data.settings.BackfillStatus
import tech.elephantclock.privatemail.core.data.settings.OnboardingStep
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.FolderRole
import tech.elephantclock.privatemail.core.model.VerdictAction
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Mode
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Stage
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWorkState.Status

val ACCOUNT = AccountId("acc-1")

class FakeWork : FirstRunWork {
    val calls = mutableListOf<Mode>()
    val state = MutableStateFlow(FirstRunWorkState())

    override fun prepare(id: AccountId) {
        calls += Mode.PREPARE
    }

    override fun sort(id: AccountId) {
        calls += Mode.SORT
    }

    override fun apply(id: AccountId) {
        calls += Mode.APPLY
    }

    override fun state(id: AccountId): Flow<FirstRunWorkState> = state
}

/** Settings and people in memory, shared by the fakes of screens 02 and 03. */
class FakeOnboardingData(
    people: List<SentPerson> = SAMPLE_PEOPLE,
) : PersonaliseData,
    FirstRunData {
    val people = MutableStateFlow(people)
    val prefs = MutableStateFlow(AccountPrefs())
    var counts: List<VerdictCount> = SAMPLE_COUNTS
    var backfill = BackfillStatus.COMPLETE
    val kept = mutableListOf<Long>()
    var items: List<ReviewItem> = SAMPLE_ITEMS

    override fun people(id: AccountId): Flow<List<SentPerson>> = people

    override fun prefs(id: AccountId): Flow<AccountPrefs> = prefs

    override suspend fun update(
        id: AccountId,
        transform: (AccountPrefs) -> AccountPrefs,
    ) {
        prefs.value = transform(prefs.value)
    }

    override suspend fun review(id: AccountId) = FirstRunReview.of(counts, prefs.value)

    override suspend fun items(
        id: AccountId,
        group: ReviewGroup,
    ) = items.filterNot { it.id in kept }

    override suspend fun keep(messageId: Long) {
        kept += messageId
        counts =
            counts.map { if (it.action == VerdictAction.DISCARD) it.copy(count = it.count - 1) else it }
    }

    override suspend fun backfillStatus(id: AccountId) = backfill

    override suspend fun labelOnly(id: AccountId) {
        prefs.value = prefs.value.copy(step = OnboardingStep.DONE, autoFileRequested = false)
    }
}

val SAMPLE_PEOPLE =
    listOf(
        SentPerson("irina@partner.example", "Irina Novak", 42, 1_700_000_000_000),
        SentPerson("anton@izi.example", "Anton Kim", 31, 1_700_000_000_000),
        SentPerson("maria.lopez@client.example", null, 12, 1_700_000_000_000),
        SentPerson("tom@vendor.example", "Tom Weber", 4, 1_700_000_000_000),
    )

val SAMPLE_COUNTS =
    listOf(
        VerdictCount(VerdictAction.KEEP, FolderRole.PRIORITY, null, 24),
        VerdictCount(VerdictAction.FILE, FolderRole.PEOPLE, null, 58),
        VerdictCount(VerdictAction.FILE, FolderRole.MONEY, null, 46),
        VerdictCount(VerdictAction.FILE, FolderRole.WORK, null, 214),
        VerdictCount(VerdictAction.FILE, FolderRole.REPORTS, null, 70),
        VerdictCount(VerdictAction.DISCARD, FolderRole.DISCARDED, null, 1592),
        VerdictCount(VerdictAction.JUNK, FolderRole.JUNK, null, 56),
    )

val SAMPLE_ITEMS =
    listOf(
        ReviewItem(
            1,
            "Weekend Deals",
            "48 hours only: 30% off everything",
            0,
            "Newsletter or marketing: unsubscribe link, one-click unsubscribe",
        ),
        ReviewItem(
            2,
            "Social Network",
            "You have 3 new followers",
            0,
            "Social network notification from social.example",
        ),
        ReviewItem(
            3,
            "Travel Offers",
            "Flights to Lisbon from 49 EUR",
            0,
            "Newsletter or marketing: unsubscribe link, sent through Mailchimp",
        ),
    )

fun sorting(
    done: Int,
    total: Int,
) = FirstRunWorkState(Mode.SORT, Status.RUNNING, Stage.SORT, done, total)
