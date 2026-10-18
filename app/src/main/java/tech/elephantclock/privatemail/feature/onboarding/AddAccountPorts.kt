package tech.elephantclock.privatemail.feature.onboarding

import kotlinx.coroutines.flow.first
import tech.elephantclock.privatemail.core.data.db.AccountDao
import tech.elephantclock.privatemail.core.data.db.toEntity
import tech.elephantclock.privatemail.core.data.secure.SecretStore
import tech.elephantclock.privatemail.core.mail.ConnectionTestResult
import tech.elephantclock.privatemail.core.mail.ConnectionTester
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.autoconfig.Autoconfig
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoveryResult
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.ServerConfig
import tech.elephantclock.privatemail.security.CertificatePinStore

/** Finds server settings for an address. Blocking: sends the domain over the network. */
fun interface SettingsDiscovery {
    fun discover(email: String): DiscoveryResult
}

/**
 * Signs in to both servers. Blocking. [extraPins] are certificates trusted during this setup
 * but not saved yet (they're saved with the account).
 */
fun interface ConnectionCheck {
    fun test(
        imap: ServerConfig,
        smtp: ServerConfig,
        credentials: MailCredentials,
        extraPins: List<CertificateInfo>,
    ): ConnectionTestResult
}

/** An account already stored for the same address: re-adding it updates the row. */
data class ExistingAccount(
    val id: AccountId,
    val createdAtMillis: Long,
)

/** Where a new account goes. The view model calls these in a fixed order; see `AddAccountViewModel.save`. */
interface AccountStore {
    suspend fun find(email: String): ExistingAccount?

    fun savePassword(
        id: AccountId,
        password: Password,
    )

    fun trust(certificate: CertificateInfo)

    /** Upserting also clears `AccountEntity.authProblem`, so sync and IDLE pick the account up again. */
    suspend fun upsert(
        account: Account,
        createdAtMillis: Long,
    )
}

class AutoconfigDiscovery(
    private val autoconfig: Autoconfig,
) : SettingsDiscovery {
    override fun discover(email: String) = autoconfig.discover(email)
}

class TesterConnectionCheck(
    private val stored: CertificatePins,
) : ConnectionCheck {
    override fun test(
        imap: ServerConfig,
        smtp: ServerConfig,
        credentials: MailCredentials,
        extraPins: List<CertificateInfo>,
    ): ConnectionTestResult {
        val pending = CertificatePins.of(*extraPins.toTypedArray())
        val pins =
            CertificatePins { host, port, sha ->
                stored.isPinned(host, port, sha) ||
                    pending.isPinned(host, port, sha)
            }
        return ConnectionTester(pins).test(imap, smtp, credentials)
    }
}

class RoomAccountStore(
    private val accounts: AccountDao,
    private val secrets: SecretStore,
    private val pins: CertificatePinStore,
) : AccountStore {
    override suspend fun find(email: String): ExistingAccount? =
        accounts
            .all()
            .first()
            .firstOrNull { it.email == email }
            ?.let { ExistingAccount(AccountId(it.id), it.createdAtMillis) }

    override fun savePassword(
        id: AccountId,
        password: Password,
    ) = secrets.put(id, password)

    override fun trust(certificate: CertificateInfo) = pins.trust(certificate)

    override suspend fun upsert(
        account: Account,
        createdAtMillis: Long,
    ) = accounts.upsert(account.toEntity(createdAtMillis))
}
