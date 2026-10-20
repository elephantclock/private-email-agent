package tech.elephantclock.privatemail.core.data.repo

/**
 * Removes quoted history from one message's plain text before it goes to the model (T32), so a
 * thread brief reads each message once. Line-based and linear; English, Russian and German, plus
 * the common French and Spanish attributions.
 *
 * - `>`-quoted lines are dropped.
 * - A reply attribution ("On … wrote:", "… написал(а):", "Am … schrieb …:"), an "Original
 *   Message" separator or an Outlook-style header block (From/Sent/To/Subject and their Russian
 *   and German forms) starts quoted history: everything from there on is cut.
 * - A forward marker ("Forwarded message", "Пересылаемое сообщение", "Weitergeleitete
 *   Nachricht") drops the marker and the header lines after it but **keeps** the forwarded text:
 *   in a forward, that text is the content.
 * - A signature ("-- " line) and everything after it is cut.
 *
 * If nothing is left (a message that is only a quote), the original text is returned trimmed.
 */
object QuoteStripper {
    fun strip(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith(">") -> {
                    i++
                    continue
                }

                line == "-- " || t == "--" -> {
                    break
                }

                isForwardMarker(t) -> {
                    i++
                    while (i < lines.size && (lines[i].isBlank() || isHeaderLine(lines[i].trim()))) i++
                    continue
                }

                isReplySeparator(t) || isAttribution(t, lines.getOrNull(i + 1)?.trim()) || isHeaderBlock(lines, i) -> {
                    break
                }
            }
            out += line.trimEnd()
            i++
        }
        // A rule line (Outlook's "____", "-----") left just above the cut belongs to the quote.
        while (out.isNotEmpty() && out.last().trim().let { it.isEmpty() || it.all { c -> c == '_' || c == '-' } }) {
            out.removeAt(out.lastIndex)
        }
        val result = collapseBlankLines(out).trim()
        return result.ifEmpty { text.trim() }
    }

    private fun isForwardMarker(t: String): Boolean {
        val l = t.lowercase()
        return FORWARD_MARKERS.any { it in l } && (t.startsWith("-") || t.endsWith(":") || t.endsWith("-"))
    }

    private fun isReplySeparator(t: String): Boolean {
        val l = t.lowercase()
        return REPLY_SEPARATORS.any { it in l } && (t.startsWith("-") || t.startsWith("_"))
    }

    /** "On …, Anna <a@x> wrote:" (possibly wrapped onto [next]), and the Russian, German, French, Spanish forms. */
    private fun isAttribution(
        t: String,
        next: String?,
    ): Boolean {
        val l = t.lowercase()
        if (ATTRIBUTION_ENDINGS.any { l.endsWith(it) } &&
            (ATTRIBUTION_STARTS.any { l.startsWith(it) } || RU_DATE.containsMatchIn(l))
        ) {
            return true
        }
        // Gmail in many locales: "пн, 20 окт. 2026 г. в 10:00, Анна <anna@x.ru>:".
        if (l.endsWith(">:") && '@' in l && l.any { it.isDigit() } && t.length < 200) return true
        // "On Mon, 12 Oct 2026 at 10:00, Anna <a@x>" / "wrote:" split by the mail client.
        val n = next?.lowercase() ?: return false
        return ATTRIBUTION_STARTS.any { l.startsWith(it) } && t.length < 200 &&
            ATTRIBUTION_ENDINGS.any { n == it.trim() || n.endsWith(it) }
    }

    /** "From: …" followed within four lines by "Sent:"/"Date:" and "To:"/"Subject:" (any listed language). */
    private fun isHeaderBlock(
        lines: List<String>,
        start: Int,
    ): Boolean {
        val first = lines[start].trim().lowercase()
        if (FROM_LABELS.none { first.startsWith(it) }) return false
        var date = false
        var other = false
        for (k in start + 1..minOf(lines.lastIndex, start + 4)) {
            val l = lines[k].trim().lowercase()
            if (DATE_LABELS.any { l.startsWith(it) }) date = true
            if (OTHER_LABELS.any { l.startsWith(it) }) other = true
        }
        return date && other
    }

    private fun isHeaderLine(t: String): Boolean {
        val l = t.lowercase()
        return (FROM_LABELS + DATE_LABELS + OTHER_LABELS).any { l.startsWith(it) }
    }

    private fun collapseBlankLines(lines: List<String>): String {
        val sb = StringBuilder()
        var blank = 0
        for (l in lines) {
            if (l.isBlank()) {
                blank++
                if (blank > 1) continue
            } else {
                blank = 0
            }
            sb.append(l).append('\n')
        }
        return sb.toString()
    }

    private val FORWARD_MARKERS =
        listOf(
            "forwarded message",
            "begin forwarded message",
            "пересылаемое сообщение",
            "пересланное сообщение",
            "weitergeleitete nachricht",
            "message transféré",
            "mensaje reenviado",
        )
    private val REPLY_SEPARATORS =
        listOf("original message", "исходное сообщение", "ursprüngliche nachricht", "message d'origine")
    private val ATTRIBUTION_STARTS = listOf("on ", "am ", "le ", "el ")
    private val ATTRIBUTION_ENDINGS =
        listOf(
            "wrote:",
            "написал:",
            "написала:",
            "написал(а):",
            "пишет:",
            "schrieb:",
            "geschrieben:",
            "a écrit :",
            "a écrit:",
            "escribió:",
        )

    // Russian attributions start with the date: "20 окт. 2026 г., в 10:00, Анна <a@x> написал(а):".
    private val RU_DATE = Regex("^\\d{1,2}[ .]")
    private val FROM_LABELS = listOf("from:", "от:", "von:", "de :", "de:")
    private val DATE_LABELS =
        listOf("sent:", "date:", "отправлено:", "дата:", "gesendet:", "datum:", "envoyé :", "enviado:")
    private val OTHER_LABELS =
        listOf("to:", "subject:", "кому:", "тема:", "an:", "betreff:", "à :", "objet :", "para:", "asunto:", "cc:")
}
