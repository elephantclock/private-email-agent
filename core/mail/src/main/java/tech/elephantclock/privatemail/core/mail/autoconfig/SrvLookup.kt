package tech.elephantclock.privatemail.core.mail.autoconfig

import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig

/** RFC 6186 / RFC 8314 SRV discovery; implicit TLS records are preferred over STARTTLS ones. */
class SrvLookup(
    private val dns: DnsClient,
) {
    fun imap(domain: String) =
        first(
            domain,
            "_imaps._tcp" to ConnectionSecurity.SSL_TLS,
            "_imap._tcp" to ConnectionSecurity.STARTTLS,
        )

    fun smtp(domain: String) =
        first(
            domain,
            "_submissions._tcp" to ConnectionSecurity.SSL_TLS,
            "_submission._tcp" to ConnectionSecurity.STARTTLS,
        )

    fun mx(domain: String): List<MxRecord> = dns.query(domain, DnsMessage.TYPE_MX)?.let(DnsMessage::parseMx).orEmpty()

    private fun first(
        domain: String,
        vararg services: Pair<String, ConnectionSecurity>,
    ): ServerConfig? =
        services.firstNotNullOfOrNull { (service, security) ->
            val records = dns.query("$service.$domain", DnsMessage.TYPE_SRV)?.let(DnsMessage::parseSrv).orEmpty()
            records
                .firstOrNull { it.target != "." && it.port in 1..65535 }
                ?.let { ServerConfig(it.target.trimEnd('.'), it.port, security) }
        }
}
