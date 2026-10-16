package tech.elephantclock.privatemail.core.mail.autoconfig

/**
 * Looks a domain up in Thunderbird's ISPDB and in the domain's own autoconfig file.
 * Only the domain is sent; never the full address or anything else.
 */
class IspdbClient(
    private val fetcher: HttpsFetcher = HttpsFetcher(),
    private val ispdbBase: String = "https://autoconfig.thunderbird.net/v1.1/",
    /** The domain's own autoconfig locations (Thunderbird's two well-known URLs). */
    private val autoconfigUrls: (String) -> List<String> = { domain ->
        listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
        )
    },
) {
    fun ispdb(
        domain: String,
        email: String,
    ): ClientConfig? = fetcher.get(ispdbBase + domain)?.let { ClientConfigXml.parse(it, email) }

    fun autoconfig(
        domain: String,
        email: String,
    ): ClientConfig? =
        autoconfigUrls(domain).firstNotNullOfOrNull { url ->
            fetcher.get(url)?.let { ClientConfigXml.parse(it, email) }
        }
}
