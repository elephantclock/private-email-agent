package tech.elephantclock.privatemail.core.ai.safety

/** A secret found in text: [start] inclusive, [end] exclusive. */
internal data class SecretSpan(
    val start: Int,
    val end: Int,
    val kind: SecretKind,
)

/**
 * Hand-written detectors, each a single left-to-right pass with bounded look-around, so the
 * cost is linear in the (already capped) input whatever an attacker writes. No regex.
 */
internal object SecretScanners {
    fun scan(text: String): List<SecretSpan> {
        val all = ArrayList<SecretSpan>()
        tokens(text, all)
        labelled(text, all)
        PaymentScanners.cards(text, all)
        PaymentScanners.ibans(text, all)
        codes(text, all)
        // Earliest first, longest first at the same start; drop anything overlapping a kept span.
        all.sortWith(compareBy<SecretSpan> { it.start }.thenByDescending { it.end })
        val kept = ArrayList<SecretSpan>(all.size)
        for (s in all) if (kept.isEmpty() || s.start >= kept.last().end) kept += s
        return kept
    }

    // ---- Tokens: JWTs, known key prefixes, long mixed letter+digit strings. ----

    private val prefixes =
        listOf(
            "sk-",
            "sk_live_",
            "sk_test_",
            "pk_live_",
            "rk_live_",
            "ghp_",
            "gho_",
            "ghu_",
            "ghs_",
            "ghr_",
            "github_pat_",
            "xoxb-",
            "xoxp-",
            "xoxa-",
            "xoxr-",
            "xoxs-",
            "glpat-",
            "AKIA",
            "ASIA",
            "AIza",
            "ya29.",
            "hf_",
            "shpat_",
            "npm_",
        )

    private fun Char.isTokenChar() = isAsciiLetterOrDigit() || this == '_' || this == '-' || this == '+'

    private fun Char.isSegmentChar() =
        isTokenChar() || this == '.' || this == '/' || this == '=' || this == '~' || this == '%'

    private fun tokens(
        t: String,
        out: MutableList<SecretSpan>,
    ) {
        var i = 0
        while (i < t.length) {
            if (!t[i].isSegmentChar()) {
                i++
                continue
            }
            var end = i
            while (end < t.length && t[end].isSegmentChar()) end++
            var segEnd = end
            while (segEnd > i && (t[segEnd - 1] == '.' || t[segEnd - 1] == '=')) segEnd--
            segment(t, i, segEnd, out)
            i = end
        }
    }

    private fun segment(
        t: String,
        start: Int,
        end: Int,
        out: MutableList<SecretSpan>,
    ) {
        if (isJwt(t, start, end)) {
            out += SecretSpan(start, end, SecretKind.JWT)
            return
        }
        if (t.startsWith("ya29.", start) && end - start >= 30) {
            out += SecretSpan(start, end, SecretKind.SECRET)
            return
        }
        // Parts split at URL and base64 punctuation, so a token inside a link is found too.
        var p = start
        while (p < end) {
            var q = p
            while (q < end && t[q].isTokenChar()) q++
            if (q > p && isSecretToken(t, p, q)) out += SecretSpan(p, q, SecretKind.SECRET)
            p = q + 1
        }
    }

    private fun isJwt(
        t: String,
        start: Int,
        end: Int,
    ): Boolean {
        if (!t.startsWith("eyJ", start)) return false
        var dots = 0
        var partLen = 0
        val parts = IntArray(3)
        for (k in start until end) {
            val c = t[k]
            when {
                c == '.' -> {
                    if (dots == 2) return false
                    parts[dots++] = partLen
                    partLen = 0
                }

                c.isAsciiLetterOrDigit() || c == '_' || c == '-' -> {
                    partLen++
                }

                else -> {
                    return false
                }
            }
        }
        if (dots != 2) return false
        parts[2] = partLen
        return parts[0] >= 10 && parts[1] >= 10 && parts[2] >= 8
    }

    private fun isSecretToken(
        t: String,
        start: Int,
        end: Int,
    ): Boolean {
        val len = end - start
        for (prefix in prefixes) {
            if (t.startsWith(prefix, start) && len >= prefix.length + 16) return true
        }
        if (len < 32) return false
        var digits = 0
        var letters = 0
        for (k in start until end) {
            if (t[k].isAsciiDigit()) {
                digits++
            } else if (t[k].isAsciiLetter()) {
                letters++
            }
        }
        return digits >= 2 && letters >= 2
    }

