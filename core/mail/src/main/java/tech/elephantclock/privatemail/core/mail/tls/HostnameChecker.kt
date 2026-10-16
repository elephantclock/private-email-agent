package tech.elephantclock.privatemail.core.mail.tls

import java.security.cert.X509Certificate
import java.util.Locale

/**
 * RFC 6125 host name check against the certificate's subject alternative names.
 * Done here rather than by the platform because Android and desktop JSSE differ.
 */
object HostnameChecker {
    private const val SAN_DNS = 2
    private const val SAN_IP = 7

    fun matches(
        host: String,
        cert: X509Certificate,
    ): Boolean {
        val names = cert.subjectAlternativeNames ?: return false
        val target = host.trimEnd('.').lowercase(Locale.ROOT)
        val isIp = target.isIpLiteral()
        return names.any { entry ->
            val type = entry[0] as Int
            val value = (entry[1] as? String)?.lowercase(Locale.ROOT) ?: return@any false
            when {
                isIp -> type == SAN_IP && value == target
                type == SAN_DNS -> dnsMatches(target, value.trimEnd('.'))
                else -> false
            }
        }
    }

    /** `*.example.com` matches `mail.example.com`, not `example.com` or `a.b.example.com`. */
    internal fun dnsMatches(
        host: String,
        pattern: String,
    ): Boolean {
        if (!pattern.startsWith("*.")) return host == pattern
        val suffix = pattern.substring(1)
        if (suffix.count { it == '.' } < 2) return false
        return host.endsWith(suffix) && host.indexOf('.') == host.length - suffix.length
    }

    private fun String.isIpLiteral() = all { it.isDigit() || it == '.' } || ':' in this
}
