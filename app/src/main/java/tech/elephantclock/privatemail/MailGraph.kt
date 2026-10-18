package tech.elephantclock.privatemail

import android.content.Context
import tech.elephantclock.privatemail.core.data.db.AppDatabase
import tech.elephantclock.privatemail.core.data.repo.MailRepository
import tech.elephantclock.privatemail.core.data.repo.PasswordImapConnector
import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins

/**
 * The app's long-lived objects, created on first use (Hilt replaces this later). One
 * repository per process, so the worker and the foreground IDLE share its folder locks.
 */
class MailGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.open(appContext) }

    val repository: MailRepository by lazy {
        // Pinned certificates are stored once T41 adds the trust dialog; until then none.
        MailRepository(database, PasswordImapConnector(SecretStore(appContext), ImapClient(CertificatePins.None)))
    }
}
