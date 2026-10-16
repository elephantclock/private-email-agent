package tech.elephantclock.privatemail.core.mail.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Why the platform rejected a server certificate. */
enum class UntrustedReason {
    /** Self-signed, unknown CA, or broken chain. */
    CHAIN,

    /** Valid chain, but issued for a different host name. */
    HOSTNAME,

    /** Outside its validity period. */
    EXPIRED,
}

/** What the user sees before deciding to trust one certificate for one server. */
data class CertificateInfo(
    val host: String,
    val port: Int,
    /** SHA-256 of the DER-encoded leaf certificate, colon-separated upper-case hex. */
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notAfterMillis: Long,
    val reason: UntrustedReason,
) {
    companion object {
        fun of(
            host: String,
            port: Int,
            leaf: X509Certificate,
            reason: UntrustedReason,
        ) = CertificateInfo(
            host = host,
            port = port,
            sha256 = sha256Fingerprint(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notAfterMillis = leaf.notAfter.time,
            reason = reason,
        )
    }
}

fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(cert.encoded)
        .joinToString(":") { "%02X".format(it) }
