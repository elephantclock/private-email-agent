package tech.elephantclock.privatemail

import android.content.Context
import androidx.work.WorkManager
import tech.elephantclock.privatemail.core.data.onboarding.FirstRun
import tech.elephantclock.privatemail.core.data.onboarding.SentHistoryBackfill
import tech.elephantclock.privatemail.core.data.onboarding.SentPeople
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.data.settings.SentBackfillGate
import tech.elephantclock.privatemail.core.data.settings.StoredTriageSettings
import tech.elephantclock.privatemail.core.data.settings.TriageSettingsStore
import tech.elephantclock.privatemail.core.data.triage.TriageOrchestrator
import tech.elephantclock.privatemail.core.data.triage.TriageSettings
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.onboarding.EngineFirstRunData
import tech.elephantclock.privatemail.feature.onboarding.FirstRunViewModel
import tech.elephantclock.privatemail.feature.onboarding.FirstRunWork
import tech.elephantclock.privatemail.feature.onboarding.PersonaliseViewModel
import tech.elephantclock.privatemail.feature.onboarding.StorePersonaliseData
import tech.elephantclock.privatemail.work.WorkManagerFirstRunWork

/**
 * Onboarding's objects (T42/T43), kept beside [MailGraph] so that file stays the coordinator's.
 * One per process, like the graph: the settings store must be a single DataStore instance.
 *
 * Coordinator wiring: the orchestrator should read [triageSettings], so auto-file follows the
 * user's Apply (and the backfill gate) instead of staying label-only:
 * `TriageOrchestrator(database, repository, mover, settings = onboarding(appContext).triageSettings)`.
 */
class OnboardingGraph internal constructor(
    context: Context,
    private val graph: MailGraph,
) {
    private val appContext = context.applicationContext

    val store: TriageSettingsStore = TriageSettingsStore.get(appContext)

    val gate: SentBackfillGate by lazy { SentBackfillGate(graph.database, store) }

    /** The persistent [TriageSettings] for `MailGraph.orchestrator`. Auto-file only after Apply and the backfill. */
    val triageSettings: TriageSettings by lazy { StoredTriageSettings(store, gate) }

    /** Its own connection factory over the graph's password store and pins (the graph's is private). */
    private val connector by lazy { PasswordImapConnector(graph.secrets, ImapClient(graph.certificatePins)) }

    val backfill: SentHistoryBackfill by lazy {
        SentHistoryBackfill(graph.database, graph.repository, connector, store)
    }

    /** First-run triage: the user's settings (VIPs, authserv-ids) but never a move. */
    private val labelOnly: TriageOrchestrator by lazy {
        TriageOrchestrator(
            graph.database,
            graph.repository,
            graph.mover,
            settings = StoredTriageSettings.labelOnly(triageSettings),
        )
    }

    val firstRun: FirstRun by lazy {
        FirstRun(graph.database, graph.repository, graph.mover, labelOnly, backfill, store)
    }

    val work: FirstRunWork by lazy { WorkManagerFirstRunWork(WorkManager.getInstance(appContext)) }

    private val people by lazy { SentPeople(graph.database) }

    fun personaliseViewModel(id: AccountId) =
        PersonaliseViewModel(
            id,
            StorePersonaliseData(people, store) { own ->
                val email =
                    graph.database
                        .accounts()
                        .get(own.value)
                        ?.email
                store.get(own).ownAddresses + listOfNotNull(email)
            },
            work,
        )

    fun firstRunViewModel(id: AccountId) = FirstRunViewModel(id, EngineFirstRunData(firstRun, backfill, store), work)

    internal companion object {
        @Volatile private var instance: OnboardingGraph? = null

        fun of(
            context: Context,
            graph: MailGraph,
        ): OnboardingGraph =
            instance?.takeIf { it.graph === graph } ?: synchronized(this) {
                instance?.takeIf { it.graph === graph } ?: OnboardingGraph(context, graph).also { instance = it }
            }
    }
}

/** The onboarding objects for this graph (one per process). */
fun MailGraph.onboarding(context: Context): OnboardingGraph = OnboardingGraph.of(context, this)
