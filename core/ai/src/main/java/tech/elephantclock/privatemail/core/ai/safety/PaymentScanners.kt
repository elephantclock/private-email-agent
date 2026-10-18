package tech.elephantclock.privatemail.core.ai.safety

/** Card numbers and IBANs, checksum-validated. Single pass, bounded look-ahead, no regex. */
internal object PaymentScanners {
    // ---- Cards: 13–19 digits, single space or dash between groups, Luhn-valid. ----

    fun cards(
        t: String,
        out: MutableList<SecretSpan>,
    ) {
        var i = 0
        while (i < t.length) {
            if (!t[i].isAsciiDigit() || (i > 0 && t[i - 1].isLetterOrDigit())) {
                i++
                continue
            }
            val digits = StringBuilder()
            var j = i
            var bestEnd = -1
            while (j < t.length && digits.length <= 19) {
                val c = t[j]
                if (c.isAsciiDigit()) {
                    digits.append(c)
                    j++
                    val boundary = j == t.length || !t[j].isAsciiDigit()
                    if (boundary && digits.length in 13..19 && !(j < t.length && t[j].isLetter()) && luhn(digits)) {
                        bestEnd = j
                    }
                } else if ((c == ' ' || c == '-') && j + 1 < t.length && t[j + 1].isAsciiDigit() &&
                    digits.isNotEmpty()
                ) {
                    j++
                } else {
                    break
                }
            }
            if (bestEnd > 0) out += SecretSpan(i, bestEnd, SecretKind.CARD)
            // Skip the whole digit run: each character is visited a bounded number of times.
            i = maxOf(j, i + 1)
        }
    }

    fun luhn(digits: CharSequence): Boolean {
        var sum = 0
        var double = false
        for (k in digits.length - 1 downTo 0) {
            var d = digits[k] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return digits.isNotEmpty() && sum % 10 == 0
    }

    // ---- IBAN: country letters, check digits, up to 34 characters, groups of 1–4; mod-97. ----

    fun ibans(
        t: String,
        out: MutableList<SecretSpan>,
    ) {
        var i = 0
        while (i + 4 <= t.length) {
            val startsIban =
                t[i].isAsciiUpper() && t[i + 1].isAsciiUpper() && t[i + 2].isAsciiDigit() && t[i + 3].isAsciiDigit() &&
                    (i == 0 || !t[i - 1].isLetterOrDigit())
            if (!startsIban) {
                i++
                continue
            }
            val chars = StringBuilder()
            val ends = ArrayList<Pair<Int, Int>>() // (length so far, end index) at word boundaries
            var j = i
            while (j < t.length && chars.length < 34) {
                var k = j
                while (k < t.length && (t[k].isAsciiUpper() || t[k].isAsciiDigit()) && chars.length < 34) {
                    chars.append(t[k])
                    k++
                }
                if (k == j || (k < t.length && t[k].isLetterOrDigit())) {
                    chars.setLength(ends.lastOrNull()?.first ?: 0)
                    break
                }
                ends += chars.length to k
                if (k + 1 < t.length && t[k] == ' ' &&
                    (t[k + 1].isAsciiUpper() || t[k + 1].isAsciiDigit())
                ) {
                    j = k + 1
                } else {
                    break
                }
            }
            val match = ends.lastOrNull { (len, _) -> len >= 15 && mod97(chars, len) }
            if (match != null) out += SecretSpan(i, match.second, SecretKind.IBAN)
            i = maxOf(ends.lastOrNull()?.second ?: (i + 1), i + 1)
        }
    }

    private fun mod97(
        chars: CharSequence,
        len: Int,
    ): Boolean {
        var rem = 0
        for (k in 0 until len) {
            val c = chars[(k + 4) % len]
            val v = if (c.isAsciiDigit()) c - '0' else c - 'A' + 10
            rem = if (v >= 10) (rem * 100 + v) % 97 else (rem * 10 + v) % 97
        }
        return rem == 1
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun Char.isAsciiUpper() = this in 'A'..'Z'
}
