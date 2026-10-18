package tech.elephantclock.privatemail.core.triage.signals

import tech.elephantclock.privatemail.core.model.AuthResult
import tech.elephantclock.privatemail.core.model.AuthResults
import java.util.Locale

/**
 * Reads SPF, DKIM and DMARC from `Authentication-Results` (RFC 8601).
 *
 * **Trust model.** Any sender can add `Authentication-Results` headers; only the one the
 * receiving server prepends is real. Servers add theirs at the top, and T12 keeps every
 * instance in header order, so:
 * - with a configured authserv-id (the user's server, e.g. `mx.example.com`), only instances
 *   with that id count; if none has it the result is UNKNOWN (never a lower, forgeable one);
 * - otherwise only the topmost instance counts.
 *
 * A server that adds no header at all leaves the topmost one sender-controlled. Rules therefore
 * treat FAIL as evidence and never let PASS relax a decision.
 */
object AuthResultsParser {
    /** [instances] in header order (topmost first), as stored in `MessageHeader.headers`. */
    fun parse(
        instances: List<String>?,
        trustedAuthservIds: Set<String> = emptySet(),
    ): AuthResults {
        if (instances.isNullOrEmpty()) return AuthResults()
        val trusted = trustedAuthservIds.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        if (trusted.isEmpty()) return parseOne(instances.first())?.results ?: AuthResults()
        // Bounded: a message with thousands of headers must not cost thousands of parses.
        for (value in instances.take(MAX_INSTANCES)) {
            val parsed = parseOne(value) ?: continue
            if (parsed.authservId in trusted) return parsed.results
        }
        return AuthResults()
    }

    internal data class Parsed(
        val authservId: String,
        val results: AuthResults,
    )

    /** Parses one header value; null when it has no authserv-id. Never throws. */
    internal fun parseOne(raw: String): Parsed? {
        val segments = splitSegments(Text.cap(Text.unfold(raw), Text.MAX_HEADER))
        val authservId =
            segments
                .firstOrNull()
                ?.trim()
                ?.split(' ')
                ?.firstOrNull()
                ?.lowercase(Locale.ROOT)
        if (authservId.isNullOrEmpty() || '=' in authservId) return null
        var spf: AuthResult? = null
        var dmarc: AuthResult? = null
        val dkim = mutableListOf<AuthResult>()
        for (segment in segments.drop(1)) {
            // CFWS is allowed around '=' ("spf = pass").
            val first =
                segment
                    .replace(" =", "=")
                    .replace("= ", "=")
                    .trim()
                    .split(' ')
                    .firstOrNull() ?: continue
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            val method = first.substring(0, eq).substringBefore('/').lowercase(Locale.ROOT)
            val result = mapResult(first.substring(eq + 1))
            when (method) {
                "spf" -> if (spf == null) spf = result
                "dkim" -> dkim += result
                "dmarc" -> if (dmarc == null) dmarc = result
            }
        }
        val dkimResult =
            when {
                dkim.isEmpty() -> AuthResult.UNKNOWN
                AuthResult.PASS in dkim -> AuthResult.PASS
                else -> dkim.first()
            }
        return Parsed(authservId, AuthResults(spf ?: AuthResult.UNKNOWN, dkimResult, dmarc ?: AuthResult.UNKNOWN))
    }

    /**
     * Splits on `;` outside quoted strings, dropping `(comments)` (which nest) and the contents of
     * quoted strings. One pass, so hostile input costs linear time.
     */
    private fun splitSegments(s: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inQuote = false
        var i = 0
        while (i < s.length && segments.size < MAX_SEGMENTS) {
            val c = s[i]
            when {
                c == '\\' && (inQuote || depth > 0) -> {
                    i++
                }

                // skip the escaped character
                inQuote -> {
                    if (c == '"') inQuote = false
                }

                depth > 0 -> {
                    when (c) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                }

                c == '(' -> {
                    depth = 1
                }

                c == '"' -> {
                    inQuote = true
                    current.append('"') // keeps a placeholder so `a="x;y"` stays one token
                }

                c == ';' -> {
                    segments += current.toString()
                    current.setLength(0)
                }

                c.isWhitespace() -> {
                    if (current.isNotEmpty() && current.last() != ' ') current.append(' ')
                }

                else -> {
                    current.append(c)
                }
            }
            i++
        }
        if (segments.size < MAX_SEGMENTS) segments += current.toString()
        return segments
    }

    private fun mapResult(value: String): AuthResult =
        when (value.lowercase(Locale.ROOT)) {
            "pass" -> AuthResult.PASS
            "fail", "hardfail" -> AuthResult.FAIL
            "softfail" -> AuthResult.SOFTFAIL
            "neutral", "policy" -> AuthResult.NEUTRAL
            "none" -> AuthResult.NONE
            "temperror" -> AuthResult.TEMPERROR
            "permerror" -> AuthResult.PERMERROR
            else -> AuthResult.UNKNOWN
        }

    private const val MAX_INSTANCES = 50
    private const val MAX_SEGMENTS = 64
}
