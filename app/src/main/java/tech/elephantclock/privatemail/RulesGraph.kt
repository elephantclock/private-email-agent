package tech.elephantclock.privatemail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tech.elephantclock.privatemail.core.data.secure.StoredSecret
import tech.elephantclock.privatemail.core.data.settings.DiscardMode
import tech.elephantclock.privatemail.core.data.settings.SentBackfillRerun
import tech.elephantclock.privatemail.core.data.triage.DiscardedActions
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.smtp.SmtpSender
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.feature.discarded.AppUnsubscriber
import tech.elephantclock.privatemail.feature.discarded.DataDiscardedCommands
import tech.elephantclock.privatemail.feature.discarded.DiscardedViewModel
import tech.elephantclock.privatemail.feature.discarded.RoomDiscardedSource
import tech.elephantclock.privatemail.feature.rules.ContentRulesFiles
import tech.elephantclock.privatemail.feature.rules.RulesViewModel
import tech.elephantclock.privatemail.feature.rules.StoreRulesData
import tech.elephantclock.privatemail.notify.SuppressedAlerts

// Screen 07's wiring (T48), kept out of MailGraph so lanes don't collide. The coordinator plugs
// these in; see each function for where.

/** The Rules tab: `RulesRoute(graph.rulesViewModel(context))`. */
fun MailGraph.rulesViewModel(context: Context): RulesViewModel {
    val onboarding = onboarding(context)
    return RulesViewModel(
        data = StoreRulesData(database, onboarding.store, onboarding.gate),
        files = ContentRulesFiles(context.applicationContext.contentResolver),
        io = Dispatchers.IO,
    )
}

/**
 * The account's discard mode, for the mover: `TriageMover(database, repository, connector,
 * discardMode = discardModeOf(appContext))` so "straight to Trash" is honoured.
 */
fun MailGraph.discardModeOf(context: Context): suspend (AccountId) -> DiscardMode {
    val store = onboarding(context).store
    return { id -> store.get(id).discardMode }
}

/** Re-reads Sent history where the auto-file gate closed on its own; call from `SyncWorker.afterSync`. */
fun MailGraph.sentBackfillRerun(context: Context): SentBackfillRerun {
    val onboarding = onboarding(context)
    return SentBackfillRerun(database, onboarding.store, onboarding.gate, onboarding.backfill)
}

/**
 * [discardedViewModel] with the stored settings, so one-click unsubscribe trusts only the
 * account's own authserv-ids (T47's open item). Replaces `discardedViewModel()` in the shell.
 */
fun MailGraph.storedDiscardedViewModel(context: Context): DiscardedViewModel =
    DiscardedViewModel(
        source = RoomDiscardedSource(database, onboarding(context).triageSettings),
        commands = DataDiscardedCommands(DiscardedActions(database, mover)),
        unsubscriber =
            AppUnsubscriber(
                db = database,
                credentials = { id, username ->
                    (secrets.get(id) as? StoredSecret.Found)?.let { MailCredentials(username, it.password) }
                },
                smtp = SmtpSender(certificatePins),
            ),
    )

/**
 * The inbox badge (T49) with the stored settings: held-back alerts count only for accounts
 * whose alerts are on. (Which `Authentication-Results` are trusted was already decided by
 * triage with the stored authserv-ids, so the rows themselves need no settings.)
 */
fun MailGraph.storedSuppressedAlertCount(context: Context): Flow<Int> {
    val store = onboarding(context).store
    return combine(SuppressedAlerts(database).items(), store.all) { rows, prefs ->
        rows.count { row ->
            val p = prefs.accounts[row.accountId]
            p == null || (p.notifications && (p.alertUrgent || p.alertHigh))
        }
    }.distinctUntilChanged()
}
