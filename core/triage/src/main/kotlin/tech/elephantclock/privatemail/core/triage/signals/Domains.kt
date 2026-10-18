package tech.elephantclock.privatemail.core.triage.signals

import java.net.IDN
import java.text.Normalizer
import java.util.Locale

/** Domain-name helpers for look-alike detection. Inputs are untrusted; nothing here throws. */
internal object Domains {
    private const val MAX_DOMAIN = 253
    private const val MAX_LABEL = 63

    /** Lower-case ASCII form without a trailing dot, or null when it isn't a usable domain. */
    fun normalize(domain: String?): String? {
        if (domain == null) return null
        val d =
            domain
                .trim()
                .trimEnd('.', '>')
                .lowercase(Locale.ROOT)
        if (d.isEmpty() || d.length > MAX_DOMAIN || '.' !in d || d.startsWith('.')) return null
        if (d.any { it.isWhitespace() || it == '@' || it == '[' || it == ']' }) return null
        return d
    }

    /** Unicode form of an ASCII domain (`xn--` labels decoded); the input if decoding fails. */
    fun toUnicode(ascii: String): String =
        try {
            IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
        } catch (_: RuntimeException) {
            ascii
        }

    /**
     * The registrable domain, by a small heuristic: the last two labels, or the last three under
     * common second-level suffixes such as `co.uk`. A full public-suffix list isn't worth its size here.
     */
    fun registrable(domain: String): String {
        val labels = domain.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_LABEL_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    /** The name part of a registrable domain: `paypal` for `paypal.co.uk`. */
    fun nameLabel(registrable: String): String = registrable.substringBefore('.')

    /**
     * A visual skeleton: compatibility-decomposed, accents dropped, Cyrillic and Greek look-alikes and
     * digit swaps mapped to Latin (`0`→o, `1`/`i`→l, `rn`→m, `vv`→w), hyphens removed. Two labels with
     * the same skeleton look the same to a reader.
     */
    fun skeleton(label: String): String {
        val decomposed =
            try {
                Normalizer.normalize(Text.cap(label, MAX_LABEL * 2), Normalizer.Form.NFKD)
            } catch (_: IllegalArgumentException) {
                label
            }
        val sb = StringBuilder(decomposed.length)
        decomposed.lowercase(Locale.ROOT).codePoints().forEach { cp ->
            val skip = Character.getType(cp) == Character.NON_SPACING_MARK.toInt() || cp == '-'.code || cp == '_'.code
            if (!skip) {
                val mapped = CONFUSABLES[cp]
                if (mapped != null) sb.append(mapped) else sb.appendCodePoint(cp)
            }
        }
        return sb.toString().replace("rn", "m").replace("vv", "w")
    }

    /** How two strings differ when they are exactly one typo apart. */
    enum class Edit { SUBSTITUTION, TRANSPOSITION, INSERTION }

    /** The single edit turning [a] into [b] (either direction for insertions), or null if not one edit apart. */
    fun singleEdit(
        a: String,
        b: String,
    ): Edit? {
        if (a == b) return null
        if (a.length == b.length) {
            var first = -1
            var count = 0
            for (i in a.indices) {
                if (a[i] != b[i]) {
                    if (count == 0) first = i
                    count++
                    if (count > 2) return null
                }
            }
            if (count == 1) return Edit.SUBSTITUTION
            val swapped = first + 1 < a.length && a[first] == b[first + 1] && a[first + 1] == b[first]
            return if (count == 2 && swapped) Edit.TRANSPOSITION else null
        }
        val (long, short) = if (a.length > b.length) a to b else b to a
        if (long.length != short.length + 1) return null
        var i = 0
        while (i < short.length && long[i] == short[i]) i++
        return if (long.regionMatches(i + 1, short, i, short.length - i)) Edit.INSERTION else null
    }

    private val MULTI_LABEL_SUFFIXES =
        setOf(
            "co.uk",
            "org.uk",
            "ac.uk",
            "gov.uk",
            "me.uk",
            "ltd.uk",
            "plc.uk",
            "com.au",
            "net.au",
            "org.au",
            "co.nz",
            "org.nz",
            "com.br",
            "com.ar",
            "com.mx",
            "com.co",
            "com.pe",
            "com.tr",
            "com.cn",
            "com.hk",
            "com.sg",
            "com.tw",
            "com.my",
            "com.ph",
            "com.eg",
            "com.sa",
            "com.ua",
            "org.ua",
            "pp.ua",
            "com.ru",
            "gov.ru",
            "org.ru",
            "msk.ru",
            "spb.ru",
            "co.jp",
            "ne.jp",
            "or.jp",
            "co.kr",
            "co.in",
            "co.il",
            "co.za",
            "co.id",
            "co.th",
            "co.ae",
            "com.kz",
            "com.by",
        )

    /** Look-alike letters (Cyrillic, Greek, Latin variants) and digits mapped to the Latin letter they imitate. */
    private val CONFUSABLES: Map<Int, String> =
        buildMap {
            fun map(
                chars: String,
                to: String,
            ) = chars.forEach { put(it.code, to) }
            map("аɑα", "a")
            map("ьβ", "b")
            map("сϲ", "c")
            map("ԁ", "d")
            map("еёεЄ", "e")
            map("ɡ", "g")
            map("һ", "h")
            map("1iіїıӏιɩǀ|lʟ", "l")
            map("ј", "j")
            map("кκ", "k")
            map("ηп", "n")
            map("0оοσ", "o")
            map("рρ", "p")
            map("ԛ", "q")
            map("ѕ5", "s")
            map("τ", "t")
            map("υ", "u")
            map("νѵ", "v")
            map("ԝωѡ", "w")
            map("хχ", "x")
            map("уγ", "y")
            map("3", "e")
        }
}
