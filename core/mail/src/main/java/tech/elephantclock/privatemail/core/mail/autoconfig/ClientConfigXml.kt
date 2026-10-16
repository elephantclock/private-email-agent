package tech.elephantclock.privatemail.core.mail.autoconfig

import org.w3c.dom.Element
import org.xml.sax.InputSource
import tech.elephantclock.privatemail.core.model.ConnectionSecurity
import tech.elephantclock.privatemail.core.model.ServerConfig
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** One `incomingServer` / `outgoingServer` entry that we can use (TLS only). */
data class ConfigServer(
    val server: ServerConfig,
    val username: String,
    /** False when the entry lists only OAuth2. */
    val passwordAuth: Boolean,
)

data class ClientConfig(
    val providerId: String?,
    val displayName: String?,
    val imap: List<ConfigServer>,
    val smtp: List<ConfigServer>,
) {
    /** Best pair: password-capable first, then implicit TLS over STARTTLS. Null if either side is missing. */
    fun best(): Pair<ConfigServer, ConfigServer>? {
        val order =
            compareByDescending<ConfigServer> { it.passwordAuth }.thenBy {
                it.server.security !=
                    ConnectionSecurity.SSL_TLS
            }
        val i = imap.sortedWith(order).firstOrNull() ?: return null
        val s = smtp.sortedWith(order).firstOrNull() ?: return null
        return i to s
    }
}

/**
 * Parses Thunderbird's clientConfig v1.1 format (ISPDB and domain autoconfig files).
 * The input is untrusted: documents with a DOCTYPE or entities are rejected before parsing.
 */
object ClientConfigXml {
    fun parse(
        xml: String,
        email: String,
    ): ClientConfig? {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
        val doc =
            try {
                newBuilderFactory().newDocumentBuilder().parse(InputSource(StringReader(xml)))
            } catch (_: Exception) {
                return null
            }
        val provider = doc.getElementsByTagName("emailProvider").item(0) as? Element ?: return null

        fun servers(
            tag: String,
            type: String,
        ) = provider.children(tag).filter { it.getAttribute("type") == type }.mapNotNull { it.toServer(email) }
        return ClientConfig(
            providerId = provider.getAttribute("id").ifBlank { null },
            displayName =
                provider
                    .children("displayName")
                    .firstOrNull()
                    ?.textContent
                    ?.trim(),
            imap = servers("incomingServer", "imap"),
            smtp = servers("outgoingServer", "smtp"),
        )
    }

    private fun Element.toServer(email: String): ConfigServer? {
        fun text(tag: String) = children(tag).firstOrNull()?.textContent?.trim()
        val security =
            when (text("socketType")?.uppercase()) {
                "SSL", "TLS" -> ConnectionSecurity.SSL_TLS
                "STARTTLS" -> ConnectionSecurity.STARTTLS
                else -> return null // plain or unknown: never
            }
        val host = text("hostname")?.let { substitute(it, email) }?.takeIf { it.isNotBlank() } ?: return null
        val port = text("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val auth = children("authentication").map { it.textContent.trim().lowercase() }
        val passwordAuth = auth.isEmpty() || auth.any { it.startsWith("password") || it == "plain" }
        return ConfigServer(
            server = ServerConfig(host, port, security),
            username = substitute(text("username") ?: "%EMAILADDRESS%", email),
            passwordAuth = passwordAuth,
        )
    }

    private fun substitute(
        template: String,
        email: String,
    ) = template
        .replace("%EMAILADDRESS%", email)
        .replace("%EMAILLOCALPART%", email.substringBeforeLast('@'))
        .replace("%EMAILDOMAIN%", email.substringAfterLast('@'))

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    private fun newBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // Not every platform parser knows every feature (Android's doesn't); the DOCTYPE check above is the guard.
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
        }
}
