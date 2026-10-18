package tech.elephantclock.privatemail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.data.secure.SecretStore
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

    val repository: MailRepository by lazy {
        MailRepository(database, PasswordImapConnector(secrets, ImapClient(certificatePins)))
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
