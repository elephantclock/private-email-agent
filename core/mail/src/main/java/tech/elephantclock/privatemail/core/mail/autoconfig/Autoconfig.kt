package tech.elephantclock.privatemail.core.mail.autoconfig

import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.net.IDN

/**
 * Finds IMAP/SMTP settings for an email address, in order: built-in presets → ISPDB →
 * the domain's autoconfig → MX matched to a known provider → SRV → the domain's own MX
 * host with standard ports → manual. Blocking; call it off the main thread.
 */
class Autoconfig(
    private val ispdb: IspdbClient,
    private val srv: SrvLookup,
) {
    fun discover(email: String): DiscoveryResult {
        val address = email.trim()
        val domain =
            runCatching { IDN.toASCII(address.substringAfterLast('@', "")).lowercase().trimEnd('.') }
                .getOrNull()
                ?.takeIf { '.' in it }
                ?: return DiscoveryResult.Manual

        ProviderPresets.forDomain(domain)?.let { return found(it.toSettings(address, DiscoverySource.PRESET)) }
        ispdb.ispdb(domain, address)?.toSettings(address, DiscoverySource.ISPDB, needsConfirmation = false)?.let {
            return found(it)
        }
        ispdb
            .autoconfig(
                domain,
                address,
            )?.toSettings(address, DiscoverySource.AUTOCONFIG, needsConfirmation = false)
            ?.let {
                return found(it)
            }

        val mxHosts = srv.mx(domain).map { it.host.trimEnd('.') }.filter { it.isNotEmpty() && it != "." }
        mxHosts.firstNotNullOfOrNull(ProviderPresets::forMxHost)?.let { preset ->
            // MX answers can be spoofed; only a built-in preset (a known provider) is trusted without asking.
            return found(preset.toSettings(address, DiscoverySource.MX))
        }
        mxHosts.firstOrNull()?.let(::providerDomain)?.takeIf { it != domain }?.let { mxDomain ->
            ispdb.ispdb(mxDomain, address)?.toSettings(address, DiscoverySource.MX, needsConfirmation = true)?.let {
                return found(it)
            }
        }

        val srvImap = srv.imap(domain)
        val srvSmtp = srv.smtp(domain)
        if (srvImap != null && srvSmtp != null) {
            val outside = !domainMatches(srvImap.host, domain) || !domainMatches(srvSmtp.host, domain)
            return found(
                DiscoveredSettings(srvImap, srvSmtp, address, DiscoverySource.SRV, needsConfirmation = outside),
            )
        }

        mxHosts.firstOrNull { domainMatches(it, domain) }?.let { host ->
            return found(
                DiscoveredSettings(
                    imap = ServerConfig(host, 993, ConnectionSecurity.SSL_TLS),
                    smtp = ServerConfig(host, 587, ConnectionSecurity.STARTTLS),
                    username = address,
                    source = DiscoverySource.MX_HOST_GUESS,
                ),
            )
        }
        return DiscoveryResult.Manual
    }

    private fun found(settings: DiscoveredSettings) = DiscoveryResult.Found(settings)

    private fun ProviderPreset.toSettings(
        email: String,
        source: DiscoverySource,
    ) = DiscoveredSettings(
        imap,
        smtp,
        email,
        source,
        id,
        name,
        note,
        needsConfirmation = false,
        passwordSupported = passwordSupported,
    )

    private fun ClientConfig.toSettings(
        email: String,
        source: DiscoverySource,
        needsConfirmation: Boolean,
    ): DiscoveredSettings? {
        val (i, s) = best() ?: return null
        return DiscoveredSettings(
            imap = i.server,
            smtp = s.server,
            username = i.username.ifBlank { email },
            source = source,
            providerId = providerId,
            providerName = displayName,
            needsConfirmation = needsConfirmation,
            passwordSupported = i.passwordAuth && s.passwordAuth,
        )
    }

    /** "mx1.mail.example.co.uk" → "example.co.uk"; a rough registrable-domain guess for ISPDB lookups. */
    internal fun providerDomain(host: String): String? {
        val labels = host.lowercase().trimEnd('.').split('.')
        if (labels.size < 2) return null
        val twoLevelSuffix = labels.size >= 3 && labels[labels.size - 2].length <= 3 && labels.last().length == 2
        return labels.takeLast(if (twoLevelSuffix) 3 else 2).joinToString(".")
    }
}
