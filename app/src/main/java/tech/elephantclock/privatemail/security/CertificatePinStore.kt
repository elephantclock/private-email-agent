package tech.elephantclock.privatemail.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import tech.elephantclock.privatemail.core.mail.tls.CertificateInfo
import tech.elephantclock.privatemail.core.mail.tls.CertificatePins
import java.util.concurrent.ConcurrentHashMap

/**
 * Certificates the user chose to trust, one per host and port. SharedPreferences rather than
 * DataStore because [isPinned] is called synchronously inside the TLS handshake; the map is
 * loaded once and kept in memory, so every client sees a new pin at once. The file sits in the
 * `sharedpref` backup domain, which is excluded from cloud backup and device transfer.
 */
class CertificatePinStore(
    private val prefs: SharedPreferences,
) : CertificatePins {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private val pins = ConcurrentHashMap<String, String>()

    init {
        prefs.all.forEach { (key, value) -> if (value is String) pins[key] = value }
    }

    override fun isPinned(
        host: String,
        port: Int,
        sha256: String,
    ): Boolean = pins[key(host, port)] == sha256

    /** Trusts [certificate] for its own host and port only; replaces an older pin for them. */
    @SuppressLint("UseKtx") // the KTX edit {} hides commit()'s result, which we check
    fun trust(certificate: CertificateInfo) {
        val key = key(certificate.host, certificate.port)
        check(prefs.edit().putString(key, certificate.sha256).commit()) { "could not save the certificate" }
        pins[key] = certificate.sha256
    }

    private fun key(
        host: String,
        port: Int,
    ) = "${host.lowercase().trimEnd('.')}:$port"

    companion object {
        const val PREFS_NAME = "pea_certificate_pins"
    }
}
