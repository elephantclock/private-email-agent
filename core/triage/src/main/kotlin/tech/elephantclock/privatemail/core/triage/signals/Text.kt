package tech.elephantclock.privatemail.core.triage.signals

import java.text.Normalizer
import java.util.Locale

/** Small, linear-time text helpers shared by the signal detectors. Inputs are untrusted. */
internal object Text {
    /** Longest header value we look at; real ones are far shorter. */
    const val MAX_HEADER = 8 * 1024

    /** Longest subject + preview we scan for keywords (the preview itself is at most ~4 KB). */
    const val MAX_SCAN = 8 * 1024

    fun cap(
        s: String,
        max: Int,
    ): String = if (s.length <= max) s else s.substring(0, max)

    /** Undoes RFC 5322 folding (CRLF followed by space or tab). */
    fun unfold(s: String): String =
        s
            .replace("\r\n", "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')

    /**
     * Lower-cased NFKC form with hidden characters removed, for keyword matching.
     * NFKC folds fullwidth and mathematical letters; removing zero-widths stops "co\u200Bde" hiding a keyword.
     */
    fun normalizeForSearch(s: String): String {
        val nfkc =
            try {
                Normalizer.normalize(s, Normalizer.Form.NFKC)
            } catch (_: IllegalArgumentException) {
                s
            }
        val sb = StringBuilder(nfkc.length)
        nfkc.codePoints().forEach { cp -> if (!HiddenCharDetector.isInvisible(cp)) sb.appendCodePoint(cp) }
        // ё and е are used interchangeably in Russian; search on one form.
        return sb.toString().lowercase(Locale.ROOT).replace('ё', 'е')
    }

    /**
     * True when [needle] occurs in [haystack] starting at a word boundary (the character before
     * is not a letter or digit). With [wholeWord] the character after must not be one either.
     */
    fun containsWord(
        haystack: String,
        needle: String,
        wholeWord: Boolean,
    ): Boolean {
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return false
            val beforeOk = i == 0 || !haystack[i - 1].isLetterOrDigit()
            val end = i + needle.length
            val afterOk = !wholeWord || end >= haystack.length || !haystack[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }
}
