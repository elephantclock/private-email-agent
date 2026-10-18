package tech.elephantclock.privatemail.core.triage.signals

/**
 * Finds invisible and direction-changing characters used to disguise a subject or display name
 * ("Pay\u200Bpal", "\u202Efdp.exe"). Only short fields are checked: marketing previews are
 * routinely padded with zero-width characters, so the body would give false alarms.
 *
 * Characters that legitimate text needs are allowed in context: ZWJ/ZWNJ inside emoji sequences
 * and in Arabic or Indic words, LRM/RLM in text that has right-to-left letters, tag characters in
 * subdivision flags, and a byte-order mark at the very start.
 */
object HiddenCharDetector {
    /** True when [text] has a hidden character outside the contexts listed above. */
    fun isSuspicious(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val s = Text.cap(text, Text.MAX_HEADER)
        val hasRtl by lazy(LazyThreadSafetyMode.NONE) { s.codePoints().anyMatch(::isRtlLetter) }
        val hasBlackFlag by lazy(LazyThreadSafetyMode.NONE) { s.contains(BLACK_FLAG) }
        var i = 0
        var prev = -1
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val next = i + Character.charCount(cp)
            val suspicious =
                when {
                    cp in BIDI_CONTROLS -> {
                        true
                    }

                    cp in ALWAYS_HIDDEN -> {
                        true
                    }

                    cp == BOM -> {
                        i != 0
                    }

                    cp == ZWNJ || cp == ZWJ -> {
                        val after = if (next < s.length) s.codePointAt(next) else -1
                        isWordLetter(prev) && isWordLetter(after)
                    }

                    cp in DIRECTION_MARKS -> {
                        !hasRtl
                    }

                    cp in TAGS -> {
                        !hasBlackFlag
                    }

                    else -> {
                        false
                    }
                }
            if (suspicious) return true
            prev = cp
            i = next
        }
        return false
    }

    /** Characters removed before keyword matching: every format character plus the fillers. */
    internal fun isInvisible(cp: Int): Boolean =
        Character.getType(cp) == Character.FORMAT.toInt() || cp in ALWAYS_HIDDEN

    /** A letter or digit of a script where a zero-width joiner has no business inside a word. */
    private fun isWordLetter(cp: Int): Boolean {
        if (cp < 0 || !Character.isLetterOrDigit(cp)) return false
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN,
            Character.UnicodeScript.CYRILLIC,
            Character.UnicodeScript.GREEK,
            Character.UnicodeScript.COMMON,
            -> true

            else -> false
        }
    }

    private fun isRtlLetter(cp: Int): Boolean {
        if (!Character.isLetter(cp)) return false
        val d = Character.getDirectionality(cp)
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    private const val BOM = 0xFEFF
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private const val BLACK_FLAG = "\uD83C\uDFF4"

    /** Embeddings, overrides and isolates: always suspicious in a subject or name. */
    private val BIDI_CONTROLS = (0x202A..0x202E).toSet() + (0x2066..0x2069)

    /** LRM, RLM, ALM: normal in right-to-left text. */
    private val DIRECTION_MARKS = setOf(0x200E, 0x200F, 0x061C)

    private val TAGS = 0xE0000..0xE007F

    /** Zero-width space, word joiner, invisible operators, soft hyphen, CGJ, Mongolian separator, fillers. */
    private val ALWAYS_HIDDEN =
        setOf(0x200B, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x00AD, 0x034F, 0x180E) +
            setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x17B4, 0x17B5)
}
