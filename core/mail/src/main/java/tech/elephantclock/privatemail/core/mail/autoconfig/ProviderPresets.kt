package tech.elephantclock.privatemail.core.mail.autoconfig

import tech.elephantclock.privatemail.core.model.ConnectionSecurity.SSL_TLS
import tech.elephantclock.privatemail.core.model.ConnectionSecurity.STARTTLS
import tech.elephantclock.privatemail.core.model.ServerConfig

data class ProviderPreset(
    val id: String,
    val name: String,
    val domains: Set<String>,
    /** MX host suffixes that identify custom domains hosted by this provider. */
    val mxDomains: Set<String>,
    val imap: ServerConfig,
    val smtp: ServerConfig,
    val note: ProviderNote? = null,
    val passwordSupported: Boolean = true,
)

/** Settings checked against Thunderbird's ISPDB on 16 Oct 2026 (Fastmail: from Fastmail's docs). */
object ProviderPresets {
    val all =
        listOf(
            ProviderPreset(
                id = "gmail",
                name = "Gmail",
                domains = setOf("gmail.com", "googlemail.com"),
                mxDomains = setOf("google.com", "googlemail.com"),
                imap = ServerConfig("imap.gmail.com", 993, SSL_TLS),
                smtp = ServerConfig("smtp.gmail.com", 465, SSL_TLS),
                note = ProviderNote.AppPasswordRequired("https://support.google.com/accounts/answer/185833"),
            ),
            ProviderPreset(
                id = "microsoft",
                name = "Outlook.com / Microsoft 365",
                domains = setOf("outlook.com", "hotmail.com", "live.com", "msn.com", "hotmail.co.uk", "outlook.de"),
                mxDomains = setOf("protection.outlook.com", "outlook.com", "hotmail.com"),
                imap = ServerConfig("outlook.office365.com", 993, SSL_TLS),
                smtp = ServerConfig("smtp.office365.com", 587, STARTTLS),
                note =
                    ProviderNote.MicrosoftPasswordBlocked(
                        "https://support.microsoft.com/en-us/outlook/pop-imap-and-smtp-settings-for-outlook-com",
                    ),
                passwordSupported = false,
            ),
            ProviderPreset(
                id = "icloud",
                name = "iCloud Mail",
                domains = setOf("icloud.com", "me.com", "mac.com"),
                mxDomains = setOf("mail.icloud.com"),
                imap = ServerConfig("imap.mail.me.com", 993, SSL_TLS),
                smtp = ServerConfig("smtp.mail.me.com", 587, STARTTLS),
                note = ProviderNote.AppPasswordRequired("https://support.apple.com/en-us/102654"),
            ),
            ProviderPreset(
                id = "yahoo",
                name = "Yahoo Mail",
                domains = setOf("yahoo.com", "ymail.com", "rocketmail.com", "yahoo.co.uk", "yahoo.de", "yahoo.fr"),
                mxDomains = setOf("yahoodns.net"),
                imap = ServerConfig("imap.mail.yahoo.com", 993, SSL_TLS),
                smtp = ServerConfig("smtp.mail.yahoo.com", 465, SSL_TLS),
                note = ProviderNote.AppPasswordRequired("https://help.yahoo.com/kb/SLN15241.html"),
            ),
            ProviderPreset(
                id = "zoho",
                name = "Zoho Mail",
                domains = setOf("zoho.com", "zohomail.com"),
                mxDomains = setOf("zoho.com"),
                imap = ServerConfig("imap.zoho.com", 993, SSL_TLS),
                smtp = ServerConfig("smtp.zoho.com", 465, SSL_TLS),
            ),
            ProviderPreset(
                id = "zoho-eu",
                name = "Zoho Mail (EU)",
                domains = setOf("zoho.eu", "zohomail.eu"),
                mxDomains = setOf("zoho.eu"),
                imap = ServerConfig("imap.zoho.eu", 993, SSL_TLS),
                smtp = ServerConfig("smtp.zoho.eu", 465, SSL_TLS),
            ),
            ProviderPreset(
                id = "fastmail",
                name = "Fastmail",
                domains = setOf("fastmail.com", "fastmail.fm", "fastmail.net", "fastmail.org"),
                mxDomains = setOf("messagingengine.com"),
                imap = ServerConfig("imap.fastmail.com", 993, SSL_TLS),
                smtp = ServerConfig("smtp.fastmail.com", 465, SSL_TLS),
                note = ProviderNote.AppPasswordRequired("https://www.fastmail.help/hc/en-us/articles/360058752854"),
            ),
        )

    fun forDomain(domain: String): ProviderPreset? =
        all.firstOrNull { p -> p.domains.any { domainMatches(domain, it) } }

    fun forMxHost(host: String): ProviderPreset? = all.firstOrNull { p -> p.mxDomains.any { domainMatches(host, it) } }
}

/** True if [host] is [domain] or a subdomain of it, on label boundaries (`evilgoogle.com` ≠ `google.com`). */
fun domainMatches(
    host: String,
    domain: String,
): Boolean {
    val h = host.trimEnd('.').lowercase()
    val d = domain.trimEnd('.').lowercase()
    return h == d || h.endsWith(".$d")
}
