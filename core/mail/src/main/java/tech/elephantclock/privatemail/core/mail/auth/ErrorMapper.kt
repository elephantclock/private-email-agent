package tech.elephantclock.privatemail.core.mail.auth

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns exceptions and server replies into [AuthError]s. */
object ErrorMapper {
    private val urlPattern = Regex("""https?://[^\s)>\]]+""")
    private val startTlsMissing = Regex("STARTTLS (is )?required but", RegexOption.IGNORE_CASE)

    fun map(
        error: Throwable,
        host: String,
        port: Int,
        password: Password?,
        rejectedCertificate: CertificateInfo?,
    ): AuthError {
        if (rejectedCertificate != null) return AuthError.CertificateUntrusted(rejectedCertificate)
        val chain = generateSequence(error) { it.cause }.toList()
        chain.firstOrNull { it is UnknownHostException }?.let { return AuthError.HostNotFound(host) }
        if (chain.any { it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException }) {
            return AuthError.PortBlockedOrTimeout(host, port)
        }
        val text = redact(chain.mapNotNull { it.message }.joinToString(" | "), password)
        if (chain.any { it is SSLException }) return AuthError.TlsFailure(text)
        // IMAP: "STARTTLS required but not supported by server"; SMTP: "STARTTLS is required but host does not support STARTTLS".
        if (startTlsMissing.containsMatchIn(text)) return AuthError.StartTlsUnavailable
        if (error is AuthenticationFailedException) return fromServerReply(text)
        if (error is MessagingException && looksLikeAuthReply(text)) return fromServerReply(text)
        return AuthError.Unknown(text)
    }

    /** Classifies an IMAP `NO` / SMTP 4xx-5xx authentication reply. */
    fun fromServerReply(reply: String): AuthError {
        val r = reply.lowercase()
        return when {
            "application-specific password" in r || "app password" in r || "5.7.9" in r -> {
                AuthError.AppPasswordRequired(urlPattern.find(reply)?.value)
            }

            "no authentication mechanisms supported" in r || "no login methods" in r ||
                "basic authentication is disabled" in r || "5.7.139" in r || "logindisabled" in r -> {
                AuthError.AuthMethodNotSupported(reply)
            }

            "web browser" in r || "web login" in r || "verify your account" in r || "5.7.14" in r ||
                "too many" in r || "[unavailable]" in r || "[limit]" in r || "try again later" in r ||
                "locked" in r || "disabled" in r || r.contains(Regex("""\b(421|454)\b""")) -> {
                AuthError.AccountLockedOrRateLimited(reply)
            }

            else -> {
                AuthError.WrongCredentials
            }
        }
    }

    private fun looksLikeAuthReply(text: String) =
        Regex("""\[AUTHENTICATIONFAILED]|\b5\.7\.\d+|\b535\b|\b534\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Belt and braces: servers don't echo passwords, but never let one through. */
    fun redact(
        text: String,
        password: Password?,
    ): String {
        val secret = password?.reveal() ?: return text
        return if (secret.length >= 3) text.replace(secret, "██") else text
    }
}