    // ---- "password: xyz", "api_key=…", "пароль: …": mask the value after a label. ----

    private val passwordLabels =
        listOf("password", "passwort", "passcode", "pwd", "пароль", "contraseña", "mot de passe", "senha", "wachtwoord")
    private val secretLabels =
        listOf(
            "api_key",
            "api-key",
            "apikey",
            "api key",
            "secret",
            "client_secret",
            "access_token",
            "auth_token",
            "token",
        )

    private fun labelled(
        t: String,
        out: MutableList<SecretSpan>,
    ) {
        var i = 0
        while (i < t.length) {
            if ((i > 0 && t[i - 1].isLetter()) || !t[i].isLetter()) {
                i++
                continue
            }
            val pw = passwordLabels.firstOrNull { t.regionMatches(i, it, 0, it.length, ignoreCase = true) }
            val label = pw ?: secretLabels.firstOrNull { t.regionMatches(i, it, 0, it.length, ignoreCase = true) }
            if (label == null) {
                i++
                continue
            }
            var j = i + label.length
            if (j < t.length && t[j].isLetter()) {
                i = j
                continue
            }
            var sep = false
            var k = 0
            while (j < t.length && k < 4 && (t[j] == ' ' || t[j] == ':' || t[j] == '=' || t[j] in "-–—")) {
                if (t[j] != ' ') sep = true
                j++
                k++
            }
            if (sep) {
                var v = j
                while (v < t.length && !t[v].isWhitespace() && v - j < 128) v++
                if (v - j >= 3) out += SecretSpan(j, v, if (pw != null) SecretKind.PASSWORD else SecretKind.SECRET)
                i = v
            } else {
                i = j
            }
        }
    }

    // ---- One-time codes: 4–8 digits (or 3+3) near a code keyword. ----

    private val codeWords =
        listOf(
            "code",
            "otp",
            "pin",
            "passcode",
            "verification",
            "verify",
            "one-time",
            "2fa",
            "token",
            "код",
            "пароль",
            "kod",
            "código",
            "codice",
            "bestätigung",
            "einmal",
        )

    private fun codes(
        t: String,
        out: MutableList<SecretSpan>,
    ) {
        // hits[k] = keyword occurrences starting before k, so "any keyword in a window" is O(1).
        val hits = IntArray(t.length + 1)
        for (k in t.indices) {
            val hit =
                (k == 0 || !t[k - 1].isLetter()) &&
                    codeWords.any { t.regionMatches(k, it, 0, it.length, ignoreCase = true) }
            hits[k + 1] = hits[k] + if (hit) 1 else 0
        }
        var i = 0
        while (i < t.length) {
            // Not inside a word, a longer number, a date (12.10.2026) or a time (10:30).
            val glued =
                i > 0 && (t[i - 1].isLetterOrDigit() || (t[i - 1] in ".,/:" && i > 1 && t[i - 2].isAsciiDigit()))
            if (!t[i].isAsciiDigit() || glued) {
                i++
                continue
            }
            var j = i
            var digits = 0
            var groups = 1
            while (j < t.length) {
                if (t[j].isAsciiDigit()) {
                    digits++
                    j++
                } else if ((t[j] == ' ' || t[j] == '-') && groups == 1 && digits == 3 && j + 4 <= t.length &&
                    (j + 1 until j + 4).all { t[it].isAsciiDigit() }
                ) {
                    groups = 2
                    j++
                } else {
                    break
                }
            }
            val standalone =
                j == t.length ||
                    !(t[j].isLetterOrDigit() || (t[j] in ".,/:" && j + 1 < t.length && t[j + 1].isAsciiDigit()))
            if (standalone && digits in 4..8) {
                val from = maxOf(0, i - 80)
                val to = minOf(t.length, j + 40)
                if (hits[to] - hits[from] > 0) out += SecretSpan(i, j, SecretKind.CODE)
            }
            i = maxOf(j, i + 1)
        }
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun Char.isAsciiUpper() = this in 'A'..'Z'

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || isAsciiDigit()
}
