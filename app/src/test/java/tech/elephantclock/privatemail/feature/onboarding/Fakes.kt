package tech.elephantclock.privatemail.feature.onboarding

import tech.elephantclock.privatemail.core.mail.ConnectionTestResult
import tech.elephantclock.privatemail.core.mail.MailResult
import tech.elephantclock.privatemail.core.mail.auth.AuthError
import tech.elephantclock.privatemail.core.mail.auth.MailCredentials
import tech.elephantclock.privatemail.core.mail.auth.Password
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoveredSettings
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoveryResult
import tech.elephantclock.privatemail.core.mail.autoconfig.DiscoverySource
import tech.elephantclock.privatemail.core.mail.autoconfig.ProviderNote
import tech.elephantclock.privatemail.core.mail.imap.Capabilities
import tech.elephantclock.privatemail.core.mail.imap.ImapCheck
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import tech.elephantclock.privatemail.core.mail.tls.UntrustedReason
import tech.elephantclock.privatemail.core.model.Account
import tech.elephantclock.privatemail.core.model.AccountId
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig

class FakeDiscovery(
    var result: DiscoveryResult,
) : SettingsDiscovery {
    val calls = mutableListOf<String>()

    override fun discover(email: String): DiscoveryResult {
        calls += email
        return result
    }
}

class FakeConnection(
    vararg results: ConnectionTestResult,
) : ConnectionCheck {
    private val queue = ArrayDeque(results.toList())

    data class Call(
        val imap: ServerConfig,
        val smtp: ServerConfig,
        val username: String,
        val password: Password,
        val extraPins: List<CertificateInfo>,
    )

    val calls = mutableListOf<Call>()

    override fun test(
        imap: ServerConfig,
        smtp: ServerConfig,
        credentials: MailCredentials,
        extraPins: List<CertificateInfo>,
    ): ConnectionTestResult {
        calls += Call(imap, smtp, credentials.username, credentials.password, extraPins)
        return queue.removeFirstOrNull() ?: OK
    }
}

class FakeStore(
    private val existing: Map<String, ExistingAccount> = emptyMap(),
) : AccountStore {
    val events = mutableListOf<String>()
    val passwords = mutableMapOf<AccountId, Password>()
    val accounts = mutableListOf<Pair<Account, Long>>()
    var failUpsert = false

    override suspend fun find(email: String) = existing[email]

    override fun savePassword(
        id: AccountId,
        password: Password,
    ) {
        events += "password:$id"
        passwords[id] = password
    }

    override fun trust(certificate: CertificateInfo) {
        events += "pin:${certificate.host}:${certificate.port}"
    }

    override suspend fun upsert(
        account: Account,
        createdAtMillis: Long,
    ) {
        if (failUpsert) error("disk full")
        events += "upsert:${account.id}"
        accounts += account to createdAtMillis
    }
}

val IMAP_OK: MailResult<ImapCheck> =
    MailResult.Ok(
        ImapCheck(Capabilities(true, true, true, true, true, false), emptyList()),
    )
val SMTP_OK: MailResult<Unit> = MailResult.Ok(Unit)
val OK = ConnectionTestResult(IMAP_OK, SMTP_OK)

fun failed(error: AuthError) = MailResult.Failed(error)

fun cert(
    host: String,
    port: Int,
    sha: String = "AA:BB:$port",
) = CertificateInfo(host, port, sha, "CN=$host", "CN=$host", 0L, UntrustedReason.CHAIN)

val GMAIL =
    DiscoveryResult.Found(
        DiscoveredSettings(
            imap = ServerConfig("imap.gmail.com", 993, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.gmail.com", 465, ConnectionSecurity.SSL_TLS),
            username = "ann@gmail.com",
            source = DiscoverySource.PRESET,
            providerId = "gmail",
            providerName = "Gmail",
            note = ProviderNote.AppPasswordRequired("https://support.google.com/accounts/answer/185833"),
        ),
    )

/** SRV records pointing outside the domain: the case RFC 6186 § 6 warns about. */
val SRV_ELSEWHERE =
    DiscoveryResult.Found(
        DiscoveredSettings(
            imap = ServerConfig("mail.hoster.example", 993, ConnectionSecurity.SSL_TLS),
            smtp = ServerConfig("smtp.hoster.example", 465, ConnectionSecurity.SSL_TLS),
            username = "bob@example.org",
            source = DiscoverySource.SRV,
            needsConfirmation = true,
        ),
    )
