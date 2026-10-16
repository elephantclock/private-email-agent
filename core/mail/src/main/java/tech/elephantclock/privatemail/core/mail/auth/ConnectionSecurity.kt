package tech.elephantclock.privatemail.core.mail.auth

import tech.elephantclock.privatemail.core.mail.tls.TlsSocketFactory
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import java.util.Properties

/** Timeouts for one connection. Angus waits forever by default, so these are always set. */
data class Timeouts(
    val connectMillis: Int = 15_000,
    val readMillis: Int = 30_000,
)

/**
 * Angus Mail properties for one protocol ("imap" or "smtp") with TLS always on:
 * implicit TLS, or STARTTLS that is required (a server that doesn't offer it is refused
 * before any credentials are sent). Host-name checks happen in the trust manager.
 */
internal fun mailProperties(
    protocol: String,
    security: ConnectionSecurity,
    socketFactory: TlsSocketFactory,
    timeouts: Timeouts,
): Properties =
    Properties().apply {
        val p = "mail.$protocol"
        when (security) {
            ConnectionSecurity.SSL_TLS -> {
                put("$p.ssl.enable", "true")
            }

            ConnectionSecurity.STARTTLS -> {
                put("$p.ssl.enable", "false")
                put("$p.starttls.enable", "true")
                put("$p.starttls.required", "true")
            }
        }
        put("$p.ssl.socketFactory", socketFactory)
        put("$p.ssl.checkserveridentity", "false")
        put("$p.connectiontimeout", timeouts.connectMillis.toString())
        put("$p.timeout", timeouts.readMillis.toString())
        put("$p.writetimeout", timeouts.readMillis.toString())
        put("$p.auth.mechanisms", AuthMechanism.PASSWORD_MECHANISMS)
        put("$p.auth.xoauth2.disable", "true")
        put("$p.auth.ntlm.disable", "true")
        put("mail.debug", "false")
        put("mail.debug.auth", "false")
    }
