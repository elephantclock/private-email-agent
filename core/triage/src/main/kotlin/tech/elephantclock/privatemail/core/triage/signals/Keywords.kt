package tech.elephantclock.privatemail.core.triage.signals

/**
 * Multilingual keyword signals over the subject and preview: security codes, money and
 * orders/travel. Lists cover English, Russian, German, French, Spanish and Arabic. Matching is
 * plain substring search at a word start on normalised text (no regex), so it is linear.
 *
 * These feed protections, where a false positive only costs a discard, so the lists stay
 * specific: "code" alone would match every code-review notification.
 */
object Keywords {
    /** A security-code phrase and a 4–8 digit code (spaces or one dash allowed: `123 456`, `123-456`). */
    fun securityCode(text: String): Boolean {
        val t = prepare(text)
        return KeywordLists.SECURITY_PHRASES.any { Text.containsWord(t, it, wholeWord = false) } && hasCode(t)
    }

    /** A money phrase: invoices, receipts, payments, refunds, transfers. */
    fun money(text: String): Boolean {
        val t = prepare(text)
        return KeywordLists.MONEY_STEMS.any { Text.containsWord(t, it, wholeWord = false) } ||
            KeywordLists.MONEY_WORDS.any { Text.containsWord(t, it, wholeWord = true) }
    }

    /** Orders, deliveries, bookings and tickets. */
    fun ordersOrTravel(text: String): Boolean {
        val t = prepare(text)
        return KeywordLists.ORDER_STEMS.any { Text.containsWord(t, it, wholeWord = false) }
    }

    /** Reports and monitoring alerts. */
    fun report(text: String): Boolean {
        val t = prepare(text)
        return KeywordLists.REPORT_STEMS.any { Text.containsWord(t, it, wholeWord = false) }
    }

    /**
     * Account-security wording (password resets, new sign-ins). Not a protection, but rules never
     * discard such mail on bulk headers alone: they leave it to the AI.
     */
    fun accountSecurity(text: String): Boolean {
        val t = prepare(text)
        return KeywordLists.ACCOUNT_SECURITY_STEMS.any { Text.containsWord(t, it, wholeWord = false) }
    }

    /** A notification that someone wrote to the user personally ("sent you a message"). */
    fun personalMessage(text: String): Boolean {
        val t = prepare(text)
        return KeywordLists.PERSONAL_MESSAGE_STEMS.any { Text.containsWord(t, it, wholeWord = false) }
    }

    private fun prepare(text: String): String = Text.normalizeForSearch(Text.cap(text, Text.MAX_SCAN))

    /** True when [t] has a run of 4–8 digits not inside a longer number, letters or a date-like token. */
    internal fun hasCode(t: String): Boolean {
        var i = 0
        while (i < t.length) {
            if (!Character.isDigit(t[i]) || (i > 0 && isCodeNeighbour(t, i - 1))) {
                i++
                continue
            }
            var digits = 0
            var j = i
            var separators = 0
            while (j < t.length && digits <= MAX_CODE) {
                val c = t[j]
                when {
                    Character.isDigit(c) -> digits++

                    (c == ' ' || c == '-') && separators == 0 && j + 1 < t.length && Character.isDigit(t[j + 1]) &&
                        digits in 2..4 -> separators++

                    else -> break
                }
                j++
            }
            val endOk = j >= t.length || !isCodeNeighbour(t, j)
            if (digits in MIN_CODE..MAX_CODE && endOk) return true
            i = maxOf(j, i + 1)
        }
        return false
    }

    /**
     * Whether `t[k]` makes an adjacent digit run part of something else: a letter or digit, `/`, `#`,
     * or a `.`/`,` between digits (a decimal or a date). A full stop ending the sentence is fine.
     */
    private fun isCodeNeighbour(
        t: String,
        k: Int,
    ): Boolean {
        val c = t[k]
        if (c.isLetterOrDigit() || c == '/' || c == '#') return true
        if (c != '.' && c != ',') return false
        val before = k > 0 && Character.isDigit(t[k - 1])
        val after = k + 1 < t.length && Character.isDigit(t[k + 1])
        return before && after
    }

    private const val MIN_CODE = 4
    private const val MAX_CODE = 8
}
