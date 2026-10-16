package tech.elephantclock.privatemail.core.mail.tls

/**
 * Certificates the user chose to trust, each for exactly one host and port.
 * There is no "trust all": a pin only ever matches one fingerprint.
 */
fun interface CertificatePins {
    fun isPinned(
        host: String,
        port: Int,
        sha256: String,
    ): Boolean

    companion object {
        val None = CertificatePins { _, _, _ -> false }

        fun of(vararg pins: CertificateInfo) =
            CertificatePins { host, port, sha256 ->
                pins.any { it.host.equals(host, ignoreCase = true) && it.port == port && it.sha256 == sha256 }
            }
    }
}
