package tech.elephantclock.privatemail.core.mail.imap

import tech.elephantclock.privatemail.core.mail.auth.Timeouts
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig

data class ImapConfig(
    val host: String,
    val port: Int,
    val security: ConnectionSecurity,
    val timeouts: Timeouts = Timeouts(),
) {
    companion object {
        fun from(
            server: ServerConfig,
            timeouts: Timeouts = Timeouts(),
        ) = ImapConfig(server.host, server.port, server.security, timeouts)
    }
}
