package tech.elephantclock.privatemail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.data.triage.TriageMover
import tech.elephantclock.privatemail.core.data.triage.TriageOrchestrator
import tech.elephantclock.privatemail.core.mail.autoconfig.AndroidDnsClient
import tech.elephantclock.privatemail.core.mail.autoconfig.Autoconfig
import tech.elephantclock.privatemail.core.mail.autoconfig.IspdbClient
import tech.elephantclock.privatemail.core.mail.autoconfig.SrvLookup
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.feature.onboarding.AddAccountViewModel
import tech.elephantclock.privatemail.feature.onboarding.AutoconfigDiscovery
import tech.elephantclock.privatemail.feature.onboarding.RoomAccountStore
import tech.elephantclock.privatemail.feature.onboarding.TesterConnectionCheck
import tech.elephantclock.privatemail.security.CertificatePinStore

/**
 * The app's long-lived objects, created on first use (Hilt replaces this later). One
 * repository per process, so the worker and the foreground IDLE share its folder locks.
 */
class MailGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.open(appContext) }

    val secrets: SecretStore by lazy { SecretStore(appContext) }

    /** Certificates the user trusted in the add-account screen; read live by every connection. */
    val certificatePins: CertificatePinStore by lazy { CertificatePinStore(appContext) }

    /** One connector for sync and moves, so both see the same pins and password store. */
    private val connector by lazy { PasswordImapConnector(secrets, ImapClient(certificatePins)) }

    val repository: MailRepository by lazy { MailRepository(database, connector) }

    /** Honours the stored discard mode (hold 30 days, or straight to an existing Trash; T48). */
    val mover: TriageMover by lazy {
        TriageMover(
            database,
            repository,
            connector,
            discardMode = discardModeOf(appContext),
        )
    }

    /**
     * Triage: rules only until the on-device model arrives (T31); the stored settings, which keep
     * every account label-only until the user applies the first run and the Sent backfill is
     * complete (T43); alerts through the notification channels (T49); no contacts yet (needs a
     * permission flow).
     */
    val orchestrator: TriageOrchestrator by lazy {
        TriageOrchestrator(
            database,
            repository,
            mover,
            ai = ai(appContext).triageAi,
            settings = onboarding(appContext).triageSettings,
            notifier = triageNotifier(appContext),
        )
    }

    fun addAccountViewModel(initialEmail: String = "") =
        AddAccountViewModel(
            discovery = AutoconfigDiscovery(Autoconfig(IspdbClient(), SrvLookup(AndroidDnsClient(appContext)))),
            connection = TesterConnectionCheck(certificatePins),
            store = RoomAccountStore(database.accounts(), secrets, certificatePins),
            io = Dispatchers.IO,
            initialEmail = initialEmail,
        )
}
