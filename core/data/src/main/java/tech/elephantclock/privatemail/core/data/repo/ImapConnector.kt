package tech.elephantclock.privatemail.core.data.repo

import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.data.secure.StoredSecret
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.imap.ImapClient
import tech.elephantclock.privatemail.core.mail.imap.ImapConfig
import tech.elephantclock.privatemail.core.mail.imap.ImapConnection
import tech.elephantclock.privatemail.core.model.Account

/** Opens a signed-in IMAP connection for an account. Blocking; call it off the main thread. */
fun interface ImapConnector {
    fun connect(account: Account): MailResult<ImapConnection>
}

/** Password accounts: the password comes from [SecretStore] and is handed only to [ImapClient]. */
class PasswordImapConnector(
    private val secrets: SecretStore,
    private val client: ImapClient,
) : ImapConnector {
    override fun connect(account: Account): MailResult<ImapConnection> =
        when (val secret = secrets.get(account.id)) {
            is StoredSecret.Found -> {
                client.connect(ImapConfig.from(account.imap), MailCredentials(account.username, secret.password))
            }

            StoredSecret.Missing, StoredSecret.NeedsReentry -> {
                MailResult.Failed(AuthError.PasswordUnavailable)
            }
        }
}
