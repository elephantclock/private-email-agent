package tech.elephantclock.privatemail.feature.discarded

import tech.elephantclock.privatemail.core.model.Address
import tech.elephantclock.privatemail.core.model.AuthResult
import tech.elephantclock.privatemail.core.triage.signals.AuthResultsParser
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** How a sender can be asked to stop sending, read from its `List-Unsubscribe` headers. */
sealed interface UnsubscribeMethod {
    /** RFC 8058: one HTTPS POST of `List-Unsubscribe=One-Click` to [url]; nothing is opened. */
    data class OneClick(
        val url: String,
    ) : UnsubscribeMethod {
        val host: String get() = URI(url).host
    }

    /** RFC 2369 `mailto:`: an email from the user to [to]. */
    data class Mailto(
        val to: Address,
        val subject: String,
        val body: String,
    ) : UnsubscribeMethod
}

/**
 * Reads `List-Unsubscribe` (RFC 2369) and `List-Unsubscribe-Post` (RFC 8058).
 *
 * - **One-click** only with `List-Unsubscribe-Post: List-Unsubscribe=One-Click`, an `https` URI,
 *   and a DKIM pass from the trusted `Authentication-Results` with nothing failing: RFC 8058
 *   requires the headers to be covered by a valid DKIM signature, and without one anybody could
 *   make the app POST to any address. (We can't check the signature covers the headers; a pass
 *   is the best evidence available on the device.)
 * - Otherwise **mailto**, with a single plain address.
 * - Otherwise nothing: an `https` link without one-click would mean opening a web page, which
 *   the app never does on its own.
 */
object ListUnsubscribe {
    private const val ONE_CLICK = "List-Unsubscribe=One-Click"
    private const val MAX_URI = 2_048
    private const val MAX_SUBJECT = 200
    private const val MAX_BODY = 1_000
    const val DEFAULT_TEXT = "unsubscribe"

    /** [headers] as stored (lower-cased names, values in order). */
    fun methodFor(
        headers: Map<String, List<String>>,
        trustedAuthservIds: Set<String> = emptySet(),
    ): UnsubscribeMethod? {
        val uris = headers["list-unsubscribe"].orEmpty().flatMap(::uris)
        if (uris.isEmpty()) return null
        val oneClickOffered = headers["list-unsubscribe-post"].orEmpty().any { it.trim() == ONE_CLICK }
        if (oneClickOffered && dkimPassed(headers["authentication-results"], trustedAuthservIds)) {
            uris.firstNotNullOfOrNull(::httpsUrl)?.let { return UnsubscribeMethod.OneClick(it) }
        }
        return uris.firstNotNullOfOrNull(::mailto)
    }

    private fun dkimPassed(
        instances: List<String>?,
        trusted: Set<String>,
    ): Boolean {
        val auth = AuthResultsParser.parse(instances, trusted)
        return auth.dkim == AuthResult.PASS && !auth.anyFail
    }

    /** The `<...>` URIs of one header value, unfolded; text outside brackets is ignored. */
    internal fun uris(value: String): List<String> =
        Regex("<([^<>]*)>")
            .findAll(value)
            .map { m -> m.groupValues[1].filterNot { it.isWhitespace() } }
            .filter { it.isNotEmpty() && it.length <= MAX_URI }
            .toList()

    internal fun httpsUrl(raw: String): String? {
        val uri =
            try {
                URI(raw)
            } catch (_: URISyntaxException) {
                return null
            }
        val ok =
            uri.scheme?.lowercase(Locale.ROOT) == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                raw.none { it.isISOControl() }
        return raw.takeIf { ok }
    }

    internal fun mailto(raw: String): UnsubscribeMethod.Mailto? {
        if (!raw.regionMatches(0, "mailto:", 0, 7, ignoreCase = true)) return null
        val rest = raw.substring(7)
        val address = percentDecode(rest.substringBefore('?')) ?: return null
        if (address.isEmpty() || ',' in address || address.count { it == '@' } != 1) return null
        if (address.any { it.isWhitespace() || it.isISOControl() || it in "<>\"()[];:\\" }) return null
        if (address.startsWith('@') || address.endsWith('@') || '.' !in address.substringAfter('@')) return null
        val query =
            rest
                .substringAfter('?', "")
                .split('&')
                .filter { '=' in it }
                .associate { it.substringBefore('=').lowercase(Locale.ROOT) to it.substringAfter('=') }
        val subject =
            query["subject"]
                ?.let(::percentDecode)
                ?.oneLine()
                ?.take(MAX_SUBJECT)
                ?.ifBlank { null } ?: DEFAULT_TEXT
        val body =
            query["body"]
                ?.let(::percentDecode)
                ?.filter { it == '\n' || it == '\r' || !it.isISOControl() }
                ?.take(MAX_BODY)
                ?.ifBlank { null } ?: DEFAULT_TEXT
        return UnsubscribeMethod.Mailto(Address.of(address), subject, body)
    }

    private fun String.oneLine() = map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

    /** RFC 3986 percent-decoding as UTF-8 ('+' stays '+', as in mailto); null when malformed. */
    internal fun percentDecode(s: String): String? {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val byte = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(byte)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
