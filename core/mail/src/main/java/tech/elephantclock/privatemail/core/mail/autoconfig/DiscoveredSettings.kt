package tech.elephantclock.privatemail.core.mail.autoconfig

import tech.elephantclock.privatemail.core.model.ServerConfig

enum class DiscoverySource {
    /** Built-in provider preset matched by the email domain. */
    PRESET,

    /** Thunderbird ISPDB (autoconfig.thunderbird.net, HTTPS). */
    ISPDB,

    /** The domain's own autoconfig file over HTTPS. */
    AUTOCONFIG,

    /** The domain's MX points at a known provider (preset or ISPDB). */
    MX,

    /** RFC 6186 SRV records. */
    SRV,

    /** The domain's MX host is inside the domain; standard ports assumed. */
    MX_HOST_GUESS,
}

/** Something the add-account screen must tell the user before they type a password. */
sealed interface ProviderNote {
    val helpUrl: String

    /** Gmail, iCloud, Yahoo and Fastmail want an app-specific password for IMAP. */
    data class AppPasswordRequired(
        override val helpUrl: String,
    ) : ProviderNote

    /** Microsoft blocks password sign-in on most accounts; Microsoft sign-in comes in a later version. */
    data class MicrosoftPasswordBlocked(
        override val helpUrl: String,
    ) : ProviderNote
}

/**
 * Server settings found for an address. Discovery only ever sends the domain anywhere.
 *
 * When [needsConfirmation] is true the hosts came from unauthenticated DNS and lie outside
 * the email's domain: the add-account screen (T41) must show them and get an explicit OK
 * before any password is sent (RFC 6186 §6).
 */
data class DiscoveredSettings(
    val imap: ServerConfig,
    val smtp: ServerConfig,
    val username: String,
    val source: DiscoverySource,
    val providerId: String? = null,
    val providerName: String? = null,
    val note: ProviderNote? = null,
    val needsConfirmation: Boolean = false,
    /** False when the provider lists OAuth only: password sign-in will likely be refused. */
    val passwordSupported: Boolean = true,
)

sealed interface DiscoveryResult {
    data class Found(
        val settings: DiscoveredSettings,
    ) : DiscoveryResult

    /** Nothing found; show the manual settings form. */
    data object Manual : DiscoveryResult
}
